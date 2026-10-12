package com.isotjs.todosian.data

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Manual ordering of folders and lists, stored as a hidden JSON document at the vault root
 * ([FILE_NAME]). The file is a **snapshot of the user's preference, not a registry of what
 * exists**:
 *
 * - It is written only when the user drags a row ([write]); nothing else (create, delete,
 *   rename, move, background scans) ever touches it.
 * - Reading is best-effort and fail-open: a missing, corrupt, or future-version file behaves
 *   as "no preference" ([parse] returns null) and callers fall back to A–Z.
 * - The effective order is always computed by [ListOrder.effective]: entries present in the
 *   file (that still exist) first, then everything unknown appended A–Z. That is why new,
 *   renamed, and moved entries land at the bottom automatically, and stale keys are inert
 *   until a drag rewrites the file (which prunes them as a side effect).
 *
 * Keys are paths relative to the vault root, so the file is portable across devices and SAF
 * providers. Folder keys are plain names; list keys are `<parent-folder>/<list>`, with the
 * empty string as the parent key for the unfiled (root-level) lists.
 */
object ListOrder {

    const val FILE_NAME = ".todosian-order.json"
    const val VERSION = 1

    /** Parent key for lists at the vault root (the "Unfiled lists" pseudo-folder). */
    const val UNFILED_KEY = ""

    /** True when [text] parses into a usable order; anything else means "no preference". */
    fun parse(text: String): StoredOrder? {
        return runCatching {
            val root = Json.parseToJsonElement(text).jsonObject
            val version = root["version"]?.jsonPrimitive?.intOrNull ?: return null
            if (version != VERSION) return null

            val folders = root["folders"]?.let { element ->
                element.jsonArray.mapNotNull { entry -> entry.asStringOrNull() }
            } ?: emptyList()

            val lists = root["lists"]?.let { element ->
                element.jsonObject.entries.associate { (parent, value) ->
                    parent to value.jsonArray.mapNotNull { entry -> entry.asStringOrNull() }
                }
            } ?: emptyMap()

            StoredOrder(folders = folders, lists = lists)
        }.getOrNull()
    }

    fun serialize(order: StoredOrder): String {
        return buildJsonObject {
            put("version", JsonPrimitive(VERSION))
            put("folders", JsonArray(order.folders.map { JsonPrimitive(it) }))
            put(
                "lists",
                buildJsonObject {
                    order.lists.forEach { (parent, names) ->
                        put(parent, JsonArray(names.map { JsonPrimitive(it) }))
                    }
                },
            )
        }.toString()
    }

    /**
     * Effective order of [items]: those whose [keyOf] appears in [storedKeys], in that order
     * (stale keys silently skipped), followed by the remaining items sorted A–Z,
     * case-insensitively. Items with duplicate keys (two same-named folders can exist when
     * files are created outside the app) are placed independently. Pure so it can be unit tested.
     */
    fun <T> effective(items: List<T>, keyOf: (T) -> String, storedKeys: List<String>): List<T> {
        if (storedKeys.isEmpty()) {
            return items.sortedWith(compareBy { keyOf(it).lowercase() })
        }

        val used = BooleanArray(items.size)
        val ordered = ArrayList<T>(items.size)
        for (key in storedKeys.distinct()) {
            for ((index, item) in items.withIndex()) {
                if (!used[index] && keyOf(item) == key) {
                    used[index] = true
                    ordered.add(item)
                    break
                }
            }
        }

        val remaining = items.filterIndexed { index, _ -> !used[index] }
            .sortedWith(compareBy { keyOf(it).lowercase() })
        return ordered + remaining
    }

    private fun kotlinx.serialization.json.JsonElement.asStringOrNull(): String? =
        (this as? JsonPrimitive)?.takeIf { it.isString }?.content
}

/** The stored preference: which entries the user has explicitly ordered, in what order. */
data class StoredOrder(
    val folders: List<String> = emptyList(),
    /** Parent folder path → ordered list names. [ListOrder.UNFILED_KEY] for root lists. */
    val lists: Map<String, List<String>> = emptyMap(),
) {
    /** Copy with a replaced folders section; other sections are preserved verbatim. */
    fun withFolderOrder(orderedFolders: List<String>): StoredOrder = copy(folders = orderedFolders)

    /** Copy with a replaced lists section for [parentPath]; other sections preserved. */
    fun withListOrder(parentPath: String, orderedLists: List<String>): StoredOrder =
        copy(lists = lists + (parentPath to orderedLists))
}

/**
 * SAF-backed access to [ListOrder.FILE_NAME] in the vault root. All I/O is serialized by a
 * mutex; reads never throw (null on any problem) and write failures are reported through
 * [write]'s Result so the UI can revert a drag.
 */
class ListOrderStore(private val appContext: Context) {

    private val ioMutex = Mutex()

    /** The stored preference, or null when absent/corrupt/unreadable (no preference set). */
    suspend fun read(folderUri: Uri): StoredOrder? {
        return withContext(Dispatchers.IO) {
            ioMutex.withLock {
                runCatching { readInternal(folderUri) }.getOrNull()
            }
        }
    }

    /**
     * Replaces one section of the stored preference (folders, or the lists of one parent).
     * [transform] receives the current order (empty when none) and returns the new one; the
     * caller is expected to pass the *complete* order of the section's existing entries, which
     * prunes deleted entries as a side effect.
     */
    suspend fun write(folderUri: Uri, transform: (StoredOrder) -> StoredOrder): Result<Unit> {
        return withContext(Dispatchers.IO) {
            ioMutex.withLock {
                runCatching {
                    val root = DocumentFile.fromTreeUri(appContext, folderUri)
                        ?: throw IllegalStateException("Invalid folder URI")
                    val current = runCatching { readInternal(folderUri) }.getOrNull() ?: StoredOrder()
                    val next = transform(current)

                    val file = root.findFile(ListOrder.FILE_NAME)
                    val text = ListOrder.serialize(next)
                    if (file != null) {
                        writeTextInternal(file.uri, text)
                    } else {
                        val created = root.createFile("application/json", ListOrder.FILE_NAME)
                            ?: throw IllegalStateException("Unable to create order file")
                        writeTextInternal(created.uri, text)
                    }
                    Log.d(TAG, "Order file written: folders=${next.folders.size}")
                    Unit
                }
            }
        }
    }

    private fun readInternal(folderUri: Uri): StoredOrder? {
        val root = DocumentFile.fromTreeUri(appContext, folderUri)
            ?: throw IllegalStateException("Invalid folder URI")
        val file = root.findFile(ListOrder.FILE_NAME) ?: return null
        if (!file.isFile) return null
        val text = appContext.contentResolver.openInputStream(file.uri)?.use { input ->
            input.bufferedReader().readText()
        } ?: return null
        return ListOrder.parse(text)
    }

    private fun writeTextInternal(uri: Uri, text: String) {
        appContext.contentResolver.openOutputStream(uri, "w")?.use { out ->
            out.bufferedWriter().use { writer ->
                writer.write(text)
            }
        } ?: throw IllegalStateException("Unable to open order file output stream")
    }

    private companion object {
        private const val TAG = "ListOrderStore"
    }
}
