package com.isotjs.todosian.ui

import android.net.Uri
import com.isotjs.todosian.data.model.TodoList
import com.isotjs.todosian.data.model.VaultContent

/**
 * One-shot navigation seeds: the screen the user leaves hands the destination what it already
 * knows about the vault layout, so the destination can render its first frame instantly while
 * its own (authoritative) scan runs in the background and reconciles.
 *
 * This is navigation argument-passing, not a content cache: only names/URIs/order are carried,
 * never file contents, each value is consumed once and then cleared, and the very next scan
 * replaces whatever was seeded.
 */
object NavSeed {

    /** Home → folder pager: the full vault layout as known on the Home screen. */
    private var vault: VaultContent? = null

    /** Folder page → list pager: the containing folder's lists and its folder key. */
    private var listPager: ListPagerSeed? = null

    fun seedVault(content: VaultContent) {
        vault = content
    }

    /**
     * Returns the seeded layout when it contains [entryFolderUri] (a real folder) or unfiled
     * lists for an unfiled entry, clearing the seed. Null otherwise → caller shows its loader.
     */
    fun consumeVault(entryFolderUri: Uri, entryIsUnfiled: Boolean): VaultContent? {
        val seed = vault ?: return null
        vault = null
        val entryFound = if (entryIsUnfiled) {
            seed.unfiledLists.isNotEmpty()
        } else {
            seed.folders.any { it.uri == entryFolderUri }
        }
        return seed.takeIf { entryFound }
    }

    fun seedListPager(seed: ListPagerSeed) {
        listPager = seed
    }

    /** Returns the seeded folder contents when they contain [entryListUri], clearing the seed. */
    fun consumeListPager(entryListUri: Uri): ListPagerSeed? {
        val seed = listPager ?: return null
        listPager = null
        return seed.takeIf { seed.lists.any { it.uri == entryListUri } }
    }
}

/** The folder context a list pager needs: its lists (ordered) and its folder key/URI. */
data class ListPagerSeed(
    val parentPath: String,
    val parentFolderUri: Uri,
    val lists: List<TodoList>,
)
