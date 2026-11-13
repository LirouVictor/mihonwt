// Adapted from https://github.com/mannu691/TachiyomiAT
// Copyright mannu691 and TachiyomiAT contributors, Apache License 2.0

package eu.kanade.translation

import android.content.Context
import android.graphics.Bitmap
import android.widget.Toast
import ca.mpreg.imagedecoder.ImageDecoder
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.hippo.unifile.UniFile
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalOrder
import eu.kanade.tachiyomi.util.system.toast
import eu.kanade.translation.data.TranslationProvider
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.Translation
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.recognizer.TextRecognizer
import eu.kanade.translation.translator.GeminiTranslator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import logcat.LogPriority
import mihon.core.archive.archiveReader
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.translation.TranslationPreferences
import tachiyomi.i18n.wt.WTMR
import java.io.IOException
import java.io.InputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Queue that runs OCR and translation on downloaded chapters and saves the result next to the downloads. */
@Inject
@SingleIn(AppScope::class)
class ChapterTranslator(
    private val context: Context,
    private val provider: TranslationProvider,
    private val downloadProvider: DownloadProvider,
    private val sourceManager: SourceManager,
    private val translationPreferences: TranslationPreferences,
) {

    private val _queueState = MutableStateFlow<List<Translation>>(emptyList())
    val queueState = _queueState.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var translationJob: Job? = null

    val isRunning: Boolean
        get() = translationJob?.isActive == true

    private val textRecognizer by lazy { TextRecognizer() }

    fun start(): Boolean {
        if (isRunning || queueState.value.isEmpty()) {
            return false
        }

        val pending = queueState.value.filter { it.status != Translation.State.TRANSLATED }
        pending.forEach { if (it.status != Translation.State.QUEUE) it.status = Translation.State.QUEUE }
        launchTranslatorJob()
        return pending.isNotEmpty()
    }

    fun stop() {
        cancelTranslatorJob()
        queueState.value.filter { it.status == Translation.State.TRANSLATING }
            .forEach { it.status = Translation.State.ERROR }
    }

    fun pause() {
        cancelTranslatorJob()
        queueState.value.filter { it.status == Translation.State.TRANSLATING }
            .forEach { it.status = Translation.State.QUEUE }
    }

    private fun launchTranslatorJob() {
        if (isRunning) return

        translationJob = scope.launch {
            // One chapter at a time per source, up to five sources; a failed chapter frees its slot
            val activeTranslationFlow = queueState.transformLatest { queue ->
                while (true) {
                    val activeTranslations = queue.asSequence()
                        .filter { it.status.value <= Translation.State.TRANSLATING.value }
                        .groupBy { it.source }
                        .toList()
                        .take(5)
                        .map { (_, translations) -> translations.first() }
                    emit(activeTranslations)

                    if (activeTranslations.isEmpty()) break
                    combine(activeTranslations.map(Translation::statusFlow)) { states ->
                        states.contains(Translation.State.ERROR)
                    }
                        .filter { it }
                        .first()
                }
            }.distinctUntilChanged()

            supervisorScope {
                val translationJobs = mutableMapOf<Translation, Job>()

                activeTranslationFlow.collectLatest { activeTranslations ->
                    translationJobs.filter { it.key !in activeTranslations }.forEach { (translation, job) ->
                        job.cancel()
                        translationJobs.remove(translation)
                    }

                    activeTranslations.filter { it !in translationJobs }.forEach { translation ->
                        translationJobs[translation] = launchTranslationJob(translation)
                    }
                }
            }
        }
    }

    private fun CoroutineScope.launchTranslationJob(translation: Translation) = launch {
        try {
            translateChapter(translation)
            if (translation.status == Translation.State.TRANSLATED) {
                removeFromQueue(translation)
            }
            if (areAllTranslationsFinished()) {
                stop()
            }
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            logcat(LogPriority.ERROR, e)
            stop()
        }
    }

    private fun cancelTranslatorJob() {
        translationJob?.cancel()
        translationJob = null
    }

    suspend fun queueChapter(manga: Manga, chapter: Chapter) {
        val source = sourceManager.get(manga.source) as? HttpSource ?: return
        if (provider.findTranslationFile(chapter.name, chapter.scanlator, manga.title, source) != null) return
        val queued = queueState.value.find { it.chapter.id == chapter.id }
        if (queued != null) {
            if (queued.status != Translation.State.ERROR) return
            // Queued again rather than reset, so a running queue notices it
            removeFromQueue(queued)
        }
        addToQueue(Translation(source, manga, chapter))
    }

    private suspend fun translateChapter(translation: Translation) {
        try {
            translation.error = null
            translation.status = Translation.State.TRANSLATING
            val apiKey = translationPreferences.geminiApiKey.get()
            if (apiKey.isBlank()) throw IOException(context.stringResource(WTMR.strings.translation_error_no_api_key))
            val translator = GeminiTranslator(apiKey = apiKey, model = translationPreferences.geminiModel.get())
            val mangaDir = provider.getMangaDir(translation.manga.title, translation.source)
            val chapterDir = downloadProvider.findChapterDir(
                translation.chapter.name,
                translation.chapter.scanlator,
                translation.chapter.url,
                translation.manga.title,
                translation.source,
            ) ?: throw IOException(context.stringResource(WTMR.strings.translation_error_not_downloaded))

            val pages = recognizeChapter(chapterDir)
            translator.translate(pages)

            mangaDir.createFile(
                provider.getTranslationFileName(translation.chapter.name, translation.chapter.scanlator),
            )!!.openOutputStream().use { it.write(Json.encodeToString(pages).toByteArray()) }
            translation.status = Translation.State.TRANSLATED
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            logcat(LogPriority.ERROR, e)
            val error = e.message ?: e.toString()
            translation.error = error
            translation.status = Translation.State.ERROR
            val message = context.stringResource(WTMR.strings.translation_failed, translation.chapter.name, error)
            withContext(Dispatchers.Main) { context.toast(message, Toast.LENGTH_LONG) }
        }
    }

    /** Returns the text blocks of each page that has any, keyed by the page's file name. */
    private suspend fun recognizeChapter(chapterDir: UniFile): Map<String, PageTranslation> {
        val pages = mutableMapOf<String, PageTranslation>()
        withChapterPages(chapterDir) { fileName, openStream ->
            currentCoroutineContext().ensureActive()
            val bitmap = decodePage(openStream)
            try {
                val blocks = textRecognizer.recognize(InputImage.fromBitmap(bitmap, 0)).textBlocks
                    .filter { it.boundingBox != null && it.text.length > 1 }
                val page = toPageTranslation(blocks, bitmap.width, bitmap.height)
                if (page.blocks.isNotEmpty()) pages[fileName] = page
            } finally {
                bitmap.recycle()
            }
        }
        return pages
    }

    // ML Kit decodes with Android's own decoders, which return nothing for formats like AVIF or JPEG XL on many
    // devices, so pages go through the reader's decoder instead
    private fun decodePage(openStream: () -> InputStream): Bitmap {
        val bitmap = openStream().use { input -> ImageDecoder.open(input).use { it.decodeToBitmap(null) } }
        if (bitmap.config == Bitmap.Config.ARGB_8888) return bitmap
        return bitmap.copy(Bitmap.Config.ARGB_8888, false).also { bitmap.recycle() }
    }

    private suspend fun withChapterPages(
        chapterDir: UniFile,
        block: suspend (fileName: String, openStream: () -> InputStream) -> Unit,
    ) {
        if (chapterDir.isFile) {
            chapterDir.archiveReader(context).use { reader ->
                val names = reader.useEntries { entries ->
                    entries
                        .filter { it.isFile && ImageUtil.isImage(it.name) { reader.getInputStream(it.name)!! } }
                        .map { it.name }
                        .sortedWith { n1, n2 -> n1.compareToCaseInsensitiveNaturalOrder(n2) }
                        .toList()
                }
                names.forEach { name -> block(name) { reader.getInputStream(name)!! } }
            }
        } else {
            chapterDir.listFiles().orEmpty()
                .filter { ImageUtil.isImage(it.name) }
                .sortedWith { f1, f2 -> f1.name.orEmpty().compareToCaseInsensitiveNaturalOrder(f2.name.orEmpty()) }
                .forEach { file -> block(file.name!!) { file.openInputStream() } }
        }
    }

    private fun toPageTranslation(blocks: List<Text.TextBlock>, width: Int, height: Int): PageTranslation {
        val page = PageTranslation(imgWidth = width.toFloat(), imgHeight = height.toFloat())
        for (block in blocks) {
            val bounds = block.boundingBox!!
            val element = block.lines.first().elements.first()
            val symBounds = element.symbols.firstOrNull()?.boundingBox ?: element.boundingBox ?: bounds
            page.blocks.add(
                TranslationBlock(
                    text = block.text,
                    width = bounds.width().toFloat(),
                    height = bounds.height().toFloat(),
                    symWidth = symBounds.width().toFloat(),
                    symHeight = symBounds.height().toFloat(),
                    angle = block.lines.first().angle,
                    x = bounds.left.toFloat(),
                    y = bounds.top.toFloat(),
                ),
            )
        }
        page.blocks = smartMergeBlocks(page.blocks, widthThreshold = 50, xThreshold = 30, yThreshold = 30)
        return page
    }

    /** Joins consecutive blocks that look like lines of the same speech bubble. */
    private fun smartMergeBlocks(
        blocks: List<TranslationBlock>,
        widthThreshold: Int,
        xThreshold: Int,
        yThreshold: Int,
    ): MutableList<TranslationBlock> {
        if (blocks.isEmpty()) return mutableListOf()

        val merged = mutableListOf<TranslationBlock>()
        var current = blocks[0]
        for (i in 1 until blocks.size) {
            val next = blocks[i]
            if (shouldMergeTextBlock(current, next, widthThreshold, xThreshold, yThreshold)) {
                current = mergeTextBlock(current, next)
            } else {
                merged.add(current)
                current = next
            }
        }
        merged.add(current)
        return merged
    }

    private fun shouldMergeTextBlock(
        a: TranslationBlock,
        b: TranslationBlock,
        widthThreshold: Int,
        xThreshold: Int,
        yThreshold: Int,
    ): Boolean {
        val isWidthSimilar = (b.width < a.width) || (abs(a.width - b.width) < widthThreshold)
        val isXClose = abs(a.x - b.x) < xThreshold
        val isYClose = (b.y - (a.y + a.height)) < yThreshold
        return isWidthSimilar && isXClose && isYClose
    }

    private fun mergeTextBlock(a: TranslationBlock, b: TranslationBlock): TranslationBlock {
        val newX = min(a.x, b.x)
        val newY = a.y
        return TranslationBlock(
            text = a.text + " " + b.text,
            translation = a.translation + " " + b.translation,
            width = max(a.x + a.width, b.x + b.width) - newX,
            height = max(a.y + a.height, b.y + b.height) - newY,
            x = newX,
            y = newY,
            symHeight = a.symHeight,
            symWidth = a.symWidth,
            angle = a.angle,
        )
    }

    private fun areAllTranslationsFinished(): Boolean {
        return queueState.value.none { it.status.value <= Translation.State.TRANSLATING.value }
    }

    private fun addToQueue(translation: Translation) {
        translation.status = Translation.State.QUEUE
        _queueState.update { it + translation }
    }

    private fun removeFromQueue(translation: Translation) {
        removeFromQueueIf { it == translation }
    }

    fun removeFromQueue(chapter: Chapter) {
        removeFromQueueIf { it.chapter.id == chapter.id }
    }

    private inline fun removeFromQueueIf(predicate: (Translation) -> Boolean) {
        _queueState.update { queue ->
            val translations = queue.filter(predicate)
            translations.forEach { translation ->
                if (translation.status == Translation.State.TRANSLATING ||
                    translation.status == Translation.State.QUEUE
                ) {
                    translation.status = Translation.State.NOT_TRANSLATED
                }
            }
            queue - translations.toSet()
        }
    }
}
