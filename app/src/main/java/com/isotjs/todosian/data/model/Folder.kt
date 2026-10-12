package com.isotjs.todosian.data.model

import android.net.Uri

/**
 * A directory in the vault that holds [Category] documents.
 *
 * The vault root itself appears as the virtual folder [isUnfiled] == true ("Unfiled lists"): it
 * groups the `.md` files that sit directly at the root of the selected tree, so lists created
 * before folders existed stay visible. Its [uri] is the vault root tree URI, which is also a
 * valid parent for creating/moving lists.
 */
data class Folder(
    val displayName: String,
    val uri: Uri,
    val lists: List<Category>,
    val isUnfiled: Boolean = false,
)

/** Snapshot of the vault layout: real folders (A–Z) plus the root-level unfiled lists. */
data class VaultContent(
    val folders: List<Folder>,
    val unfiledLists: List<Category>,
) {
    /** Every list in the vault, unfiled first, then folder by folder (folder order as stored). */
    fun allLists(): List<Category> = unfiledLists + folders.flatMap { it.lists }
}
