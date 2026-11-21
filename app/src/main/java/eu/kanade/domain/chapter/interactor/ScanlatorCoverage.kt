package eu.kanade.domain.chapter.interactor

import tachiyomi.domain.chapter.model.Chapter

data class ScanlatorCoverage(
    val chapterNumbers: Set<Double>,
    val numbersByScanlator: Map<String, Set<Double>>,
    val hasUnidentifiedScanlator: Boolean,
) {
    val counts: Map<String, Int> = numbersByScanlator.mapValues { it.value.size }
    val numberedChapters: Int get() = chapterNumbers.size

    private val mainNumbers = chapterNumbers.filterTo(HashSet(), Double::isMainChapter)

    fun missingMainCount(scanlator: String): Int =
        mainNumbers.size - numbersByScanlator.getValue(scanlator).count { it in mainNumbers }

    fun missingFrom(scanlator: String): Set<Double> = chapterNumbers - numbersByScanlator.getValue(scanlator)

    fun containsChapter(number: Int): Boolean = number.toDouble() in chapterNumbers
}

internal fun Double.isMainChapter(): Boolean = this % 1.0 == 0.0

internal fun List<Double>.consecutiveChapterRanges(): List<Pair<Double, Double>> {
    if (isEmpty()) return emptyList()
    val ranges = mutableListOf<Pair<Double, Double>>()
    var first = first()
    var last = first
    for (number in drop(1)) {
        if (number != last + 1) {
            ranges += first to last
            first = number
        }
        last = number
    }
    ranges += first to last
    return ranges
}

internal fun List<Chapter>.scanlatorCoverage(): ScanlatorCoverage {
    val numbers = HashSet<Double>()
    val byScanlator = mutableMapOf<String, MutableSet<Double>>()
    var hasUnidentifiedScanlator = false
    for (chapter in this) {
        val scanlator = chapter.scanlator?.takeIf { it.isNotBlank() }
        val scanlatorNumbers = if (scanlator == null) {
            hasUnidentifiedScanlator = true
            null
        } else {
            byScanlator.getOrPut(scanlator) { HashSet() }
        }
        if (chapter.isRecognizedNumber && chapter.chapterNumber.isFinite()) {
            numbers.add(chapter.chapterNumber)
            scanlatorNumbers?.add(chapter.chapterNumber)
        }
    }
    return ScanlatorCoverage(numbers, byScanlator, hasUnidentifiedScanlator)
}
