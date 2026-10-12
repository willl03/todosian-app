package com.isotjs.todosian.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure helpers that decide which vault entries surface as folders and lists.
 * SAF enumeration itself needs Android framework classes, so it is exercised on a device.
 */
class FileRepositoryHelpersTest {

    // region isListFileName (Markdown list documents)

    @Test
    fun `lowercase md file is a list`() {
        assertTrue(SafFileRepository.isListFileName("Inbox.md"))
    }

    @Test
    fun `uppercase md extension is a list`() {
        assertTrue(SafFileRepository.isListFileName("Inbox.MD"))
        assertTrue(SafFileRepository.isListFileName("Inbox.Md"))
        assertTrue(SafFileRepository.isListFileName("Inbox.mD"))
    }

    @Test
    fun `non-markdown file is not a list`() {
        assertFalse(SafFileRepository.isListFileName("notes.txt"))
        assertFalse(SafFileRepository.isListFileName("image.png"))
        assertFalse(SafFileRepository.isListFileName("archive.md.bak"))
        assertFalse(SafFileRepository.isListFileName("no-extension"))
    }

    @Test
    fun `sync-conflict files are never lists`() {
        assertFalse(SafFileRepository.isListFileName("sync-conflict-Inbox.md"))
        assertFalse(SafFileRepository.isListFileName("Inbox (sync-conflict).md"))
        assertFalse(SafFileRepository.isListFileName("SYNC-CONFLICT.md"))
    }

    @Test
    fun `null name is not a list`() {
        assertFalse(SafFileRepository.isListFileName(null))
    }

    @Test
    fun `unicode and emoji file names are lists`() {
        assertTrue(SafFileRepository.isListFileName("Ünïcodé 🎉.md"))
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
