package com.isotjs.todosian.utils

/**
 * Folder colors are carried by the folder's own name: an emoji at the front of the name
 * ("🔴 Work"). Lists inherit it for display. These helpers parse and rewrite that leading
 * emoji; everything else (persistence, ordering, sync) is just the folder's regular name.
 */
private val EMOJI_RANGES = listOf(
    0x1F000..0x1FAFF,   // main emoji blocks (incl. the colored circles 0x1F7E0–0x1F7EF)
    0x1F100..0x1F1FF,   // regional indicators / flags
    0x2600..0x26FF,     // misc symbols
    0x2700..0x27BF,     // dingbats
    0x2B00..0x2BFF,     // misc symbols (⭐ …)
)

fun isEmojiCodePoint(codePoint: Int): Boolean = EMOJI_RANGES.any { codePoint in it }

/**
 * The first emoji cluster of [name] when it begins with an emoji, else null. The cluster is
 * walked with explicit emoji continuation rules (variation selectors, skin tones, flags, ZWJ
 * sequences) rather than platform text iterators, so behavior is identical on Android and in
 * JVM unit tests.
 */
fun leadingEmoji(name: String): String? {
    if (name.isBlank()) return null
    val first = name.codePointAt(0)
    if (!isEmojiCodePoint(first)) return null
    return name.substring(0, emojiClusterEnd(name, start = 0))
}

/** End index (exclusive) of the emoji cluster starting at [start], whose head is an emoji. */
private fun emojiClusterEnd(name: String, start: Int): Int {
    var i = start + Character.charCount(name.codePointAt(start))
    while (i < name.length) {
        val codePoint = name.codePointAt(i)
        when {
            codePoint == 0x200D -> {
                // ZWJ sequence: swallow the joiner plus the emoji that follows it.
                i += Character.charCount(codePoint)
                if (i >= name.length) break
                val joined = name.codePointAt(i)
                if (!isEmojiCodePoint(joined)) break
                i += Character.charCount(joined)
            }
            codePoint in 0xFE00..0xFE0F -> i += Character.charCount(codePoint)      // variation selector
            codePoint in 0x1F3FB..0x1F3FF -> i += Character.charCount(codePoint)    // skin tone modifier
            codePoint in 0x1F1E6..0x1F1FF -> i += Character.charCount(codePoint)    // flag pair (2nd half)
            else -> break
        }
    }
    return i
}

/** Strips every leading emoji cluster (and the spaces after them). */
private fun stripLeadingEmojis(name: String): String {
    var rest = name
    while (true) {
        val emoji = leadingEmoji(rest) ?: break
        rest = rest.removePrefix(emoji).trimStart()
        if (rest.isEmpty()) break
    }
    return rest
}

/**
 * [name] rewritten with [emoji] (null = none) as its leading token, separated by one space.
 * Returns null when that would leave the name empty (a folder cannot be nameless).
 */
fun withLeadingEmoji(name: String, emoji: String?): String? {
    val base = stripLeadingEmojis(name).trim()
    if (base.isEmpty()) return null
    return if (emoji != null) "$emoji $base" else base
}
