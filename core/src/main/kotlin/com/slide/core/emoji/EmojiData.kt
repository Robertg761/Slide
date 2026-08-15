package com.slide.core.emoji

/**
 * The emoji catalogue, in the order a picker wants to show it.
 *
 * Entries keep the order of `emoji-test.txt`, which is CLDR's recommended presentation order —
 * the same order Gboard and every other keyboard uses, because it is the one people have learned.
 *
 * Skin-tone variants hang off their base entry rather than sitting beside it in the grid, so
 * "waving hand" is one key with a long-press rather than six keys that look almost identical.
 *
 * Built by `tools/build_emoji.py`.
 */
class EmojiData(
    /** Display names for the tab strip, in tab order. */
    val categories: List<String>,
    private val emoji: Array<String>,
    private val categoryOf: ByteArray,
    private val variants: Array<Array<String>?>,
    private val searchText: Array<String>,
) {

    val size: Int get() = emoji.size

    /**
     * Entry indices per category, in presentation order.
     *
     * The source file is grouped, so these are contiguous runs in practice, but the picker only
     * ever needs the list and building it here means nothing downstream depends on that holding.
     */
    private val byCategory: Array<IntArray> = buildCategoryIndex()

    fun emojiAt(index: Int): String = emoji[index]

    /** The five skin-tone forms of this emoji, or empty when it has none. */
    fun variantsAt(index: Int): List<String> = variants[index]?.asList() ?: emptyList()

    fun hasVariants(index: Int): Boolean = variants[index] != null

    fun indicesIn(category: Int): IntArray =
        if (category in byCategory.indices) byCategory[category] else EMPTY

    /**
     * The form of this emoji for the user's chosen skin tone, falling back to the default form.
     *
     * [tone] is an index into [variantsAt], or [TONE_DEFAULT] for the yellow form. Every emoji
     * that supports tones supports all five, so a tone that is set never lands on a gap.
     */
    fun toned(index: Int, tone: Int): String {
        val forms = variants[index] ?: return emoji[index]
        return if (tone in forms.indices) forms[tone] else emoji[index]
    }

    /**
     * Emoji matching [query], best first, capped at [limit].
     *
     * A match at the start of any word in the name or its keywords beats a match buried inside
     * one, so searching "cat" leads with the cat rather than with "communications".
     */
    fun search(query: String, limit: Int = 60): IntArray {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return EMPTY

        val leading = IntArray(limit)
        var leadingCount = 0
        val trailing = IntArray(limit)
        var trailingCount = 0

        for (index in emoji.indices) {
            val at = searchText[index].indexOf(needle)
            if (at < 0) continue
            val atWordStart = at == 0 || searchText[index][at - 1] == ' '
            if (atWordStart) {
                if (leadingCount < limit) leading[leadingCount++] = index
                // Nothing beats a full page of word-start matches, so stop looking for weaker ones.
                if (leadingCount == limit) break
            } else if (trailingCount < limit) {
                trailing[trailingCount++] = index
            }
        }

        val total = minOf(limit, leadingCount + trailingCount)
        val result = IntArray(total)
        leading.copyInto(result, 0, 0, minOf(leadingCount, total))
        if (leadingCount < total) {
            trailing.copyInto(result, leadingCount, 0, total - leadingCount)
        }
        return result
    }

    /**
     * Every form of every emoji, mapped back to its entry index.
     *
     * Built once on first use: [indexOf] is called per cell while the recents grid binds, and a
     * linear scan over ~2,000 entries and their tone variants per cell is measurable UI-thread
     * work, while this map is a few tens of kilobytes.
     */
    private val indexByForm: Map<String, Int> by lazy {
        HashMap<String, Int>(emoji.size * 2).also { map ->
            for (index in emoji.indices) {
                map.putIfAbsent(emoji[index], index)
                variants[index]?.forEach { form -> map.putIfAbsent(form, index) }
            }
        }
    }

    /** The index of an emoji in any of its toned forms, or -1. Used to resolve recents. */
    fun indexOf(value: String): Int = indexByForm[value] ?: -1

    /**
     * Category ids are stored one to a byte, so they have to be read back unsigned.
     *
     * Kotlin's `Byte` is signed: without the mask, a catalogue with more than 128 categories turns
     * id 130 into -126 and indexes out of the array before the picker ever draws anything.
     */
    private fun categoryAt(index: Int): Int = categoryOf[index].toInt() and 0xFF

    private fun buildCategoryIndex(): Array<IntArray> {
        val counts = IntArray(categories.size)
        for (index in emoji.indices) counts[categoryAt(index)]++

        val result = Array(categories.size) { IntArray(counts[it]) }
        val cursor = IntArray(categories.size)
        for (index in emoji.indices) {
            val category = categoryAt(index)
            result[category][cursor[category]++] = index
        }
        return result
    }

    companion object {
        /** The yellow form, used when the user has not chosen a skin tone. */
        const val TONE_DEFAULT = -1

        /** Every emoji with tones has all five, so this is the length of any [variantsAt]. */
        const val TONE_COUNT = 5

        private val EMPTY = IntArray(0)
    }
}
