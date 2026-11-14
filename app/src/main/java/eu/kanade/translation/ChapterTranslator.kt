// Adapted from https://github.com/mannu691/TachiyomiAT
// Copyright mannu691 and TachiyomiAT contributors, Apache License 2.0

package eu.kanade.translation

import android.content.Context
import android.graphics.Bitmap
import android.widget.Toast
import androidx.core.graphics.scale
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
import eu.kanade.translation.data.ApiKeyCipher
import eu.kanade.translation.data.TranslationProvider
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.Translation
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.recognizer.TextRecognizer
import eu.kanade.translation.recognizer.grownIntoBubble
import eu.kanade.translation.recognizer.inBubble
import eu.kanade.translation.recognizer.lightPixels
import eu.kanade.translation.translator.PageTranslator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
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
import tachiyomi.domain.translation.TranslationService
import tachiyomi.i18n.wt.WTMR
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
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

    private val recognizedChapters = ConcurrentHashMap<Long, Map<String, PageTranslation>>()

    /** Small JPEG copies of the pages of [recognizedChapters], for translators that read them. */
    private val recognizedImages = ConcurrentHashMap<Long, MutableMap<String, ByteArray>>()

    private val _partialTranslations = MutableStateFlow<Map<Long, Map<String, PageTranslation>>>(emptyMap())

    /**
     * Pages translated so far of each chapter still in the queue, by chapter id, so the reader can show them before
     * the chapter's file is saved. A failed chapter keeps them, and a retry only translates the rest.
     */
    val partialTranslations = _partialTranslations.asStateFlow()

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
            translation.progress = null
            val translator = createTranslator()
            val mangaDir = provider.getMangaDir(translation.manga.title, translation.source)
            val chapterDir = downloadProvider.findChapterDir(
                translation.chapter.name,
                translation.chapter.scanlator,
                translation.chapter.url,
                translation.manga.title,
                translation.source,
            ) ?: throw IOException(context.stringResource(WTMR.strings.translation_error_not_downloaded))

            val chapterId = translation.chapter.id
            val done = partialTranslations.value[chapterId].orEmpty().toMutableMap()
            val images = recognizedImages.getOrPut(chapterId) { ConcurrentHashMap() }
            val glossary = provider.getGlossary(mangaDir).toMutableMap()
            val pages = coroutineScope {
                val recognized = Channel<Pair<String, PageTranslation>>(Channel.UNLIMITED)
                val ocr = async {
                    // A retry usually follows a translator error, so it reuses the OCR instead of reading every page
                    // again
                    val cached = recognizedChapters[chapterId]
                    if (cached != null) {
                        cached.forEach { recognized.send(it.toPair()) }
                        cached
                    } else {
                        val pageImages = images.takeIf { translator.readsImages }
                        recognizeChapter(chapterDir, translation, pageImages) { name, page ->
                            recognized.send(name to page)
                        }
                            .also { recognizedChapters[chapterId] = it }
                    }.also { recognized.close() }
                }
                // Batches go out as soon as OCR has read their pages, so the reader shows the first pages while the
                // rest are still being read. Small batches also keep each reply within the model's output limit
                val batch = mutableMapOf<String, PageTranslation>()
                suspend fun translateBatch() {
                    val terms = translator.translate(batch.toMap(), translation.manga.title, images, glossary.toMap()) {
                        translation.updateProgress { progress -> progress.copy(retries = it) }
                    }
                    terms.forEach { (term, translated) ->
                        if (glossary.size < MAX_GLOSSARY_TERMS) glossary.putIfAbsent(term, translated)
                    }
                    done.putAll(batch)
                    batch.clear()
                    translation.updateProgress { it.copy(translated = done.size, retries = 0) }
                    _partialTranslations.update { it + (chapterId to done.toMap()) }
                }
                for ((name, page) in recognized) {
                    translation.updateProgress { it.copy(toTranslate = it.toTranslate + 1) }
                    if (name in done) continue
                    batch[name] = page
                    if (batch.size == BATCH_SIZE) translateBatch()
                }
                if (batch.isNotEmpty()) translateBatch()
                ocr.await()
            }

            mangaDir.createFile(
                provider.getTranslationFileName(translation.chapter.name, translation.chapter.scanlator),
            )!!.openOutputStream().use { it.write(Json.encodeToString(pages).toByteArray()) }
            provider.saveGlossary(mangaDir, glossary)
            recognizedChapters.remove(chapterId)
            recognizedImages.remove(chapterId)
            _partialTranslations.update { it - chapterId }
            translation.progress = null
            translation.status = Translation.State.TRANSLATED
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            logcat(LogPriority.ERROR, e)
            val error = e.message ?: e.toString()
            translation.error = error
            translation.progress = null
            translation.status = Translation.State.ERROR
            val message = context.stringResource(WTMR.strings.translation_failed, translation.chapter.name, error)
            withContext(Dispatchers.Main) { context.toast(message, Toast.LENGTH_LONG) }
        }
    }

    private fun createTranslator(): PageTranslator {
        val prefs = translationPreferences
        val service = prefs.service.get()
        val apiKey = ApiKeyCipher.decrypt(prefs.apiKey(service).get())
        val model = prefs.model(service).get()
        val customBaseUrl = prefs.customBaseUrl.get()
        if (service == TranslationService.CUSTOM && customBaseUrl.isBlank()) {
            throw IOException(context.stringResource(WTMR.strings.translation_error_no_base_url))
        }
        if (apiKey.isBlank()) throw IOException(context.stringResource(WTMR.strings.translation_error_no_api_key))
        if (service.hasModels && model.isBlank()) {
            throw IOException(context.stringResource(WTMR.strings.translation_error_no_model))
        }
        return PageTranslator.create(service, apiKey, model, customBaseUrl)
    }

    /**
     * Returns the text blocks of each page that has any, keyed by the page's file name, passing each such page to
     * [onPage] as soon as it is read. Puts a small image of each such page in [images] when given.
     */
    private suspend fun recognizeChapter(
        chapterDir: UniFile,
        translation: Translation,
        images: MutableMap<String, ByteArray>?,
        onPage: suspend (String, PageTranslation) -> Unit,
    ): Map<String, PageTranslation> {
        val pages = mutableMapOf<String, PageTranslation>()
        withChapterPages(chapterDir) { index, count, fileName, openStream ->
            currentCoroutineContext().ensureActive()
            translation.updateProgress { it.copy(page = index, pages = count) }
            val bitmap = decodePage(openStream)
            try {
                val blocks = textRecognizer.recognize(InputImage.fromBitmap(bitmap, 0)).textBlocks
                    // Single letters are kept: an "I" alone on its line would stay in English beside the translation
                    .filter { it.boundingBox != null && it.text.isNotBlank() }
                val page = toPageTranslation(blocks, bitmap.width, bitmap.height)
                val pixels = bitmap.lightPixels()
                val found = page.blocks
                page.blocks = found.mapTo(mutableListOf()) {
                    it.inBubble(bitmap.width, bitmap.height, found, pixels) ?: it.grownIntoBubble(bitmap)
                }
                if (page.blocks.isNotEmpty()) {
                    pages[fileName] = page
                    images?.put(fileName, bitmap.toSmallJpeg())
                }
            } finally {
                bitmap.recycle()
            }
            pages[fileName]?.let { onPage(fileName, it) }
            translation.updateProgress { it.copy(page = index + 1, pages = count) }
        }
        return pages
    }

    /** Returns this page shrunk to a size the model reads the lettering at, without many image tokens. */
    private fun Bitmap.toSmallJpeg(): ByteArray {
        val scale = min(1f, MODEL_IMAGE_SIZE.toFloat() / max(width, height))
        val small = scale((width * scale).toInt().coerceAtLeast(1), (height * scale).toInt().coerceAtLeast(1))
        return ByteArrayOutputStream().use { out ->
            small.compress(Bitmap.CompressFormat.JPEG, MODEL_IMAGE_QUALITY, out)
            if (small !== this) small.recycle()
            out.toByteArray()
        }
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
        block: suspend (index: Int, count: Int, fileName: String, openStream: () -> InputStream) -> Unit,
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
                names.forEachIndexed { i, name -> block(i, names.size, name) { reader.getInputStream(name)!! } }
            }
        } else {
            val files = chapterDir.listFiles().orEmpty()
                .filter { ImageUtil.isImage(it.name) }
                .sortedWith { f1, f2 -> f1.name.orEmpty().compareToCaseInsensitiveNaturalOrder(f2.name.orEmpty()) }
            files.forEachIndexed { i, file -> block(i, files.size, file.name!!) { file.openInputStream() } }
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
        page.blocks = mergeBubbleBlocks(page.blocks)
        return page
    }

    /**
     * Joins blocks that look like parts of the same speech bubble. ML Kit doesn't return a bubble's blocks one after
     * the other, so every pair is compared.
     */
    private fun mergeBubbleBlocks(blocks: List<TranslationBlock>): MutableList<TranslationBlock> {
        val merged = blocks.sortedBy { it.y }.toMutableList()
        var i = 0
        while (i < merged.size) {
            val j = (i + 1..<merged.size).firstOrNull { isSameBubble(merged[i], merged[it]) }
            if (j == null) {
                i++
            } else {
                merged[i] = mergeTextBlock(merged[i], merged.removeAt(j))
            }
        }
        return merged
    }

    /**
     * Whether [below] continues [above]: stacked less than about a line apart and overlapping horizontally. Distances
     * are measured in letter heights, so they hold at any page resolution.
     */
    private fun isSameBubble(above: TranslationBlock, below: TranslationBlock): Boolean {
        val lineHeight = max(above.symHeight, below.symHeight)
        val gap = below.y - (above.y + above.height)
        val overlap = min(above.x + above.width, below.x + below.width) - max(above.x, below.x)
        return gap > -lineHeight && gap < lineHeight * 1.2f && overlap > min(above.width, below.width) / 2
    }

    private fun mergeTextBlock(a: TranslationBlock, b: TranslationBlock): TranslationBlock {
        val newX = min(a.x, b.x)
        val newY = min(a.y, b.y)
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

    private companion object {
        const val BATCH_SIZE = 5
        const val MODEL_IMAGE_SIZE = 1024
        const val MODEL_IMAGE_QUALITY = 75
        const val MAX_GLOSSARY_TERMS = 300
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
        recognizedChapters.remove(chapter.id)
        _partialTranslations.update { it - chapter.id }
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
