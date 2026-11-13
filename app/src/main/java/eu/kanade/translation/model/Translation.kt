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

    var status: State
        get() = _statusFlow.value
        set(status) {
            _statusFlow.value = status
        }

    enum class State(val value: Int) {
        NOT_TRANSLATED(0),
        QUEUE(1),
        TRANSLATING(2),
        TRANSLATED(3),
        ERROR(4),
    }
}
