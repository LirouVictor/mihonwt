package eu.kanade.translation.recognizer

/**
 * Returns this OCR text with the misreadings typical of comic lettering fixed: its serifed "I" is often read as "1",
 * "/" or "|", as in "1S" or "WHAT /S THIS", its "T" as "7", as in "7O", and a word after an ellipsis can come out
 * as digits, as in "...50 I'M" or "...15 YOU".
 */
fun String.fixLetteringMistakes(): String = replace(I_BEFORE_CAPITAL, "I")
    .replace(I_BETWEEN_CAPITALS, "I")
    .replace(T_AS_SEVEN, "T")
    .replace(DIGITS_AFTER_ELLIPSIS) { DIGIT_WORDS.getValue(it.value) }

// At the start of a word before a capital, or inside a word in capitals. Digits elsewhere, as in "233", stay
private val I_BEFORE_CAPITAL = Regex("""(?<![\p{L}\d])[1|/](?=\p{Lu})""")
private val I_BETWEEN_CAPITALS = Regex("""(?<=\p{Lu})[1|](?=\p{Lu})""")

// Only before "O" or "H", as in "7O" or "7HE": "7PM" and "7TH" are numbers
private val T_AS_SEVEN = Regex("""(?<![\p{L}\d])7(?=[OH]\p{Lu}*(?![\p{L}\d]))""")

// Lettering in capitals seldom puts a number right after an ellipsis, so only these two common words are restored
private val DIGIT_WORDS = mapOf("15" to "IS", "50" to "SO")
private val DIGITS_AFTER_ELLIPSIS = Regex("""(?<=\.\.\.|…)(?:15|50)(?=\s\p{Lu})""")
