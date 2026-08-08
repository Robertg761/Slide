package com.slide.core.layout

/**
 * Built-in layouts for English. Additional languages and arrangements are added here as data.
 */
object Layouts {

    private fun letter(
        label: String,
        alternates: String = "",
        hint: String? = null,
    ) = Key(
        label = label,
        type = KeyType.CHARACTER,
        alternates = alternates.map(Char::toString),
        hint = hint,
    )

    private val shift = Key("⇧", KeyType.SHIFT, widthWeight = 1.5f, gestureEligible = false)
    private val delete = Key("⌫", KeyType.DELETE, widthWeight = 1.5f, repeatable = true, gestureEligible = false)
    private val toSymbols = Key("?123", KeyType.SYMBOLS, widthWeight = 1.5f, gestureEligible = false)
    private val toAlpha = Key("ABC", KeyType.ALPHA, widthWeight = 1.5f, gestureEligible = false)
    private val enter = Key("⏎", KeyType.ENTER, widthWeight = 1.5f, gestureEligible = false)
    private val space = Key(" ", KeyType.SPACE, output = " ", widthWeight = 5f, gestureEligible = false)
    /**
     * Text presentation is forced on the smiley so it draws in the key's own colour like ⇧ and ⌫,
     * rather than as a yellow emoji sitting among monochrome glyphs.
     */
    private val emoji = Key("\u263A\uFE0E", KeyType.EMOJI, gestureEligible = false)
    private val period = Key(
        ".",
        KeyType.CHARACTER,
        // The comma leads the alternates because the emoji key took its place in the bottom row,
        // which is what Gboard does too. It is the one alternate here that is not punctuation
        // people reach for occasionally.
        alternates = listOf(",", "!", "?", "…", "-", "_", "/", ";", ":"),
        gestureEligible = false,
    )

    /** Optional persistent digit row (setting A8). */
    val numberRow = KeyRow(
        listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0").map {
            Key(it, KeyType.CHARACTER, gestureEligible = false)
        },
        heightWeight = 0.8f,
    )

    val QwertyEn = KeyboardLayout(
        id = "qwerty_en",
        label = "QWERTY",
        languageTag = "en",
        rows = listOf(
            KeyRow(
                listOf(
                    letter("q", "1", hint = "1"),
                    letter("w", "2", hint = "2"),
                    letter("e", "3èéêëēėę", hint = "3"),
                    letter("r", "4", hint = "4"),
                    letter("t", "5", hint = "5"),
                    letter("y", "6ÿ", hint = "6"),
                    letter("u", "7ûüùúū", hint = "7"),
                    letter("i", "8îïíīįì", hint = "8"),
                    letter("o", "9ôöòóœøōõ", hint = "9"),
                    letter("p", "0", hint = "0"),
                ),
            ),
            KeyRow(
                listOf(
                    letter("a", "àáâäæãåā"),
                    letter("s", "ßśš"),
                    letter("d"),
                    letter("f"),
                    letter("g"),
                    letter("h"),
                    letter("j"),
                    letter("k"),
                    letter("l", "ł"),
                ),
                leadingGap = 0.5f,
                trailingGap = 0.5f,
            ),
            KeyRow(
                listOf(
                    shift,
                    letter("z", "žźż"),
                    letter("x"),
                    letter("c", "çćč"),
                    letter("v"),
                    letter("b"),
                    letter("n", "ñń"),
                    letter("m"),
                    delete,
                ),
            ),
            KeyRow(listOf(toSymbols, emoji, space, period, enter)),
        ),
    )

    /** First symbols page (`?123`). */
    val SymbolsEn = KeyboardLayout(
        id = "symbols_en",
        label = "Symbols",
        languageTag = "en",
        rows = listOf(
            KeyRow("1234567890".map { sym(it.toString()) }),
            KeyRow(listOf("@", "#", "$", "%", "&", "-", "+", "(", ")").map { sym(it) }, leadingGap = 0.5f, trailingGap = 0.5f),
            KeyRow(
                listOf(Key("=\\<", KeyType.SYMBOLS, widthWeight = 1.5f, gestureEligible = false)) +
                    listOf("*", "\"", "'", ":", ";", "!", "?").map { sym(it) } +
                    listOf(delete),
            ),
            KeyRow(listOf(toAlpha, emoji, space, period, enter)),
        ),
    )

    private fun sym(s: String) = Key(s, KeyType.CHARACTER, gestureEligible = false)

    /** Inserts the digit row at the top when the setting is enabled. */
    fun withNumberRow(layout: KeyboardLayout, enabled: Boolean): KeyboardLayout =
        if (!enabled) layout else layout.copy(rows = listOf(numberRow) + layout.rows)
}
