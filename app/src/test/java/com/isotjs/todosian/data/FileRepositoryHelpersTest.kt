package com.isotjs.todosian.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure helpers that decide which vault entries surface as folders and lists.
 * SAF enumeration itself needs Android framework classes, so it is exercised on a device.
 */
class FileRepositoryHelpersTest {

    // region isCategoryFileName (Markdown list documents)

    @Test
    fun `lowercase md file is a list`() {
        assertTrue(SafFileRepository.isCategoryFileName("Inbox.md"))
    }

    @Test
    fun `uppercase md extension is a list`() {
        assertTrue(SafFileRepository.isCategoryFileName("Inbox.MD"))
        assertTrue(SafFileRepository.isCategoryFileName("Inbox.Md"))
        assertTrue(SafFileRepository.isCategoryFileName("Inbox.mD"))
    }

    @Test
    fun `non-markdown file is not a list`() {
        assertFalse(SafFileRepository.isCategoryFileName("notes.txt"))
        assertFalse(SafFileRepository.isCategoryFileName("image.png"))
        assertFalse(SafFileRepository.isCategoryFileName("archive.md.bak"))
        assertFalse(SafFileRepository.isCategoryFileName("no-extension"))
    }

    @Test
    fun `sync-conflict files are never lists`() {
        assertFalse(SafFileRepository.isCategoryFileName("sync-conflict-Inbox.md"))
        assertFalse(SafFileRepository.isCategoryFileName("Inbox (sync-conflict).md"))
        assertFalse(SafFileRepository.isCategoryFileName("SYNC-CONFLICT.md"))
    }

    @Test
    fun `null name is not a list`() {
        assertFalse(SafFileRepository.isCategoryFileName(null))
    }

    @Test
    fun `unicode and emoji file names are lists`() {
        assertTrue(SafFileRepository.isCategoryFileName("Ünïcodé 🎉.md"))
    }

    // endregion

    // region isIgnoredEntryName (hidden directories and conflict artifacts)

    @Test
    fun `dot-prefixed entries are ignored`() {
        assertTrue(SafFileRepository.isIgnoredEntryName(".obsidian"))
        assertTrue(SafFileRepository.isIgnoredEntryName(".stfolder"))
        assertTrue(SafFileRepository.isIgnoredEntryName(".git"))
    }

    @Test
    fun `sync-conflict directories are ignored`() {
        assertTrue(SafFileRepository.isIgnoredEntryName("sync-conflict-20260101"))
        assertTrue(SafFileRepository.isIgnoredEntryName("Work (sync-conflict)"))
    }

    @Test
    fun `regular folder names are kept`() {
        assertFalse(SafFileRepository.isIgnoredEntryName("Work"))
        assertFalse(SafFileRepository.isIgnoredEntryName("Projects"))
        assertFalse(SafFileRepository.isIgnoredEntryName("Ünïcodé 🎉"))
        assertFalse(SafFileRepository.isIgnoredEntryName("some.other.name"))
    }

    @Test
    fun `null name is ignored`() {
        assertTrue(SafFileRepository.isIgnoredEntryName(null))
    }

    // endregion
}
