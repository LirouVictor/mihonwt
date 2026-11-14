// Adapted from https://github.com/mannu691/TachiyomiAT
// Copyright mannu691 and TachiyomiAT contributors, Apache License 2.0

package eu.kanade.translation.model

import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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

    /** Changes the progress atomically, as OCR and translation report it at the same time. */
    fun updateProgress(transform: (Progress) -> Progress) {
        _progressFlow.update { transform(it ?: Progress(0, 0)) }
    }

    /** Why the last attempt failed, shown when the chapter's status is [State.ERROR]. */
    var error: String? = null

    var status: State
        get() = _statusFlow.value
        set(status) {
            _statusFlow.value = status
        }

    /**
     * OCR has read [page] of [pages], and the translator, which starts on the first pages while OCR reads the rest,
     * has done [translated] of the [toTranslate] pages with text found so far and is retrying for the [retries] time.
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
