package eu.kanade.translation.recognizer

/**
 * Returns this OCR text with the misreadings typical of comic lettering fixed: its serifed "I" is often read as "1",
 * "/" or "|", as in "1S" or "WHAT /S THIS", its "T" as "7", as in "7O", and a word after an ellipsis can come out
 * as digits, as in "...50 I'M" or "...15 YOU". A bubble's outline right after a hyphen at the end of a line is read as
 * "|", as in "REMEM-|", which would keep the word from being joined to its end on the next line. A slanted "!" at the
 * end of a word is read as "/", as in "FOLKS/", and a stroke of the outline as a brace, as in "FAVOR?{".
 */
fun String.fixLetteringMistakes(): String = replace(OUTLINE_AFTER_HYPHEN, "")
    .replace(BRACE, "")
    .replace(EXCLAMATION_AS_SLASH, "!")
    .replace(I_BEFORE_CAPITAL, "I")
    .replace(I_BETWEEN_CAPITALS, "I")
    .replace(T_AS_SEVEN, "T")
    .replace(DIGITS_AFTER_ELLIPSIS) { DIGIT_WORDS.getValue(it.value) }

private val OUTLINE_AFTER_HYPHEN = Regex("""(?<=\p{L}-)\|(?=\s|$)""")
private val BRACE = Regex("[{}]")

// Only after a capital, at the end of a word: "AND/OR" stays
private val EXCLAMATION_AS_SLASH = Regex("""(?<=\p{Lu})/(?![\p{L}\d])""")

// At the start of a word before a capital, or inside a word in capitals. Digits elsewhere, as in "233", stay
private val I_BEFORE_CAPITAL = Regex("""(?<![\p{L}\d])[1|/](?=\p{Lu})""")
private val I_BETWEEN_CAPITALS = Regex("""(?<=\p{Lu})[1|](?=\p{Lu})""")

// Only before "O" or "H", as in "7O" or "7HE": "7PM" and "7TH" are numbers
private val T_AS_SEVEN = Regex("""(?<![\p{L}\d])7(?=[OH]\p{Lu}*(?![\p{L}\d]))""")

// Lettering in capitals seldom puts a number right after an ellipsis, so only these two common words are restored
private val DIGIT_WORDS = mapOf("15" to "IS", "50" to "SO")
private val DIGITS_AFTER_ELLIPSIS = Regex("""(?<=\.\.\.|…)(?:15|50)(?=\s\p{Lu})""")
