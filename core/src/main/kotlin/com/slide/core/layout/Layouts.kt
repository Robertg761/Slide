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
    private val toSymbolsAlt = Key("=\\<", KeyType.SYMBOLS_ALT, widthWeight = 1.5f, gestureEligible = false)
    private val toAlpha = Key("ABC", KeyType.ALPHA, widthWeight = 1.5f, gestureEligible = false)
    private val enter = Key("⏎", KeyType.ENTER, widthWeight = 1.5f, gestureEligible = false)
    private val space = Key(" ", KeyType.SPACE, output = " ", widthWeight = 5f, gestureEligible = false)
    /**
     * Text presentation is forced on the smiley so it draws in the key's own colour like ⇧ and ⌫,
     * rather than as a yellow emoji sitting among monochrome glyphs.
     */
    private val emoji = Key("\u263A\uFE0E", KeyType.EMOJI, gestureEligible = false)
    private val globe = Key("\u25ce", KeyType.GLOBE, gestureEligible = false)
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
        label = "English (US)",
        languageTag = "en",
        supportsNumberRow = true,
        rows = listOf(
            KeyRow(
                listOf(
                    letter("q", "1%", hint = "1"),
                    letter("w", "2\\", hint = "2"),
                    letter("e", "3èéêëēėę|", hint = "3"),
                    letter("r", "4=", hint = "4"),
                    letter("t", "5[", hint = "5"),
                    letter("y", "6ÿ]", hint = "6"),
                    letter("u", "7ûüùúū<", hint = "7"),
                    letter("i", "8îïíīįì>", hint = "8"),
                    letter("o", "9ôöòóœøōõ{", hint = "9"),
                    letter("p", "0}", hint = "0"),
                ),
            ),
            KeyRow(
                listOf(
                    letter("a", "@àáâäæãåā", hint = "@"),
                    letter("s", "#ßśš", hint = "#"),
                    letter("d", "$", hint = "$"),
                    letter("f", "-", hint = "-"),
                    letter("g", "&", hint = "&"),
                    letter("h", "_", hint = "_"),
                    letter("j", "+", hint = "+"),
                    letter("k", "(", hint = "("),
                    letter("l", ")ł", hint = ")"),
                ),
                leadingGap = 0.5f,
                trailingGap = 0.5f,
            ),
            KeyRow(
                listOf(
                    shift,
                    letter("z", "*žźż", hint = "*"),
                    letter("x", "\"", hint = "\""),
                    letter("c", "'çćč", hint = "'"),
                    letter("v", ":", hint = ":"),
                    letter("b", ";", hint = ";"),
                    letter("n", "!ñń", hint = "!"),
                    letter("m", "?", hint = "?"),
                    delete,
                ),
            ),
            KeyRow(listOf(toSymbols, emoji, space, period, enter)),
        ),
    )

    /** QWERTY with the characters people need most often in an email address. */
    val EmailEn = QwertyEn.copy(
        id = "email_en",
        label = "English (US)",
        rows = QwertyEn.rows.dropLast(1) + KeyRow(
            listOf(
                toSymbols,
                sym("@"),
                Key(" ", KeyType.SPACE, output = " ", widthWeight = 3.5f, gestureEligible = false),
                period,
                Key(".com", KeyType.CHARACTER, output = ".com", widthWeight = 1.5f, gestureEligible = false),
                enter,
            ),
        ),
    )

    /** QWERTY with URL delimiters on the primary layer. */
    val UriEn = QwertyEn.copy(
        id = "uri_en",
        label = "English (US)",
        rows = QwertyEn.rows.dropLast(1) + KeyRow(
            listOf(
                toSymbols,
                sym("/"),
                Key(" ", KeyType.SPACE, output = " ", widthWeight = 3.5f, gestureEligible = false),
                period,
                Key(".com", KeyType.CHARACTER, output = ".com", widthWeight = 1.5f, gestureEligible = false),
                enter,
            ),
        ),
    )

    private fun digit(value: String, alternates: List<String> = emptyList()) =
        Key(value, KeyType.CHARACTER, alternates = alternates, gestureEligible = false)

    private fun digitRows(rowGap: Float = 0.5f): List<KeyRow> = listOf(
        KeyRow(listOf(digit("1"), digit("2"), digit("3")), rowGap, rowGap),
        KeyRow(listOf(digit("4"), digit("5"), digit("6")), rowGap, rowGap),
        KeyRow(listOf(digit("7"), digit("8"), digit("9")), rowGap, rowGap),
    )

    /** Unsigned number/PIN pad. Zero is deliberately a full two-unit thumb target. */
    val NumberPad = KeyboardLayout(
        id = "number_pad",
        label = "Numbers",
        languageTag = "und",
        rows = digitRows() + KeyRow(
            listOf(
                digit("0").copy(widthWeight = 2f),
                delete.copy(widthWeight = 1f),
                enter.copy(widthWeight = 1f),
            ),
        ),
    )

    val SignedNumberPad = NumberPad.copy(
        id = "signed_number_pad",
        rows = digitRows() + KeyRow(
            listOf(sym("-"), digit("0"), delete.copy(widthWeight = 1f), enter.copy(widthWeight = 1f)),
        ),
    )

    val DecimalPad = NumberPad.copy(
        id = "decimal_pad",
        rows = digitRows() + KeyRow(
            listOf(sym("."), digit("0"), delete.copy(widthWeight = 1f), enter.copy(widthWeight = 1f)),
        ),
    )

    val SignedDecimalPad = NumberPad.copy(
        id = "signed_decimal_pad",
        rows = digitRows() + KeyRow(
            listOf(
                sym("-").copy(widthWeight = 0.8f),
                digit("0").copy(widthWeight = 0.8f),
                sym(".").copy(widthWeight = 0.8f),
                delete.copy(widthWeight = 0.8f),
                enter.copy(widthWeight = 0.8f),
            ),
        ),
    )

    /** Dial pad. Long-pressing zero exposes the international-prefix plus sign. */
    val PhonePad = KeyboardLayout(
        id = "phone_pad",
        label = "Phone",
        languageTag = "und",
        rows = digitRows() + KeyRow(
            listOf(
                sym("*").copy(widthWeight = 0.8f),
                digit("0", alternates = listOf("+")).copy(widthWeight = 0.8f),
                sym("#").copy(widthWeight = 0.8f),
                delete.copy(widthWeight = 0.8f),
                enter.copy(widthWeight = 0.8f),
            ),
        ),
    )

    /** Date fields need explicit separators because many editors do not insert them themselves. */
    val DatePad = NumberPad.copy(
        id = "date_pad",
        label = "Date",
        rows = digitRows() + KeyRow(
            listOf(
                sym("/").copy(widthWeight = 0.65f),
                sym("-").copy(widthWeight = 0.65f),
                digit("0").copy(widthWeight = 0.65f),
                sym(".").copy(widthWeight = 0.65f),
                delete.copy(widthWeight = 0.65f),
                enter.copy(widthWeight = 0.65f),
            ),
        ),
    )

    /** Time fields support 24-hour, fractional, and common 12-hour forms without another layer. */
    val TimePad = NumberPad.copy(
        id = "time_pad",
        label = "Time",
        rows = digitRows() + KeyRow(
            listOf(
                sym(":").copy(widthWeight = 0.58f),
                digit("0").copy(widthWeight = 0.58f),
                sym(".").copy(widthWeight = 0.58f),
                Key("AM", output = "AM", widthWeight = 0.58f, gestureEligible = false),
                Key("PM", output = "PM", widthWeight = 0.58f, gestureEligible = false),
                delete.copy(widthWeight = 0.58f),
                enter.copy(widthWeight = 0.58f),
            ),
        ),
    )

    /** Generic date-time editors get the union of date and time punctuation plus AM/PM. */
    val DateTimePad = NumberPad.copy(
        id = "datetime_pad",
        label = "Date and time",
        rows = digitRows() + listOf(
            KeyRow(
                listOf(sym("/"), sym("-"), digit("0"), sym(":"), sym("."))
                    .map { it.copy(widthWeight = 0.8f) },
            ),
            KeyRow(
                listOf(
                    Key("AM", output = "AM", widthWeight = 1f, gestureEligible = false),
                    Key("PM", output = "PM", widthWeight = 1f, gestureEligible = false),
                    delete.copy(widthWeight = 1f),
                    enter.copy(widthWeight = 1f),
                ),
                heightWeight = 0.8f,
            ),
        ),
    )

    /** First symbols page (`?123`). */
    val SymbolsEn = KeyboardLayout(
        id = "symbols_en",
        label = "Symbols",
        languageTag = "en",
        rows = listOf(
            KeyRow("1234567890".map { sym(it.toString()) }),
            KeyRow(
                listOf(
                    sym("@"), sym("#"), sym("$", "€£¥¢"), sym("%", "‰"), sym("&"),
                    sym("-", "_–—~"), sym("+", "±"), sym("(", "[{<"), sym(")", "]}>"),
                ),
                leadingGap = 0.5f,
                trailingGap = 0.5f,
            ),
            KeyRow(
                listOf(toSymbolsAlt) +
                    listOf(sym("*"), sym("\""), sym("'"), sym(":"), sym(";"), sym("!"), sym("?")) +
                    listOf(delete),
            ),
            KeyRow(listOf(toAlpha, emoji, space, period, enter)),
        ),
    )

    /**
     * Second symbols page (`=\<`), reached from the `=\<` key on [SymbolsEn].
     *
     * This is where the characters people go looking for and cannot find on the first page live:
     * the slash, the equals sign, the brackets and the currency symbols.
     */
    val SymbolsAltEn = KeyboardLayout(
        id = "symbols_alt_en",
        label = "Symbols",
        languageTag = "en",
        rows = listOf(
            KeyRow(
                listOf(
                    sym("~"), sym("`"), sym("|"), sym("•"), sym("√"),
                    sym("π"), sym("÷"), sym("×"), sym("¶"), sym("∆"),
                ),
            ),
            KeyRow(
                listOf(
                    sym("£"), sym("¢"), sym("€"), sym("¥"), sym("^"),
                    sym("°"), sym("="), sym("{"), sym("}"),
                ),
                leadingGap = 0.5f,
                trailingGap = 0.5f,
            ),
            KeyRow(
                listOf(toSymbols) +
                    listOf(sym("\\"), sym("/"), sym("<"), sym(">"), sym("["), sym("]"), sym("©", "®™")) +
                    listOf(delete),
            ),
            KeyRow(listOf(toAlpha, emoji, space, period, enter)),
        ),
    )

    private fun sym(s: String, alternates: String = "") =
        Key(s, KeyType.CHARACTER, alternates = alternates.map(Char::toString), gestureEligible = false)

    /** Inserts the digit row at the top when the setting is enabled. */
    fun withNumberRow(layout: KeyboardLayout, enabled: Boolean): KeyboardLayout =
        if (!enabled || !layout.supportsNumberRow || layout.rows.firstOrNull() == numberRow) {
            layout
        } else {
            layout.copy(rows = listOf(numberRow) + layout.rows)
        }

    /**
     * Adds the system-required next-IME affordance without permanently crowding every layout.
     *
     * Android only asks for the switch key when another enabled IME exists. The bottom row keeps
     * its original width: alphabetic layouts donate one unit from Space; compact specialised pads
     * share the new key's width proportionally across their existing keys.
     */
    fun withImeSwitcher(layout: KeyboardLayout, enabled: Boolean): KeyboardLayout {
        if (!enabled || layout.rows.any { row -> row.keys.any { it.type == KeyType.GLOBE } }) {
            return layout
        }
        val bottom = layout.rows.lastOrNull() ?: return layout
        if (bottom.keys.isEmpty()) return layout

        val globeWeight = minOf(1f, bottom.keys.sumOf { it.widthWeight.toDouble() }.toFloat() * 0.16f)
        val spaceIndex = bottom.keys.indexOfFirst { it.type == KeyType.SPACE && it.widthWeight >= globeWeight + 1f }
        val resized = if (spaceIndex >= 0) {
            bottom.keys.mapIndexed { index, key ->
                if (index == spaceIndex) key.copy(widthWeight = key.widthWeight - globeWeight) else key
            }
        } else {
            val existingWeight = bottom.keys.sumOf { it.widthWeight.toDouble() }.toFloat()
            val scale = ((existingWeight - globeWeight) / existingWeight).coerceAtLeast(0.5f)
            bottom.keys.map { key -> key.copy(widthWeight = key.widthWeight * scale) }
        }

        val insertion = resized.indexOfFirst { it.type == KeyType.SPACE }
            .takeIf { it >= 0 }
            ?: resized.indexOfLast { it.type != KeyType.ENTER }.plus(1)
        val keys = resized.toMutableList().apply { add(insertion, globe.copy(widthWeight = globeWeight)) }
        return layout.copy(rows = layout.rows.dropLast(1) + bottom.copy(keys = keys))
    }
}
