package eu.kanade.translation.recognizer

/**
 * Returns this OCR text with the misreadings typical of comic lettering fixed: its serifed "I" is often read as "1",
 * "/" or "|", as in "1S" or "WHAT /S THIS".
 */
fun String.fixLetteringMistakes(): String = replace(I_BEFORE_CAPITAL, "I").replace(I_BETWEEN_CAPITALS, "I")

// At the start of a word before a capital, or inside a word in capitals. Digits elsewhere, as in "233", stay
private val I_BEFORE_CAPITAL = Regex("""(?<![\p{L}\d])[1|/](?=\p{Lu})""")
private val I_BETWEEN_CAPITALS = Regex("""(?<=\p{Lu})[1|](?=\p{Lu})""")
