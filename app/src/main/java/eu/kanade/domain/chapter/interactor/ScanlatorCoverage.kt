package eu.kanade.domain.chapter.interactor

import tachiyomi.domain.chapter.model.Chapter

data class ScanlatorCoverage(
    val counts: Map<String, Int>,
    val numberedChapters: Int,
    val hasUnidentifiedScanlator: Boolean,
)

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
    return ScanlatorCoverage(byScanlator.mapValues { it.value.size }, numbers.size, hasUnidentifiedScanlator)
}
