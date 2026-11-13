// Adapted from https://github.com/mannu691/TachiyomiAT
// Copyright mannu691 and TachiyomiAT contributors, Apache License 2.0

package eu.kanade.translation.model

import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga

/** A chapter in the translation queue. */
data class Translation(
    val source: HttpSource,
    val manga: Manga,
    val chapter: Chapter,
) {
    private val _statusFlow = MutableStateFlow(State.NOT_TRANSLATED)
    val statusFlow = _statusFlow.asStateFlow()

    private val _progressFlow = MutableStateFlow<Progress?>(null)
    val progressFlow = _progressFlow.asStateFlow()

    var progress: Progress?
        get() = _progressFlow.value
        set(progress) {
            _progressFlow.value = progress
        }

    /** Why the last attempt failed, shown when the chapter's status is [State.ERROR]. */
    var error: String? = null

    var status: State
        get() = _statusFlow.value
        set(status) {
            _statusFlow.value = status
        }

    /**
     * OCR has read [page] of [pages]; once they're equal the translator has done [translated] of the [toTranslate]
     * pages that have text, and is retrying for the [retries] time.
     */
    data class Progress(
        val page: Int,
        val pages: Int,
        val translated: Int = 0,
        val toTranslate: Int = 0,
        val retries: Int = 0,
    )

    enum class State(val value: Int) {
        NOT_TRANSLATED(0),
        QUEUE(1),
        TRANSLATING(2),
        TRANSLATED(3),
        ERROR(4),
    }
}
