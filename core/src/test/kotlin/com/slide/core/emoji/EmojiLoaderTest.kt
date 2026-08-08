package com.slide.core.emoji

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class EmojiLoaderTest {

    private val data: EmojiData by lazy {
        File("src/main/assets/${EmojiLoader.ASSET_NAME}").inputStream().use(EmojiLoader::read)
    }

    private fun indexOf(emoji: String) = data.indexOf(emoji).also {
        assertTrue("$emoji is not in the catalogue", it >= 0)
    }

    // region The asset

    @Test
    fun `reads the whole catalogue`() {
        assertTrue("only ${data.size} emoji; the asset looks truncated", data.size > 1500)
        assertEquals(
            listOf(
                "Smileys", "People", "Nature", "Food", "Travel",
                "Activities", "Objects", "Symbols", "Flags",
            ),
            data.categories,
        )
    }

    @Test
    fun `every entry belongs to exactly one category`() {
        val counted = data.categories.indices.sumOf { data.indicesIn(it).size }
        assertEquals(data.size, counted)

        val seen = data.categories.indices.flatMap { data.indicesIn(it).toList() }
        assertEquals("an entry was filed under two categories", data.size, seen.distinct().size)
    }

    @Test
    fun `no entry is empty or duplicated`() {
        val all = (0 until data.size).map { data.emojiAt(it) }
        assertTrue("an entry has no characters", all.none { it.isEmpty() })
        assertEquals("the catalogue repeats an emoji", all.size, all.distinct().size)
    }

    /** Every skin-toned emoji has all five forms, which is what the popup row is sized for. */
    @Test
    fun `toned entries carry a full set of tones`() {
        val toned = (0 until data.size).filter { data.hasVariants(it) }
        assertTrue("no emoji had skin tones at all", toned.size > 200)
        for (index in toned) {
            assertEquals(
                "${data.emojiAt(index)} has an incomplete tone row",
                EmojiData.TONE_COUNT,
                data.variantsAt(index).size,
            )
            assertEquals(
                "${data.emojiAt(index)} repeats a tone",
                EmojiData.TONE_COUNT,
                data.variantsAt(index).distinct().size,
            )
        }
    }

    /**
     * The picker shows one waving hand, not twenty-five handshakes. Combinations that name a
     * separate tone per person are dropped by the build script; this is the check that they were.
     */
    @Test
    fun `no entry is itself a skin-tone form`() {
        val toneModifiers = 0x1F3FB..0x1F3FF
        val offenders = (0 until data.size)
            .map { data.emojiAt(it) }
            .filter { emoji -> emoji.codePoints().anyMatch { it in toneModifiers } }
        assertTrue("toned forms leaked into the grid: ${offenders.take(5)}", offenders.isEmpty())
    }

    // endregion

    // region Tones

    @Test
    fun `applies a chosen skin tone`() {
        val wave = indexOf("👋") // waving hand
        assertTrue(data.hasVariants(wave))

        val dark = data.toned(wave, EmojiData.TONE_COUNT - 1)
        assertNotEquals(data.emojiAt(wave), dark)
        assertEquals(data.variantsAt(wave).last(), dark)
    }

    @Test
    fun `falls back to the default form`() {
        val wave = indexOf("👋")
        assertEquals(data.emojiAt(wave), data.toned(wave, EmojiData.TONE_DEFAULT))
        assertEquals(data.emojiAt(wave), data.toned(wave, EmojiData.TONE_COUNT))

        // An emoji with no tones ignores the setting rather than rendering something else.
        val pizza = indexOf("🍕")
        assertEquals(data.emojiAt(pizza), data.toned(pizza, 0))
    }

    @Test
    fun `finds an emoji by any of its toned forms`() {
        val wave = indexOf("👋")
        for (form in data.variantsAt(wave)) {
            assertEquals("$form should resolve back to the waving hand", wave, data.indexOf(form))
        }
        assertEquals(-1, data.indexOf("not an emoji"))
    }

    // endregion

    // region Search

    @Test
    fun `searches by name and by keyword`() {
        val cases = mapOf(
            "pizza" to "🍕",
            "cat" to "🐱", // cat face
            "japan" to "🇯🇵",
            "thumbs" to "👍",
            "birthday" to "🎂", // a CLDR keyword, not part of "birthday cake"'s own name
        )
        for ((query, expected) in cases) {
            val results = data.search(query).map(data::emojiAt)
            assertTrue("'$query' did not find $expected, got ${results.take(5)}", expected in results)
        }
    }

    @Test
    fun `leads with matches at the start of a word`() {
        // "communications" and "delicate" both contain "cat", so an unranked substring search
        // buries the actual cat behind them.
        val leading = data.search("cat").take(12).map(data::emojiAt)
        assertTrue("'cat' ranked the cat below $leading", "\uD83D\uDC31" in leading)

        val ram = data.search("ram").take(12).map(data::emojiAt)
        assertTrue("'ram' ranked the ram below $ram", "\uD83D\uDC0F" in ram)
    }

    @Test
    fun `returns nothing for an empty or unmatched query`() {
        assertEquals(0, data.search("").size)
        assertEquals(0, data.search("   ").size)
        assertEquals(0, data.search("qzwxjvk").size)
    }

    @Test
    fun `honours the result limit`() {
        // "face" matches hundreds of entries, so this exercises both halves of the ranking.
        assertTrue(data.search("face", limit = 10).size <= 10)
        assertTrue(data.search("face", limit = 10).isNotEmpty())
        assertEquals(data.search("face", limit = 10).size, data.search("face", limit = 10).distinct().size)
    }

    @Test
    fun `is not case sensitive`() {
        assertEquals(data.search("PIZZA").toList(), data.search("pizza").toList())
    }

    // endregion

    // region Corrupt assets

    @Test
    fun `rejects a file that is not a catalogue`() {
        val bytes = "not an emoji catalogue at all".toByteArray()
        assertThrows(IOException::class.java) { EmojiLoader.read(ByteArrayInputStream(bytes)) }
    }

    @Test
    fun `rejects a version it does not understand`() {
        val bytes = File("src/main/assets/${EmojiLoader.ASSET_NAME}").readBytes()
        bytes[4] = 99
        assertThrows(IOException::class.java) { EmojiLoader.read(ByteArrayInputStream(bytes)) }
    }

    @Test
    fun `rejects a truncated file`() {
        val bytes = File("src/main/assets/${EmojiLoader.ASSET_NAME}").readBytes()
        assertThrows(IOException::class.java) {
            EmojiLoader.read(ByteArrayInputStream(bytes.copyOf(bytes.size / 2)))
        }
    }

    // endregion

}
