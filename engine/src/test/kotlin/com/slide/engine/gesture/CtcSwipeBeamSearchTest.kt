package com.slide.engine.gesture

import com.slide.engine.TestBigrams
import com.slide.engine.TestLexicon
import com.slide.engine.lexicon.Lexicon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CtcSwipeBeamSearchTest {
    private val search by lazy {
        CtcSwipeBeamSearch(TestLexicon.instance, TestBigrams.instance, userBigrams = null)
    }

    @Test
    fun `trie indexes emitted word paths`() {
        val tiny = tinyLexicon("computer", "compute", "don't")
        val tinyTrie = SwipeLexiconTrie(tiny)
        val tinyComputer = tinyTrie.nodeFor("computer")
        assertTrue(
            "tiny trie lost computer path: node=$tinyComputer root=${tinyTrie.childLetters(0)}",
            tinyComputer >= 0,
        )
        assertTrue(
            "tiny trie lost computer terminal at $tinyComputer",
            tinyTrie.terminalCount(tinyComputer) > 0,
        )
        val trie = SwipeLexiconTrie(TestLexicon.instance)

        assertTrue("lexicon lacks computer", TestLexicon.instance.contains("computer"))
        assertTrue("lexicon lacks don't", TestLexicon.instance.contains("don't"))
        val computerNode = trie.nodeFor("computer")
        var prefix = ""
        var matched = ""
        for (char in "computer") {
            prefix += char
            if (trie.nodeFor(prefix) >= 0) matched = prefix else break
        }
        assertTrue(
            "computerNode=$computerNode depth=${if (computerNode >= 0) trie.depth(computerNode) else -1} " +
                "terminals=${trie.terminalCount(computerNode)} nodes=${trie.nodeCount} matched=$matched",
            trie.terminalCount(computerNode) > 0,
        )
        assertTrue(trie.terminalCount(trie.nodeFor("dont")) > 0)
    }

    private fun tinyLexicon(vararg words: String): Lexicon {
        return tinyLexicon(*words.map { it to 100 }.toTypedArray())
    }

    private fun tinyLexicon(vararg words: Pair<String, Int>): Lexicon {
        val sorted = words.sortedBy { it.first }
        val chars = sorted.joinToString("") { it.first }.toCharArray()
        val offsets = IntArray(sorted.size + 1)
        var cursor = 0
        sorted.forEachIndexed { index, (word, _) ->
            offsets[index] = cursor
            cursor += word.length
        }
        offsets[sorted.size] = cursor
        val frequencies = ByteArray(sorted.size) { index -> sorted[index].second.toByte() }
        return Lexicon(chars, offsets, frequencies, ByteArray(sorted.size))
    }

    @Test
    fun `recovers a word from confident CTC emissions`() {
        val candidates = search.decode(emissionsFor("computer"), true, null)

        assertEquals("computer", candidates.first().word.lowercase())
        val words = candidates.map { it.word.lowercase() }
        assertEquals(words.distinct(), words)
    }

    @Test
    fun `apostrophe surface forms share their ungestured alpha path`() {
        val candidates = search.decode(emissionsFor("dont"), true, null)

        assertTrue(candidates.any { it.word.lowercase() == "don't" })
    }

    @Test
    fun `neural language prior uses raw vocabulary frequency`() {
        val commonFrequency = 160
        val uncommonFrequency = 93
        val tiny = tinyLexicon("that's" to commonFrequency, "thats" to uncommonFrequency)
        val candidates = CtcSwipeBeamSearch(tiny, null, null)
            .decode(emissionsFor("thats"), true, null)
            .associateBy { it.word.lowercase() }

        val common = requireNotNull(candidates["that's"])
        val uncommon = requireNotNull(candidates["thats"])
        assertEquals(
            0.0134f * (commonFrequency - uncommonFrequency),
            common.score - uncommon.score,
            0.0001f,
        )
        assertTrue(common.score > uncommon.score)
    }

    @Test
    fun `double letters do not require an artificial blank`() {
        val tiny = tinyLexicon("later", "letter", "litter")
        val repeatedSearch = CtcSwipeBeamSearch(tiny, null, null)
        val candidates = repeatedSearch.decode(
            emissionsFor("letter", blankBetweenLetters = false),
            true,
            null,
        )

        assertEquals("letter", candidates.first().word.lowercase())
    }

    private fun emissionsFor(word: String, blankBetweenLetters: Boolean = true): FloatArray {
        val output = FloatArray(32 * 27) { -18f }
        for (time in 0 until 32) output[time * 27 + 26] = 0f
        var time = 0
        for (letter in word) {
            output[time * 27 + 26] = -18f
            output[time * 27 + (letter - 'a')] = 0f
            time += if (blankBetweenLetters) 2 else 1
        }
        return output
    }
}
