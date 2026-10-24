package com.isotjs.todosian.data

import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.isotjs.todosian.data.model.Folder
import com.isotjs.todosian.data.model.Category
import com.isotjs.todosian.data.model.VaultContent
import com.isotjs.todosian.utils.MarkdownParser
import com.isotjs.todosian.utils.TaskMarkers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.isotjs.todosian.ui.widget.WidgetRefresher
import java.time.LocalDate

private const val CONTENT_SCHEME = "content"

internal fun isDocumentWithinFolder(
    folderAuthority: String?,
    candidateScheme: String?,
    candidateAuthority: String?,
    candidateDocumentId: String?,
): Boolean {
    if (candidateScheme != CONTENT_SCHEME) return false
    if (folderAuthority.isNullOrEmpty()) return false
    if (candidateAuthority != folderAuthority) return false
    return !candidateDocumentId.isNullOrEmpty()
}

interface FileRepository {
    fun getFolderUri(): Uri?

    /**
     * Schedules a debounced widget refresh for settings changes that affect widget content
     * (e.g. the todo sort order) but do not touch any file, so no write-triggered refresh runs.
     */
    fun scheduleWidgetRefresh()

    fun isCategoryUri(uri: Uri): Boolean

    fun clearFolderUri()

    suspend fun persistFolderUri(uri: Uri): Result<Unit>

    /**
     * Full vault layout: real folders plus the root-level "Unfiled lists" pseudo-folder.
     * With [includeCounts] = false the todo counts are not computed (files are not read or
     * parsed at all) — use it wherever the counts are not displayed, e.g. a names-only scan.
     * [markers] selects the Tasks-plugin metadata tokens the parser recognizes.
     */
    suspend fun getVaultContent(
        includeCounts: Boolean,
        markers: TaskMarkers = TaskMarkers.Default,
    ): Result<VaultContent>

    /** Every list in the vault, flattened across folders (for widgets, daily focus, pickers). */
    suspend fun getCategories(
        includeCounts: Boolean = true,
        markers: TaskMarkers = TaskMarkers.Default,
    ): Result<List<Category>>

    /** Lists directly inside [parentUri] (the vault root for unfiled lists, or a folder),
     *  ordered by the manual order file for [parentPath] ("" = unfiled), then A–Z. */
    suspend fun getCategoriesIn(
        parentUri: Uri,
        parentPath: String,
        includeCounts: Boolean,
        markers: TaskMarkers = TaskMarkers.Default,
    ): Result<List<Category>>

    suspend fun readLines(uri: Uri): Result<List<String>>

    suspend fun getDisplayName(uri: Uri): Result<String>

    suspend fun getFolderDisplayName(folderUri: Uri): Result<String>

    suspend fun writeLines(uri: Uri, lines: List<String>): Result<Unit>

    suspend fun updateLines(uri: Uri, transform: (List<String>) -> List<String>?): Result<Unit>

    suspend fun createFolder(name: String): Result<Uri>

    /** [currentName] keeps the manual order in sync (renaming the order-file entry in place). */
    suspend fun renameFolder(folderUri: Uri, currentName: String, newName: String): Result<Unit>

    suspend fun deleteFolder(folderUri: Uri): Result<Unit>

    suspend fun createCategory(parentUri: Uri, name: String): Result<Uri>

    /** [parentPath]/[currentName] keep the manual order in sync (order-file entry renamed in place). */
    suspend fun renameCategory(
        parentFolderUri: Uri,
        categoryUri: Uri,
        parentPath: String,
        currentName: String,
        newName: String,
    ): Result<Uri>

    suspend fun deleteCategory(categoryUri: Uri): Result<Unit>

    /**
     * Moves a list document into [targetFolderUri] (a folder URI, or the vault root for unfiled).
     * [sourceParentUri] is the list's current containing directory, or null when the caller cannot
     * provide it. Returns the list's URI after the move (SAF providers may issue a new one).
     */
    suspend fun moveCategory(categoryUri: Uri, sourceParentUri: Uri?, targetFolderUri: Uri): Result<Uri>

    suspend fun moveTodoLine(sourceUri: Uri, targetUri: Uri, lineIndex: Int): Result<Unit>

    suspend fun copyTodoLine(sourceUri: Uri, targetUri: Uri, lineIndex: Int): Result<Unit>

