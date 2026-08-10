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
        val sorted = words.sorted()
        val chars = sorted.joinToString("").toCharArray()
        val offsets = IntArray(sorted.size + 1)
        var cursor = 0
        sorted.forEachIndexed { index, word ->
            offsets[index] = cursor
            cursor += word.length
        }
        offsets[sorted.size] = cursor
        return Lexicon(chars, offsets, ByteArray(sorted.size) { 100 }, ByteArray(sorted.size))
    }

    @Test
    fun `recovers a word from confident CTC emissions`() {
        val candidates = search.decode(emissionsFor("computer"), true, null)

        assertEquals("computer", candidates.first().word.lowercase())
    }

    @Test
    fun `apostrophe surface forms share their ungestured alpha path`() {
        val candidates = search.decode(emissionsFor("dont"), true, null)

        assertTrue(candidates.any { it.word.lowercase() == "don't" })
    }

    private fun emissionsFor(word: String): FloatArray {
        val output = FloatArray(32 * 27) { -18f }
        for (time in 0 until 32) output[time * 27 + 26] = 0f
        var time = 0
        for (letter in word) {
            output[time * 27 + 26] = -18f
            output[time * 27 + (letter - 'a')] = 0f
            time += 2
        }
        return output
    }
}
