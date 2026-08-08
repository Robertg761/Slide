package com.slide.core.layout

/**
 * Declarative keyboard layout model.
 *
 * Layouts are pure data so that adding a language or an alternate arrangement (QWERTZ, AZERTY,
 * Dvorak) is a data change rather than a code change. The model is deliberately serialization
 * shaped — loading these from JSON assets later requires no changes to consumers.
 */
data class KeyboardLayout(
    val id: String,
    val label: String,
    /** BCP-47 tag this layout is intended for, e.g. "en". */
    val languageTag: String,
    val rows: List<KeyRow>,
) {
    /** Total width weight of the widest row; used to normalise key widths. */
    val widthUnits: Float = rows.maxOfOrNull { it.totalWeight }?.takeIf { it > 0f } ?: 10f
}

data class KeyRow(
    val keys: List<Key>,
    /** Empty space before the first key, in width units. */
    val leadingGap: Float = 0f,
    /** Empty space after the last key, in width units. */
    val trailingGap: Float = 0f,
    /** Relative row height; 1.0 is a standard row. */
    val heightWeight: Float = 1f,
) {
    val totalWeight: Float = leadingGap + trailingGap + keys.sumOf { it.widthWeight.toDouble() }.toFloat()
}

data class Key(
    /** Text drawn on the key. */
    val label: String,
    val type: KeyType = KeyType.CHARACTER,
    /** Text committed when pressed. Defaults to [label] for character keys. */
    val output: String? = null,
    val widthWeight: Float = 1f,
    /** Characters offered in the long-press popup, in display order. */
    val alternates: List<String> = emptyList(),
    /** Small secondary label drawn in the key's top corner (e.g. the digit on the top row). */
    val hint: String? = null,
    /** Whether holding the key repeats its action (backspace, arrows). */
    val repeatable: Boolean = false,
    /**
     * Whether a swipe path travelling over this key contributes to gesture decoding.
     * Only letter keys participate; modifiers and space must not pollute the path.
     */
    val gestureEligible: Boolean = type == KeyType.CHARACTER,
) {
    val outputText: String get() = output ?: label
}

enum class KeyType {
    CHARACTER,
    SHIFT,
    DELETE,
    SPACE,
    ENTER,
    /** Switch to the symbols layer. */
    SYMBOLS,
    /** Switch back to the letters layer. */
    ALPHA,
    EMOJI,
    MIC,
    SETTINGS,
    /** Cycle languages / open IME picker on long press. */
    GLOBE,
}