    /**
     * Persists a manual folder order (complete list of folder names, all existing ones). The
     * order file is a preference snapshot; nothing else in the vault is modified.
     */
    suspend fun reorderFolders(orderedFolderPaths: List<String>): Result<Unit>

    /** Persists a manual list order for [parentPath] ("" = unfiled). */
    suspend fun reorderCategories(parentPath: String, orderedListNames: List<String>): Result<Unit>

    fun hasPersistedReadWritePermission(uri: Uri): Boolean

    suspend fun countMarkdownFiles(folderUri: Uri): Result<Int>

    fun observeDocumentChanges(documentUri: Uri): Flow<Unit>

    fun observeMarkdownFilesChanges(folderUri: Uri): Flow<Unit>
}

class SafFileRepository(
    private val appContext: Context,
    private val preferencesManager: PreferencesManager,
) : FileRepository {

    private val orderStore = ListOrderStore(appContext)
    private val widgetUpdateScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val fileMutex = Mutex()

    @Volatile
    private var widgetUpdateJob: Job? = null

    private fun scheduleWidgetUpdate() {
        widgetUpdateJob?.cancel()
        widgetUpdateJob = widgetUpdateScope.launch {
            delay(WIDGET_UPDATE_DEBOUNCE_MS)
            refreshTodosianWidget()
        }
    }

    override fun scheduleWidgetRefresh() = scheduleWidgetUpdate()


    private fun refreshTodosianWidget() {
        widgetUpdateScope.launch {
            runCatching {
                Log.d(TAG, "Refreshing Todosian widgets")
                WidgetRefresher.refreshWidgets(appContext)
            }.onFailure { Log.e(TAG, "Widget refresh failed", it) }
        }
    }

    companion object {
        private const val WIDGET_UPDATE_DEBOUNCE_MS = 50L
        private const val TAG = "SafFileRepository"

        /**
         * True when [name] is a Markdown list document the app should surface: a `.md` file that
         * is not a sync-conflict artifact. Pure so it can be unit tested.
         */
        fun isCategoryFileName(name: String?): Boolean {
            if (name == null) return false
            return name.endsWith(".md", ignoreCase = true) &&
                !name.contains("sync-conflict", ignoreCase = true)
        }

        /**
         * True when a directory entry should be hidden from the vault layout: dot-prefixed
         * entries (`.obsidian`, `.stfolder`, `.git`, …) and sync-conflict artifacts.
         */
        fun isIgnoredEntryName(name: String?): Boolean {
            if (name == null) return true
            return name.startsWith(".") || name.contains("sync-conflict", ignoreCase = true)
        }
    }

    /**
     * Document-form URI for a parent directory. Folder URIs obtained from `listFiles()` are
     * already document URIs; the vault root tree URI is not, so its tree document id is used.
     */
    private fun parentDocumentUri(parentUri: Uri): Uri {
        val docId = if (DocumentsContract.isDocumentUri(appContext, parentUri)) {
            DocumentsContract.getDocumentId(parentUri)
        } else {
            DocumentsContract.getTreeDocumentId(parentUri)
        }
        return DocumentsContract.buildDocumentUriUsingTree(parentUri, docId)
    }

    override fun getFolderUri(): Uri? = preferencesManager.getFolderUri()

    override fun isCategoryUri(uri: Uri): Boolean {
        val folderUri = getFolderUri() ?: return false
        if (folderUri == uri) return false
        val documentId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()
        return isDocumentWithinFolder(
            folderAuthority = folderUri.authority,
            candidateScheme = uri.scheme,
            candidateAuthority = uri.authority,
            candidateDocumentId = documentId,
        )
    }

    private fun <T> foreignUriError(): Result<T> =
        Result.failure(IllegalArgumentException("URI is not a note in the selected folder"))

    override fun clearFolderUri() {
        preferencesManager.clearFolderUri()
        scheduleWidgetUpdate()
    }

    override suspend fun persistFolderUri(uri: Uri): Result<Unit> {
        return withContext(Dispatchers.IO) {
            runCatching {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                appContext.contentResolver.takePersistableUriPermission(uri, flags)
                preferencesManager.saveFolderUri(uri)
            }.onSuccess {
                scheduleWidgetUpdate()
            }
        }
    }

    override suspend fun getVaultContent(
        includeCounts: Boolean,
        markers: TaskMarkers,
    ): Result<VaultContent> {
        val folderUri = getFolderUri() ?: return Result.success(VaultContent(emptyList(), emptyList()))
        return withContext(Dispatchers.IO) {
            runCatching {
                val root = DocumentFile.fromTreeUri(appContext, folderUri)
                    ?: throw IllegalStateException("Invalid folder URI")

                // Manual order preference (best-effort); absent/corrupt file = plain A–Z.
                val order = orderStore.read(folderUri)

                val children = root.listFiles()
                val folders = children.asSequence()
                    .filter { it.isDirectory }
                    .filter { !isIgnoredEntryName(it.name) }
                    .map { dir ->
                        Folder(
                            displayName = dir.name.orEmpty(),
                            uri = dir.uri,
                            lists = dir.listFiles()
                                .asSequence()
                                .filter { it.isFile }
                                .mapNotNull { parseCategoryFile(it, includeCounts, markers) }
                                .toList(),
                        )
                    }
                    .filter { it.displayName.isNotEmpty() }
                    .toList()

                val orderedFolders = ListOrder.effective(
                    items = folders,
                    keyOf = { it.displayName },
                    storedKeys = order?.folders ?: emptyList(),
                ).map { folder ->
                    folder.copy(
                        lists = ListOrder.effective(
                            items = folder.lists,
                            keyOf = { it.displayName },
                            storedKeys = order?.lists?.get(folder.displayName) ?: emptyList(),
                        ),
                    )
                }

                val unfiledLists = children.asSequence()
                    .filter { it.isFile }
                    .mapNotNull { parseCategoryFile(it, includeCounts, markers) }
                    .toList()

                VaultContent(
                    folders = orderedFolders,
                    unfiledLists = ListOrder.effective(
                        items = unfiledLists,
                        keyOf = { it.displayName },
                        storedKeys = order?.lists?.get(ListOrder.UNFILED_KEY) ?: emptyList(),
                    ),
                )
            }
        }
    }

    override suspend fun getCategories(
        includeCounts: Boolean,
        markers: TaskMarkers,
    ): Result<List<Category>> {
        return getVaultContent(includeCounts, markers).mapCatching { it.allLists() }
    }

    override suspend fun getCategoriesIn(
        parentUri: Uri,
        parentPath: String,
        includeCounts: Boolean,
        markers: TaskMarkers,
    ): Result<List<Category>> {
        return withContext(Dispatchers.IO) {
            runCatching {
                // Works for both the vault root tree URI and a folder's document URI:
                // DocumentFile.fromTreeUri resolves the document id from either form.
                val parent = DocumentFile.fromTreeUri(appContext, parentUri)
                    ?: throw IllegalStateException("Invalid folder URI")

                val order = getFolderUri()?.let { orderStore.read(it) }

                val lists = parent.listFiles()
                    .asSequence()
                    .filter { it.isFile }
                    .mapNotNull { parseCategoryFile(it, includeCounts, markers) }
                    .toList()

                ListOrder.effective(
                    items = lists,
                    keyOf = { it.displayName },
                    storedKeys = order?.lists?.get(parentPath) ?: emptyList(),
                )
            }
        }
    }

    /**
     * Reads and parses one `.md` document into a [Category]; null when it is not a list file.
     * With [includeCounts] = false the file is not opened at all and all counts stay 0 —
     * the names-only scans (home rows, pagers, pickers) get a full-speed pass.
     */
    private fun parseCategoryFile(
        file: DocumentFile,
        includeCounts: Boolean,
        markers: TaskMarkers,
    ): Category? {
        val name = file.name
        if (!isCategoryFileName(name)) return null
        val displayName = name.orEmpty().removeSuffix(".md")
        if (!includeCounts) {
            return Category(
                displayName = displayName,
                uri = file.uri,
                todoCount = 0,
                doneCount = 0,
                dueTodayCount = 0,
                overdueCount = 0,
            )
        }
        val lines = readLinesInternal(file.uri)
        val todos = MarkdownParser.parse(lines, markers)
        val doneCount = todos.count { it.isDone }
        val today = LocalDate.now().toString()
        val activeTodos = todos.filter { !it.isDone }
        val dueTodayCount = activeTodos.count { it.dueDate == today }
        val overdueCount = activeTodos.count { todo ->
            todo.dueDate?.let { it < today } == true
        }
        return Category(
            displayName = displayName,
            uri = file.uri,
            todoCount = todos.size,
            doneCount = doneCount,
            dueTodayCount = dueTodayCount,
            overdueCount = overdueCount,
        )
    }

    override suspend fun readLines(uri: Uri): Result<List<String>> {
        if (!isCategoryUri(uri)) return foreignUriError()
        return withContext(Dispatchers.IO) {
            runCatching {
                fileMutex.withLock { readLinesInternal(uri) }
            }
        }
    }

    override suspend fun getDisplayName(uri: Uri): Result<String> {
        if (!isCategoryUri(uri)) return foreignUriError()
        return withContext(Dispatchers.IO) {
            runCatching {
                val file = DocumentFile.fromSingleUri(appContext, uri)
                    ?: throw IllegalStateException("Invalid file URI")
                file.name ?: ""
            }
        }
    }

    override suspend fun getFolderDisplayName(folderUri: Uri): Result<String> {
        return withContext(Dispatchers.IO) {
            runCatching {
                val folder = DocumentFile.fromTreeUri(appContext, folderUri)
                    ?: throw IllegalStateException("Invalid folder URI")
                folder.name
                    ?: folderUri.lastPathSegment
                    ?: folderUri.toString()
            }
        }
    }

    override suspend fun writeLines(uri: Uri, lines: List<String>): Result<Unit> {
        if (!isCategoryUri(uri)) return foreignUriError()
        return withContext(Dispatchers.IO) {
            runCatching {
                fileMutex.withLock { writeLinesInternal(uri, lines) }
            }
        }
    }

    override suspend fun updateLines(uri: Uri, transform: (List<String>) -> List<String>?): Result<Unit> {
        if (!isCategoryUri(uri)) return foreignUriError()
        return withContext(Dispatchers.IO) {
            runCatching {
                fileMutex.withLock {
                    val currentLines = readLinesInternal(uri)
                    val newLines = transform(currentLines) ?: return@withLock
                    writeLinesInternal(uri, newLines)
                }
            }
        }
    }

    override suspend fun createFolder(name: String): Result<Uri> {
        val cleaned = name.trim()
        if (cleaned.isEmpty()) return Result.failure(IllegalArgumentException("Empty name"))

        return withContext(Dispatchers.IO) {
            runCatching {
                val root = rootDocument()
                    ?: throw IllegalStateException("Invalid folder URI")

                val created = root.createDirectory(cleaned)
                    ?: throw IllegalStateException("Unable to create folder")

                // New folders go to the bottom of the home screen instead of falling back
                // to A–Z positioning.
                writeOrder { it.withFolderOrder(it.folders + cleaned) }
                created.uri
            }
        }
    }

    override suspend fun renameFolder(folderUri: Uri, currentName: String, newName: String): Result<Unit> {
        val cleaned = newName.trim()
        if (cleaned.isEmpty()) return Result.failure(IllegalArgumentException("Empty name"))

        return withContext(Dispatchers.IO) {
            runCatching {
                val renamed = DocumentsContract.renameDocument(
                    appContext.contentResolver,
                    folderUri,
                    cleaned,
                )
                if (renamed == null) throw IllegalStateException("Unable to rename folder")

                // Keep the manual order: rename the folder's entry and its lists-section key in
                // place. Best-effort — if the order write fails, the folder falls back to the
                // end of the list (A–Z), which is the pre-ordering behavior anyway.
                if (currentName.isNotBlank()) {
                    writeOrder { it.withRenamedFolder(currentName, cleaned) }
                }
                scheduleWidgetUpdate()
            }
        }
    }

    override suspend fun deleteFolder(folderUri: Uri): Result<Unit> {
        // Capture the name before deletion: it is the folder's key in the manual order file,
        // and a stale entry would let a recreated folder reclaim its old position.
        val folderName = DocumentFile.fromTreeUri(appContext, folderUri)?.name
        return withContext(Dispatchers.IO) {
            runCatching {
                val ok = DocumentsContract.deleteDocument(
                    appContext.contentResolver,
                    parentDocumentUri(folderUri),
                )
                if (!ok) throw IllegalStateException("Unable to delete folder")
                scheduleWidgetUpdate()
            }
        }.recoverCatching { error ->
            // Some SAF providers (SD cards, some cloud mounts) refuse to delete non-empty
            // directories. Delete the children one by one, then the directory itself.
            Log.w(TAG, "deleteDocument failed, falling back to recursive delete", error)
            deleteFolderRecursive(folderUri)
            scheduleWidgetUpdate()
        }.onSuccess {
            // Drop the folder (and its lists' order section) from the manual order file, so
            // recreating a folder with the same name starts at the bottom again.
            if (!folderName.isNullOrEmpty()) {
                writeOrder { order ->
                    order.copy(
                        folders = order.folders - folderName,
                        lists = order.lists - folderName,
                    )
                }
            }
        }
    }

    private suspend fun deleteFolderRecursive(folderUri: Uri) {
        val folder = DocumentFile.fromTreeUri(appContext, folderUri)
            ?: throw IllegalStateException("Invalid folder URI")
        for (child in folder.listFiles()) {
            if (child.isDirectory) {
                deleteFolderRecursive(child.uri)
            } else if (!child.delete()) {
                throw IllegalStateException("Unable to delete file inside folder")
            }
        }
        val ok = DocumentsContract.deleteDocument(
            appContext.contentResolver,
            parentDocumentUri(folderUri),
        )
        if (!ok) throw IllegalStateException("Unable to delete folder")
    }

    override suspend fun createCategory(parentUri: Uri, name: String): Result<Uri> {
        val cleaned = name.trim()
        if (cleaned.isEmpty()) return Result.failure(IllegalArgumentException("Empty name"))

        return withContext(Dispatchers.IO) {
            runCatching {
                val parent = DocumentFile.fromTreeUri(appContext, parentUri)
                    ?: throw IllegalStateException("Invalid folder URI")

                val fileName = if (cleaned.endsWith(".md", ignoreCase = true)) cleaned else "$cleaned.md"
                val created = parent.createFile("text/markdown", fileName)
                    ?: throw IllegalStateException("Unable to create file")

                // New sections go to the bottom of their folder's list instead of falling
                // back to A–Z positioning.
                val orderKey = if (parentUri == getFolderUri()) {
                    ListOrder.UNFILED_KEY
                } else {
                    parent.name
                }
                if (!orderKey.isNullOrEmpty()) {
                    val displayName = fileName.removeSuffix(".md")
                    writeOrder { current ->
                        current.withListOrder(
                            orderKey,
                            (current.lists[orderKey] ?: emptyList()) + displayName,
                        )
                    }
                }

                scheduleWidgetUpdate()
                created.uri
            }
        }
    }

    override suspend fun renameCategory(
        parentFolderUri: Uri,
        categoryUri: Uri,
        parentPath: String,
        currentName: String,
        newName: String,
    ): Result<Uri> {
        val cleaned = newName.trim()
        if (cleaned.isEmpty()) return Result.failure(IllegalArgumentException("Empty name"))

        return withContext(Dispatchers.IO) {
            runCatching {
                val fileName = cleaned
                    .removeSuffix(".md")
                    .removeSuffix(".MD")
                    .removeSuffix(".Md")
                    .removeSuffix(".mD")
                    .trim()
                    .let { base -> if (base.endsWith(".md", ignoreCase = true)) base else "$base.md" }

                // Resolve the document freshly from its parent folder: a previous rename
                // makes SAF hand back a bare document URI, which some providers refuse to
                // rename a second time — the parent listing always carries the current,
                // tree-based URI.
                val parent = DocumentFile.fromTreeUri(appContext, parentFolderUri)
                    ?: throw IllegalStateException("Invalid folder URI")
                val currentFileName =
                    if (currentName.endsWith(".md", ignoreCase = true)) currentName else "$currentName.md"
                val document = parent.findFile(currentFileName)
                    ?: parent.findFile(currentName)
                    ?: DocumentFile.fromSingleUri(appContext, categoryUri)
                    ?: throw IllegalStateException("List not found")

                val renamed = DocumentsContract.renameDocument(appContext.contentResolver, document.uri, fileName)
                    ?: throw IllegalStateException("Unable to rename document")

                // Some providers serve a stale folder listing right after a rename; retry the
                // lookup so `resolved` is the tree-based URI. Adopting the bare document URI
                // returned by renameDocument is what breaks successive renames on those
                // providers (reads keep working on it, renames don't).
                var resolved: Uri = renamed
                for (attempt in 0 until 4) {
                    val found = parent.findFile(fileName)
                    if (found != null) {
                        resolved = found.uri
                        break
                    }
                    if (attempt < 3) delay(150) else Log.w(TAG, "renamed file not visible in parent listing after retries")
                }

                // Keep the manual order: rename the list's entry in its folder's section, in
                // place. Best-effort, same trade-off as folder renames.
                if (currentName.isNotBlank()) {
                    writeOrder { it.withRenamedList(parentPath, currentName, fileName.removeSuffix(".md")) }
                }
                // Keep widget bindings pointing at this list: the rename changes the document
                // URI, exactly like a move does.
                WidgetRefresher.remapSelection(appContext, oldUri = document.uri, newUri = resolved)
                scheduleWidgetUpdate()
                resolved
            }.onFailure { error ->
                Log.e(TAG, "renameCategory failed (current=$currentName new=$newName)", error)
            }
        }
    }

    override suspend fun deleteCategory(categoryUri: Uri): Result<Unit> {
        return withContext(Dispatchers.IO) {
            runCatching {
                val file = DocumentFile.fromSingleUri(appContext, categoryUri)
                    ?: throw IllegalStateException("Invalid file URI")
                val ok = file.delete()
                if (!ok) throw IllegalStateException("Unable to delete file")
                scheduleWidgetUpdate()
            }
        }
    }

    override suspend fun moveCategory(categoryUri: Uri, sourceParentUri: Uri?, targetFolderUri: Uri): Result<Uri> {
        return withContext(Dispatchers.IO) {
            runCatching {
                val treeUri = getFolderUri() ?: throw IllegalStateException("No folder selected")
                val sourceParent = sourceParentUri ?: treeUri

                val moved = DocumentsContract.moveDocument(
                    appContext.contentResolver,
                    categoryUri,
                    parentDocumentUri(sourceParent),
                    parentDocumentUri(targetFolderUri),
                )
                if (moved == null) throw IllegalStateException("Unable to move list")

                // The move may have changed the document URI; keep widget bindings that point at
                // the old URI working by rewriting them to the new one.
                WidgetRefresher.remapSelection(appContext, oldUri = categoryUri, newUri = moved)
                scheduleWidgetUpdate()
                moved
            }
        }.recoverCatching { error ->
            // Some providers do not support moveDocument. Fall back to copy-then-delete, which
            // preserves the document content (all lines, including non-todo ones).
            Log.w(TAG, "moveDocument failed, falling back to copy+delete", error)
            moveCategoryByCopyDelete(categoryUri, targetFolderUri)
        }
    }

    /** Fallback for providers without moveDocument: copy the content, then delete the original. */
    private suspend fun moveCategoryByCopyDelete(categoryUri: Uri, targetFolderUri: Uri): Uri {
        val name = getDisplayName(categoryUri).getOrThrow().removeSuffix(".md")
        if (name.isEmpty()) throw IllegalStateException("Unable to resolve list name")

        val lines = readLines(categoryUri).getOrThrow()
        val created = createCategory(targetFolderUri, name).getOrThrow()
        try {
            writeLines(created, lines).getOrThrow()
        } catch (e: Exception) {
            // Never leave a half-written copy behind; keep the original intact.
            runCatching { deleteCategory(created) }
            throw e
        }
        deleteCategory(categoryUri).getOrThrow()

        WidgetRefresher.remapSelection(appContext, oldUri = categoryUri, newUri = created)
        scheduleWidgetUpdate()
        return created
    }

    override suspend fun moveTodoLine(sourceUri: Uri, targetUri: Uri, lineIndex: Int): Result<Unit> {
        if (!isCategoryUri(sourceUri) || !isCategoryUri(targetUri)) return foreignUriError()
        return withContext(Dispatchers.IO) {
            runCatching {
                val sourceLines = readLinesInternal(sourceUri)
                val targetLines = readLinesInternal(targetUri)
                val updated = MarkdownParser.tryMoveTodoLine(
                    sourceLines = sourceLines,
                    lineIndex = lineIndex,
                    targetLines = targetLines,
                ) ?: throw IllegalStateException("Invalid todo line")

                writeLinesInternal(sourceUri, updated.first)
                writeLinesInternal(targetUri, updated.second)
            }
        }
    }

    override suspend fun copyTodoLine(sourceUri: Uri, targetUri: Uri, lineIndex: Int): Result<Unit> {
        if (!isCategoryUri(sourceUri) || !isCategoryUri(targetUri)) return foreignUriError()
        return withContext(Dispatchers.IO) {
            runCatching {
                val sourceLines = readLinesInternal(sourceUri)
                val targetLines = readLinesInternal(targetUri)
                val updated = MarkdownParser.tryCopyTodoLine(
                    sourceLines = sourceLines,
                    lineIndex = lineIndex,
                    targetLines = targetLines,
                ) ?: throw IllegalStateException("Invalid todo line")

                writeLinesInternal(targetUri, updated.second)
            }
        }
    }

    override suspend fun reorderFolders(orderedFolderPaths: List<String>): Result<Unit> =
        writeOrder { it.withFolderOrder(orderedFolderPaths) }

    override suspend fun reorderCategories(parentPath: String, orderedListNames: List<String>): Result<Unit> =
        writeOrder { it.withListOrder(parentPath, orderedListNames) }

    /**
     * Writes one order-file section through the store, pruning folder entries and lists
     * sections whose parent no longer exists (folders renamed/deleted outside the app would
     * otherwise leave stale keys forever). Also refreshes widgets, whose "All lists" order
     * follows the manual order.
     */
    private suspend fun writeOrder(transform: (StoredOrder) -> StoredOrder): Result<Unit> {
        val folderUri = getFolderUri() ?: return Result.failure(IllegalStateException("No folder selected"))
        val validParents = runCatching {
            rootDocument()?.listFiles()
                ?.filter { it.isDirectory && !isIgnoredEntryName(it.name) }
                ?.mapNotNull { it.name }
                ?.toSet()
        }.getOrNull()
        return orderStore.write(folderUri) { transform(it).pruneSections(validParents) }
            .onSuccess { scheduleWidgetUpdate() }
    }

    override fun hasPersistedReadWritePermission(uri: Uri): Boolean {
        val perms = appContext.contentResolver.persistedUriPermissions
        val perm = perms.firstOrNull { it.uri == uri } ?: return false
        return perm.isReadPermission && perm.isWritePermission
    }

    override suspend fun countMarkdownFiles(folderUri: Uri): Result<Int> {
        return withContext(Dispatchers.IO) {
            runCatching {
                val root = DocumentFile.fromTreeUri(appContext, folderUri)
                    ?: throw IllegalStateException("Invalid folder URI")

                var count = 0
                for (entry in root.listFiles()) {
                    if (isCategoryFileName(entry.name) && entry.isFile) {
                        count++
                    } else if (entry.isDirectory && !isIgnoredEntryName(entry.name)) {
                        count += entry.listFiles().count { it.isFile && isCategoryFileName(it.name) }
                    }
                }
                count
            }
        }
    }

    override fun observeDocumentChanges(documentUri: Uri): Flow<Unit> {
        return observeUrisChanges(
            uris = listOf(documentUri),
            notifyDescendants = false,
        )
    }

    override fun observeMarkdownFilesChanges(folderUri: Uri): Flow<Unit> {
        val treeDocId = DocumentsContract.getTreeDocumentId(folderUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(folderUri, treeDocId)

        return callbackFlow {
            val resolver = appContext.contentResolver

            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            var updateJob = scope.launch { }
            updateJob.cancel()

            var observedUris: List<Pair<Uri, Boolean>> = emptyList()

            lateinit var observer: ContentObserver

            fun dirChildrenUri(dirUri: Uri): Uri {
                val dirDocId = DocumentsContract.getDocumentId(dirUri)
                return DocumentsContract.buildChildDocumentsUriUsingTree(dirUri, dirDocId)
            }

            fun currentMarkdownUris(): List<Pair<Uri, Boolean>> {
                val root = DocumentFile.fromTreeUri(appContext, folderUri) ?: return emptyList()

                val uris = mutableListOf<Pair<Uri, Boolean>>()
                // structural changes at the vault root
                uris.add(folderUri to true)
                uris.add(childrenUri to true)

                for (entry in root.listFiles()) {
                    if (entry.isDirectory) {
                        if (isIgnoredEntryName(entry.name)) continue
                        // structural changes inside a folder (lists created/renamed/deleted)
                        uris.add(entry.uri to true)
                        uris.add(dirChildrenUri(entry.uri) to true)
                    } else if (isCategoryFileName(entry.name)) {
                        // content changes of a root-level (unfiled) list
                        uris.add(entry.uri to false)
                    }
                }
                // content changes of lists inside folders
                for (entry in root.listFiles()) {
                    if (!entry.isDirectory || isIgnoredEntryName(entry.name)) continue
                    for (file in entry.listFiles()) {
                        if (file.isFile && isCategoryFileName(file.name)) {
                            uris.add(file.uri to false)
                        }
                    }
                }
                return uris
            }

            fun scheduleUpdate() {
                updateJob.cancel()
                updateJob = scope.launch {
                    delay(200)
                    val next = runCatching { currentMarkdownUris() }.getOrElse { emptyList() }
                    if (next == observedUris) return@launch

                    runCatching { resolver.unregisterContentObserver(observer) }
                    observedUris = next
                    runCatching {
                        observedUris.forEach { (uri, notifyDescendants) ->
                            resolver.registerContentObserver(uri, notifyDescendants, observer)
                        }
                    }.onFailure { t ->
                        close(t)
                    }
                }
            }

            observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean, changedUri: Uri?) {
                    trySend(Unit)
                    val changed = changedUri?.toString().orEmpty()
                    // Every observed URI (tree root, children collections, list documents) sits
                    // below the tree URI; only react to changes from our own tree. The trailing
                    // separator avoids matching a sibling tree whose id merely starts alike.
                    val shouldUpdate = changedUri == null || changed.startsWith("$folderUri/")
                    if (shouldUpdate) scheduleUpdate()
                }
            }

            // initial register
            observedUris = listOf(
                folderUri to true,
                childrenUri to true,
            )
            runCatching {
                observedUris.forEach { (uri, notifyDescendants) ->
                    resolver.registerContentObserver(uri, notifyDescendants, observer)
                }
            }.onFailure { t ->
                close(t)
                return@callbackFlow
            }

            scheduleUpdate()

            awaitClose {
                updateJob.cancel()
                scope.cancel()
                runCatching { resolver.unregisterContentObserver(observer) }
            }
        }.conflate()
    }

    private fun observeUrisChanges(
        uris: List<Uri>,
        notifyDescendants: Boolean,
    ): Flow<Unit> {
        return callbackFlow {
            val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    trySend(Unit)
                }

                override fun onChange(selfChange: Boolean, changedUri: Uri?) {
                    trySend(Unit)
                }
            }

            val resolver = appContext.contentResolver
            runCatching {
                uris.forEach { uri ->
                    resolver.registerContentObserver(uri, notifyDescendants, observer)
                }
            }.onFailure { t ->
                close(t)
                return@callbackFlow
            }

            awaitClose {
                runCatching { resolver.unregisterContentObserver(observer) }
            }
        }.conflate()
    }

    private fun rootDocument(): DocumentFile? {
        val folderUri = getFolderUri() ?: return null
        return DocumentFile.fromTreeUri(appContext, folderUri)
    }

    private fun readLinesInternal(uri: Uri): List<String> {
        appContext.contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
            return reader.readLines()
        } ?: throw IllegalStateException("Unable to open input stream")
    }

    private fun writeLinesInternal(uri: Uri, lines: List<String>) {
        appContext.contentResolver.openOutputStream(uri, "w")?.use { out ->
            runCatching { (out as? java.io.FileOutputStream)?.channel?.truncate(0) }
            out.bufferedWriter().use { writer ->
                lines.forEachIndexed { index, line ->
                    if (index > 0) writer.newLine()
                    writer.write(line)
                }
            }
        } ?: throw IllegalStateException("Unable to open output stream")

        try {
            appContext.contentResolver.notifyChange(uri, null)
        } catch (e: Exception) {
            // Ignore
        }
        scheduleWidgetUpdate()
    }
}
