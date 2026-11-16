package eu.kanade.translation.data

/**
 * Returns the glossary to start a series with: the names of its [characters], kept as they are, each also by its
 * parts, as "Monkey D. Luffy" is mostly called "Luffy", and the [terms] a language model knows it by, with their
 * translations. A single word that [isWord] says is English is left out, as a character called "Brook" would otherwise
 * keep the word untranslated wherever it's used.
 */
fun startingGlossary(
    characters: List<String>,
    terms: Map<String, String>,
    isWord: (String) -> Boolean,
): Map<String, String> {
    fun isKept(term: String) = isGlossaryTerm(term, isWord)
    val names = characters.flatMap { name ->
        val parts = name.split(' ').map { it.trim('.', ',', '"', '\'', '(', ')') }.filter {
            it.length >=
                MIN_PART_LETTERS
        }
        listOf(name) + parts.takeIf { it.size > 1 }.orEmpty()
    }
    return names.filter(::isKept).associateWith { it } + terms.filterKeys(::isKept)
}

/**
 * Whether [term] is worth keeping in a glossary: a single English word isn't, as "Bat" kept as "Morcego" would be
 * written so, capitalized, wherever the word is used.
 */
fun isGlossaryTerm(term: String, isWord: (String) -> Boolean): Boolean =
    term.any(Char::isLetter) && (' ' in term || !isWord(term))

private const val MIN_PART_LETTERS = 3
