package com.isotjs.todosian.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EmojiNameTest {

    // region leadingEmoji

    @Test
    fun `plain names have no leading emoji`() {
        assertNull(leadingEmoji("Work"))
        assertNull(leadingEmoji(""))
        assertNull(leadingEmoji("   "))
    }

    @Test
    fun `colored circle emojis are detected`() {
        assertEquals("🔴", leadingEmoji("🔴 Work"))
        assertEquals("🟢", leadingEmoji("🟢Personal"))
        // A name that is only an emoji is still a leading emoji…
        assertEquals("🟡", leadingEmoji("🟡"))
    }

    @Test
    fun `multi codepoint emojis are one cluster`() {
        assertEquals("👨‍👩‍👧", leadingEmoji("👨‍👩‍👧 Family"))
        assertEquals("⚔️", leadingEmoji("⚔️ Battles"))
    }

    @Test
    fun `numbers and letters are never emoji`() {
        assertNull(leadingEmoji("1. Work"))
        assertNull(leadingEmoji("A Work"))
        assertNull(leadingEmoji("# Work"))
    }

    @Test
    fun `emoji later in the name does not count`() {
        assertNull(leadingEmoji("Party 🎉"))
    }

    // endregion

    // region withLeadingEmoji

    @Test
    fun `prepends emoji with one space`() {
        assertEquals("🔴 Work", withLeadingEmoji("Work", "🔴"))
    }

    @Test
    fun `normalizes missing or doubled spaces`() {
        assertEquals("🟢 Work", withLeadingEmoji("🔴Work", "🟢"))
        assertEquals("🟢 Work", withLeadingEmoji("🔴   Work", "🟢"))
        assertEquals("🟢 Work", withLeadingEmoji("  Work ", "🟢"))
    }

    @Test
    fun `none strips every leading emoji`() {
        assertEquals("Work", withLeadingEmoji("🔴 Work", null))
        assertEquals("Work", withLeadingEmoji("🔴🟠 Work", null))
        assertEquals("Work", withLeadingEmoji("🔴Work", null))
    }

    @Test
    fun `refuses to leave an emoji-only folder nameless`() {
        assertNull(withLeadingEmoji("🔴", null))
        assertNull(withLeadingEmoji("🔴", "🟢"))
        // But a real color can still be applied if there is any other content.
        assertEquals("🟢 dot", withLeadingEmoji("🔴 dot", "🟢"))
    }

    @Test
    fun `round trip replace keeps the base name untouched`() {
        val original = "Ünïcodé 🎉 folder"
        val renamed = withLeadingEmoji(original, "🔵")!!
        assertEquals("🔵", leadingEmoji(renamed))
        assertEquals(original, withLeadingEmoji(renamed, null))
    }

    // endregion
}
