package com.slide.ime.text

/** Applies the capitalization pattern a person typed to a dictionary candidate. */
internal fun matchTypedCase(typed: String, candidate: String): String {
    val letters = typed.filter(Char::isLetter)
    return when {
        letters.length > 1 && letters.all(Char::isUpperCase) -> candidate.uppercase()
        typed.firstOrNull()?.isUpperCase() == true -> candidate.replaceFirstChar(Char::uppercaseChar)
        else -> candidate
    }
}
