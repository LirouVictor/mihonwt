// Adapted from https://github.com/mannu691/TachiyomiAT
// Copyright mannu691 and TachiyomiAT contributors, Apache License 2.0

package eu.kanade.translation

import com.hippo.unifile.UniFile
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.source.Source
import eu.kanade.translation.data.TranslationProvider
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.Translation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import logcat.LogPriority
import mihon.core.metro.AppCoroutineScope
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager

/** Entry point for the rest of the app to queue chapter translations and read saved ones. */
@Inject
@SingleIn(AppScope::class)
class TranslationManager(
    @AppCoroutineScope private val scope: CoroutineScope,
    private val translator: ChapterTranslator,
    private val provider: TranslationProvider,
    private val sourceManager: SourceManager,
) {

    val queueState: StateFlow<List<Translation>>
        get() = translator.queueState

    private val deletions = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Emits whenever the saved translations may have changed: a queued one finished, or one was deleted. */
    val changes: Flow<Unit> = merge(queueState.map {}, deletions)

    /** The status of each queued chapter, by chapter id. */
    val queuedStates: Flow<Map<Long, Translation.State>> = queueState.flatMapLatest { queue ->
        if (queue.isEmpty()) {
            flowOf(emptyMap())
        } else {
            combine(queue.map { translation -> translation.statusFlow.map { translation.chapter.id to it } }) {
                it.toMap()
            }
        }
    }

    /** Returns the ids of the [chapters] that have a saved translation. */
    fun getTranslatedChapterIds(chapters: List<Chapter>, manga: Manga, source: Source): Set<Long> {
        val fileNames = provider.getTranslationFileNames(manga.title, source)
        if (fileNames.isEmpty()) return emptySet()
        return chapters
            .filter { provider.getTranslationFileName(it.name, it.scanlator) in fileNames }
            .mapTo(HashSet()) { it.id }
    }

    fun translateChapter(manga: Manga, chapter: Chapter) {
        scope.launch {
            translator.queueChapter(manga, chapter)
            if (!translator.isRunning) translator.start()
        }
    }

    fun getQueuedTranslationOrNull(chapterId: Long): Translation? {
        return queueState.value.find { it.chapter.id == chapterId }
    }

    suspend fun getChapterTranslationStatus(
        chapterId: Long,
        chapterName: String,
        scanlator: String?,
        title: String,
        sourceId: Long,
    ): Translation.State {
        getQueuedTranslationOrNull(chapterId)?.let { return it.status }
        if (isChapterTranslated(chapterName, scanlator, title, sourceId)) return Translation.State.TRANSLATED
        return Translation.State.NOT_TRANSLATED
    }

    suspend fun isChapterTranslated(
        chapterName: String,
        chapterScanlator: String?,
        mangaTitle: String,
        sourceId: Long,
    ): Boolean {
        val source = sourceManager.get(sourceId) ?: return false
        return provider.findTranslationFile(chapterName, chapterScanlator, mangaTitle, source)?.exists() == true
    }

    /** Returns the saved translation of each page, keyed by the page's file name. */
    fun getChapterTranslation(
        chapterName: String,
        scanlator: String?,
        title: String,
        source: Source,
    ): Map<String, PageTranslation> {
        val file = provider.findTranslationFile(chapterName, scanlator, title, source) ?: return emptyMap()
        return getChapterTranslation(file)
    }

    /** Reads a translation file, deleting it if it can't be parsed so the chapter can be translated again. */
    fun getChapterTranslation(file: UniFile): Map<String, PageTranslation> {
        return try {
            file.openInputStream().use { Json.decodeFromString(it.reader().readText()) }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Invalid translation file ${file.name}" }
            file.delete()
            emptyMap()
        }
    }

    fun deleteTranslation(chapter: Chapter, manga: Manga, source: Source) {
        scope.launch {
            removeFromTranslationQueue(chapter)
            provider.findTranslationFile(chapter.name, chapter.scanlator, manga.title, source)?.delete()
            deletions.emit(Unit)
        }
    }

    fun cancelQueuedTranslation(translation: Translation) {
        removeFromTranslationQueue(translation.chapter)
    }

    private fun removeFromTranslationQueue(chapter: Chapter) {
        val wasRunning = translator.isRunning
        if (wasRunning) {
            translator.pause()
        }
        translator.removeFromQueue(chapter)
        if (wasRunning) {
            if (queueState.value.isEmpty()) {
                translator.stop()
            } else {
                translator.start()
            }
        }
    }

    /** Emits each queued translation whenever its status changes. */
    fun statusFlow(): Flow<Translation> = queueState
        .flatMapLatest { translations ->
            translations
                .map { translation -> translation.statusFlow.drop(1).map { translation } }
                .merge()
        }
        .onStart {
            emitAll(queueState.value.filter { it.status == Translation.State.TRANSLATING }.asFlow())
        }
}
