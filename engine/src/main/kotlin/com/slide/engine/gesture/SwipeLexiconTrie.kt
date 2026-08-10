package com.slide.engine.gesture

import com.slide.engine.lexicon.Lexicon

/** Compact a-z trie shared by neural swipe search and bounded typed-word correction. */
class SwipeLexiconTrie(private val lexicon: Lexicon) {
    private var firstChild = IntArray(INITIAL_NODES) { NONE }
    private var nextSibling = IntArray(INITIAL_NODES) { NONE }
    private var labels = ByteArray(INITIAL_NODES) { ROOT_LABEL }
    private var depths = ByteArray(INITIAL_NODES)
    private var terminalHeads = IntArray(INITIAL_NODES) { NONE }
    private val terminalWords = IntArray(lexicon.size)
    private val terminalNext = IntArray(lexicon.size) { NONE }

    var nodeCount: Int = 1
        private set
    private var terminalCount = 0

    init {
        for (wordIndex in 0 until lexicon.size) insert(wordIndex)
    }

    fun child(node: Int, letter: Int): Int {
        var child = firstChild[node]
        while (child != NONE) {
            if (labels[child].toInt() == letter) return child
            child = nextSibling[child]
        }
        return NONE
    }

    fun firstChild(node: Int): Int = firstChild[node]

    fun nextSibling(node: Int): Int = nextSibling[node]

    fun lastLetter(node: Int): Int = labels[node].toInt()

    fun depth(node: Int): Int = depths[node].toInt() and 0xFF

    /** Trie node for an emitted a-z path, or -1. Useful for decoder diagnostics and tests. */
    fun nodeFor(alpha: String): Int {
        var node = ROOT
        for (char in alpha) {
            if (char !in 'a'..'z') return NONE
            node = child(node, char - 'a')
            if (node == NONE) return NONE
        }
        return node
    }

    fun terminalCount(node: Int): Int {
        if (node == NONE) return 0
        var count = 0
        forEachTerminal(node) { count++ }
        return count
    }

    fun childLetters(node: Int): String = buildString {
        var child = firstChild[node]
        while (child != NONE) {
            append('a' + labels[child].toInt())
            child = nextSibling[child]
        }
    }

    fun forEachTerminal(node: Int, block: (Int) -> Unit) {
        var terminal = terminalHeads[node]
        while (terminal != NONE) {
            block(terminalWords[terminal])
            terminal = terminalNext[terminal]
        }
    }

    private fun insert(wordIndex: Int) {
        var node = ROOT
        var depth = 0
        for (position in 0 until lexicon.lengthAt(wordIndex)) {
            val char = lexicon.charAt(wordIndex, position)
            if (char == '\'') continue
            if (char !in 'a'..'z') return
            depth++
            if (depth > MAX_EMITTED_LENGTH) return
            node = child(node, char - 'a').takeIf { it != NONE } ?: addChild(node, char - 'a')
        }
        if (depth < MIN_WORD_LENGTH) return

        terminalWords[terminalCount] = wordIndex
        terminalNext[terminalCount] = terminalHeads[node]
        terminalHeads[node] = terminalCount
        terminalCount++
    }

    private fun addChild(parent: Int, letter: Int): Int {
        ensureCapacity(nodeCount + 1)
        val node = nodeCount++
        labels[node] = letter.toByte()
        depths[node] = (depth(parent) + 1).toByte()
        nextSibling[node] = firstChild[parent]
        firstChild[parent] = node
        return node
    }

    private fun ensureCapacity(required: Int) {
        if (required <= firstChild.size) return
        val oldSize = firstChild.size
        val newSize = maxOf(required, oldSize * 2)
        firstChild = firstChild.copyOf(newSize).also { it.fill(NONE, oldSize) }
        nextSibling = nextSibling.copyOf(newSize).also { it.fill(NONE, oldSize) }
        labels = labels.copyOf(newSize).also { it.fill(ROOT_LABEL, oldSize) }
        depths = depths.copyOf(newSize)
        terminalHeads = terminalHeads.copyOf(newSize).also { it.fill(NONE, oldSize) }
    }

    private companion object {
        const val ROOT = 0
        const val NONE = -1
        const val ROOT_LABEL: Byte = -1
        const val MIN_WORD_LENGTH = 2
        const val MAX_EMITTED_LENGTH = 32
        const val INITIAL_NODES = 1 shl 18
    }
}
