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
import eu.kanade.domain.chapter.model.toSChapter
import eu.kanade.domain.manga.model.readingMode
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalOrder
import eu.kanade.tachiyomi.util.system.toast
import eu.kanade.translation.data.ApiKeyCipher
import eu.kanade.translation.data.CharacterLookup
import eu.kanade.translation.data.TranslationProvider
import eu.kanade.translation.data.isGlossaryTerm
import eu.kanade.translation.data.startingGlossary
import eu.kanade.translation.model.FoundText
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.Translation
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.model.joinLines
import eu.kanade.translation.model.onlinePageName
import eu.kanade.translation.recognizer.BubbleDetector
import eu.kanade.translation.recognizer.Letter
import eu.kanade.translation.recognizer.LightPixels
import eu.kanade.translation.recognizer.OcrSpelling
import eu.kanade.translation.recognizer.TextBox
import eu.kanade.translation.recognizer.TextRecognizer
import eu.kanade.translation.recognizer.blocksInBoxes
import eu.kanade.translation.recognizer.blocksOfFoundText
import eu.kanade.translation.recognizer.bubbleBlocks
import eu.kanade.translation.recognizer.fixLetteringMistakes
import eu.kanade.translation.recognizer.grownIntoBubble
import eu.kanade.translation.recognizer.inBubble
import eu.kanade.translation.recognizer.inReadingOrder
import eu.kanade.translation.recognizer.isCorrectionOf
import eu.kanade.translation.recognizer.lightPixels
import eu.kanade.translation.recognizer.withBackground
import eu.kanade.translation.recognizer.withOverlapsJoined
import eu.kanade.translation.recognizer.wordOf
import eu.kanade.translation.translator.BlockTranslator
import eu.kanade.translation.translator.GeminiTranslator
import eu.kanade.translation.translator.LanguageModelTranslator
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import logcat.LogPriority
import mihon.core.archive.archiveReader
import mihon.data.cache.ChapterCache
import okhttp3.OkHttpClient
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
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** Queue that runs OCR and translation on chapters, downloaded or online, and saves the result by the downloads. */
@Inject
@SingleIn(AppScope::class)
class ChapterTranslator(
    private val context: Context,
    private val provider: TranslationProvider,
    private val downloadProvider: DownloadProvider,
    private val chapterCache: ChapterCache,
    private val sourceManager: SourceManager,
    private val translationPreferences: TranslationPreferences,
    private val readerPreferences: ReaderPreferences,
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

    /** How long each step of the chapters translated since the app started took, by chapter id. */
    val stepTimes = ConcurrentHashMap<Long, String>()

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
                        .map { (_, translations) -> translations.next() }
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

    /**
     * The one of these translations, all from one source, to run now: the one running, so it isn't cancelled, or else
     * the lowest chapter of the first queued series. Chapters downloaded together finish downloading in any order, and
     * the reader needs the earliest first.
     */
    private fun List<Translation>.next(): Translation {
        return firstOrNull { it.status == Translation.State.TRANSLATING }
            ?: filter { it.manga.id == first().manga.id }.minBy { it.chapter.chapterNumber }
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
            val service = translationPreferences.service.get()
            val translatedWith = translator.name +
                translationPreferences.model(service).get().takeIf { service.hasModels }?.let { " ($it)" }.orEmpty()
            val mangaDir = provider.getMangaDir(translation.manga.title, translation.source)
            val chapterPages = chapterPages(translation)

            val chapterId = translation.chapter.id
            val done = partialTranslations.value[chapterId].orEmpty().toMutableMap()
            val images = recognizedImages.getOrPut(chapterId) { ConcurrentHashMap() }
            // Glossaries saved before single English words were left out drop them now
            val glossary = provider.getGlossary(mangaDir).filterKeys(::isGlossaryTerm).toMutableMap()
            // A series is looked up once per run of the app while its glossary is empty, so a series nothing knows
            // isn't asked about again for every chapter
            if (glossary.isEmpty() && lookedUpSeries.add(translation.manga.id)) {
                glossary += lookUpGlossary(translation.manga.title, translator)
            }
            val chapterFileName =
                provider.getTranslationFileName(translation.chapter.name, translation.chapter.scanlator)
            val seenWords = provider.getSeenWords(mangaDir).toMutableMap()
            val readWords = ConcurrentHashMap.newKeySet<String>()
            val times = Times()
            val corrector = gemini().takeIf {
                translator is BlockTranslator && translationPreferences.correctTextWithGemini.get()
            }
            val findsText = translator is LanguageModelTranslator &&
                translator.readsImages &&
                translationPreferences.findTextOnImages.get()
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
                        val pageImages = images.takeIf { translator.readsImages || corrector != null }
                        val detector = bubbleDetector().takeUnless { findsText }
                        val learnedNames = seenWords.filterValues { it != chapterFileName }.keys
                        val names = OcrSpelling.namesOf(glossary.keys) + learnedNames
                        // Only words left unknown after fixing are kept, so a misreading fixed once isn't taken for a name
                        val fixText = { text: String ->
                            // Lettering fixes run again on the whole block, as they need the line after an ellipsis
                            spelling.fix(
                                text.fixLetteringMistakes(),
                                names,
                                anyLetter = names.isNotEmpty(),
                                seen = seenWords.keys + readWords,
                            )
                                .also { readWords += spelling.unknownWords(it) }
                        }
                        recognizeChapter(chapterPages, translation, pageImages, detector, fixText, times) {
                                name,
                                page,
                            ->
                            recognized.send(name to page)
                        }
                            .also { recognizedChapters[chapterId] = it }
                    }.also {
                        recognized.close()
                        times.read.set(times.start.elapsedNow().inWholeMilliseconds)
                    }
                }
                // Batches go out as soon as OCR has read their pages, so the reader shows the first pages while the
                // rest are still being read. Small batches also keep each reply within the model's output limit, and a
                // few at a time keep a chapter from waiting on each reply in turn
                val onRetry: (Int) -> Unit = { attempt ->
                    translation.updateProgress { progress -> progress.copy(retries = attempt) }
                }
                val inFlight = Semaphore(PARALLEL_BATCHES)
                val results = Mutex()
                suspend fun translateBatch(batch: Map<String, PageTranslation>) = inFlight.withPermit {
                    val batchStart = TimeSource.Monotonic.markNow()
                    val title = translation.manga.title
                    val known = results.withLock { glossary.toMap() }
                    var terms = emptyMap<String, String>()
                    var answered = emptySet<String>()
                    if (findsText) {
                        val (found, foundTerms) = (translator as LanguageModelTranslator)
                            .findText(batch, title, images, known, onRetry)
                        found.forEach { (name, texts) -> batch.getValue(name).useText(chapterPages, name, texts) }
                        terms = foundTerms
                        answered = found.keys
                    }
                    // Pages the model skipped still get the text OCR grouped translated
                    val left = batch.filterKeys { it !in answered }
                    if (corrector != null && left.isNotEmpty()) correctText(corrector, left, title, images, times)
                    if (left.isNotEmpty()) terms = terms + translator.translate(left, title, images, known, onRetry)
                    times.translate.addAndGet(batchStart.elapsedNow().inWholeMilliseconds)
                    results.withLock {
                        // Terms the model gives again move to the end, keeping how they were first translated, so a
                        // full glossary drops those not seen for longest rather than refusing new characters
                        terms.filterKeys(::isGlossaryTerm).forEach { (term, translated) ->
                            glossary[term] = glossary.remove(term) ?: translated
                        }
                        glossary.keys.take(max(0, glossary.size - MAX_GLOSSARY_TERMS)).forEach(glossary::remove)
                        done.putAll(batch)
                        translation.updateProgress { it.copy(translated = done.size, retries = 0) }
                        _partialTranslations.update { it + (chapterId to done.toMap()) }
                    }
                }
                val alreadyDone = done.keys.toSet()
                var batch = mutableMapOf<String, PageTranslation>()
                for ((name, page) in recognized) {
                    translation.updateProgress { it.copy(toTranslate = it.toTranslate + 1) }
                    if (name in alreadyDone) continue
                    batch[name] = page
                    if (batch.size == BATCH_SIZE) {
                        val full = batch
                        launch { translateBatch(full) }
                        batch = mutableMapOf()
                    }
                }
                if (batch.isNotEmpty()) launch { translateBatch(batch) }
                ocr.await()
            }

            mangaDir.createFile(chapterFileName)!!.openOutputStream().use {
                it.write(Json.encodeToString(pages).toByteArray())
            }
            provider.saveGlossary(mangaDir, glossary)
            if (readWords.isNotEmpty()) {
                // Words read again move to the end, so a full list drops those not read for longest
                readWords.forEach { seenWords[it] = seenWords.remove(it) ?: chapterFileName }
                seenWords.keys.take(max(0, seenWords.size - MAX_SEEN_WORDS)).forEach(seenWords::remove)
                provider.saveSeenWords(mangaDir, seenWords)
            }
            recognizedChapters.remove(chapterId)
            recognizedImages.remove(chapterId)
            _partialTranslations.update { it - chapterId }
            translation.progress = null
            stepTimes[chapterId] = context.stringResource(
                WTMR.strings.translation_times,
                translatedWith,
                times.start.elapsedNow().inWholeMilliseconds.seconds(),
                times.read.get().seconds(),
                times.decode.get().seconds(),
                times.ocr.get().seconds(),
                times.detect.get().seconds(),
                times.bubbles.get().seconds(),
                times.translate.get().seconds(),
            ) + if (corrector != null) {
                ". " + context.stringResource(
                    WTMR.strings.translation_times_corrected,
                    times.correct.get().seconds(),
                    times.corrected.get(),
                    times.correctable.get(),
                )
            } else {
                ""
            }
            translation.status = Translation.State.TRANSLATED
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            logcat(LogPriority.ERROR, e)
            val error = if (e is PageTranslator.QuotaExceededException) {
                context.stringResource(WTMR.strings.translation_error_quota, e.service)
            } else {
                e.message ?: e.toString()
            }
            translation.error = error
            translation.progress = null
            translation.status = Translation.State.ERROR
            val message = context.stringResource(WTMR.strings.translation_failed, translation.chapter.name, error)
            withContext(Dispatchers.Main) { context.toast(message, Toast.LENGTH_LONG) }
        }
    }

    /**
     * Returns the names and terms of the series titled [title] to start its glossary with: its characters from
     * AniList or MyAnimeList, and what a language model knows of it, the [translator] if it is one or else Gemini if it has a key.
     */
    private suspend fun lookUpGlossary(title: String, translator: PageTranslator): Map<String, String> =
        coroutineScope {
            val characters = async { lookUp { characterLookup.characterNames(title) }.orEmpty() }
            val model = translator as? LanguageModelTranslator ?: gemini()
            val terms = async { model?.let { lookUp { it.seriesTerms(title) } }.orEmpty() }
            startingGlossary(characters.await(), terms.await(), spelling::isWord)
        }

    // A glossary only helps, so a failed lookup leaves it to grow as before
    private suspend fun <T> lookUp(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logcat(LogPriority.WARN, e) { "Series lookup failed" }
        null
    }

    private fun gemini(): GeminiTranslator? {
        val apiKey = ApiKeyCipher.decrypt(translationPreferences.apiKey(TranslationService.GEMINI).get())
        val model = translationPreferences.model(TranslationService.GEMINI).get()
        return if (apiKey.isBlank() || model.isBlank()) null else GeminiTranslator(apiKey, model)
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
        if (service.needsApiKey && apiKey.isBlank()) {
            throw IOException(context.stringResource(WTMR.strings.translation_error_no_api_key))
        }
        if (service.hasModels && model.isBlank()) {
            throw IOException(context.stringResource(WTMR.strings.translation_error_no_model))
        }
        return PageTranslator.create(service, apiKey, model, customBaseUrl)
    }

    /**
     * Returns the text blocks of each page that has any, keyed by the page's file name, passing each such page to
     * [onPage] as soon as it is read, with the blocks' text passed through [fixText]. Puts a small image of each such
     * page in [images] when given.
     */
    private suspend fun recognizeChapter(
        chapterPages: ChapterPages,
        translation: Translation,
        images: MutableMap<String, ByteArray>?,
        detector: BubbleDetector?,
        fixText: (String) -> String,
        times: Times,
        onPage: suspend (String, PageTranslation) -> Unit,
    ): Map<String, PageTranslation> {
        val pages = mutableMapOf<String, PageTranslation>()
        chapterPages.forEach { index, count, fileName, openStream ->
            currentCoroutineContext().ensureActive()
            translation.updateProgress { it.copy(page = index, pages = count) }
            val decodeStart = TimeSource.Monotonic.markNow()
            val bitmap = decodePage(openStream)
            times.decode.addAndGet(decodeStart.elapsedNow().inWholeMilliseconds)
            try {
                val (ocr, textBoxes) = coroutineScope {
                    // The detector runs on the CPU while ML Kit reads the page on its own threads
                    val detection = detector?.let {
                        async(Dispatchers.Default) {
                            val detectStart = TimeSource.Monotonic.markNow()
                            it.detect(bitmap).also {
                                times.detect.addAndGet(detectStart.elapsedNow().inWholeMilliseconds)
                            }
                        }
                    }
                    val ocrScale = bitmap.ocrScale()
                    val ocrBitmap = if (ocrScale > 1f) {
                        bitmap.scale((bitmap.width * ocrScale).toInt(), (bitmap.height * ocrScale).toInt())
                    } else {
                        bitmap
                    }
                    val ocrStart = TimeSource.Monotonic.markNow()
                    val blocks = try {
                        textRecognizer.recognize(InputImage.fromBitmap(ocrBitmap, 0)).textBlocks
                    } finally {
                        times.ocr.addAndGet(ocrStart.elapsedNow().inWholeMilliseconds)
                        if (ocrBitmap !== bitmap) ocrBitmap.recycle()
                    }
                        // Single letters are kept: an "I" alone on its line would stay in English beside the
                        // translation
                        .filter { it.boundingBox != null && it.text.isNotBlank() }
                    (blocks to ocrScale) to detection?.await()
                }
                val bubblesStart = TimeSource.Monotonic.markNow()
                val pixels = bitmap.lightPixels()
                times.bubbles.addAndGet(bubblesStart.elapsedNow().inWholeMilliseconds)
                val (blocks, ocrScale) = ocr
                val groupStart = TimeSource.Monotonic.markNow()
                val page = toPageTranslation(blocks, bitmap.width, bitmap.height, ocrScale, pixels, textBoxes)
                val found = page.blocks
                page.blocks = found
                    .map { it.inBubble(bitmap.width, bitmap.height, found, pixels) ?: it.grownIntoBubble(bitmap) }
                    .inReadingOrder(translation.isRightToLeft())
                    .withOverlapsJoined()
                    .mapTo(mutableListOf()) { block ->
                        block.withBackground(bitmap).let { it.copy(text = fixText(it.text)) }
                    }
                times.bubbles.addAndGet(groupStart.elapsedNow().inWholeMilliseconds)
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

    /**
     * Milliseconds a chapter spent on each step, shown in its menu to see which step to speed up. Batches run in
     * parallel with each other and with reading, so the steps add up to more than the whole.
     */
    private class Times {
        val start = TimeSource.Monotonic.markNow()
        val read = AtomicLong()
        val decode = AtomicLong()
        val ocr = AtomicLong()
        val bubbles = AtomicLong()
        val detect = AtomicLong()
        val translate = AtomicLong()
        val correct = AtomicLong()
        val corrected = AtomicInteger()
        val correctable = AtomicInteger()
    }

    /**
     * Replaces the text of the blocks of [pages] with how [corrector] reads it on their [images], where that is the
     * same text with OCR's mistakes fixed. The text OCR read stays if the model fails, as it is only a correction.
     */
    private suspend fun correctText(
        corrector: LanguageModelTranslator,
        pages: Map<String, PageTranslation>,
        series: String,
        images: Map<String, ByteArray>,
        times: Times,
    ) {
        val start = TimeSource.Monotonic.markNow()
        val corrections = try {
            corrector.correctText(pages, series, images) {}
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Text correction failed" }
            emptyMap()
        }
        times.correct.addAndGet(start.elapsedNow().inWholeMilliseconds)
        pages.forEach { (name, page) ->
            val fixed = corrections[name].orEmpty()
            times.correctable.addAndGet(page.blocks.size)
            page.blocks = page.blocks.mapIndexedTo(mutableListOf()) { i, block ->
                val text = fixed[i]?.takeIf { it != block.text.joinLines() && isCorrectionOf(block.text, it) }
                    ?: return@mapIndexedTo block
                times.corrected.incrementAndGet()
                block.copy(text = text)
            }
        }
    }

    /** Whether the reader shows [Translation.manga] right to left, as manga, so its bubbles are read that way too. */
    private fun Translation.isRightToLeft(): Boolean {
        val mode = ReadingMode.fromPreference(manga.readingMode.toInt()).takeUnless { it == ReadingMode.DEFAULT }
            ?: ReadingMode.fromPreference(readerPreferences.defaultReadingMode.get())
        return mode == ReadingMode.RIGHT_TO_LEFT
    }

    private fun Long.seconds() = this / 1000f

    private var detector: BubbleDetector? = null

    private val spelling by lazy { OcrSpelling.load(context) }

    private fun isGlossaryTerm(term: String) = isGlossaryTerm(term, spelling::isWord)

    private val characterLookup by lazy { CharacterLookup(OkHttpClient.Builder().callTimeout(LOOKUP_TIMEOUT).build()) }

    private val lookedUpSeries = ConcurrentHashMap.newKeySet<Long>()

    /**
     * Returns the bubble detector when it is turned on, loading it the first time. When it can't be loaded, as without
     * a connection for the first download, says why and returns null, so the chapter is grouped without it.
     */
    private suspend fun bubbleDetector(): BubbleDetector? {
        if (!translationPreferences.detectBubbles.get()) return null
        detector?.let { return it }
        return try {
            BubbleDetector.load(context).also { detector = it }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            logcat(LogPriority.ERROR, e)
            val message = context.stringResource(
                WTMR.strings.translation_detector_unavailable,
                e.message ?: e.toString(),
            )
            withContext(Dispatchers.Main) { context.toast(message, Toast.LENGTH_LONG) }
            null
        }
    }

    /**
     * Replaces the blocks OCR grouped on this page with the [texts] a model found on it, each placed in its speech
     * bubble.
     */
    private suspend fun PageTranslation.useText(chapterPages: ChapterPages, name: String, texts: List<FoundText>) {
        val bitmap = chapterPages.open(name) { decodePage(it) }
        try {
            val pixels = bitmap.lightPixels()
            val found = blocksOfFoundText(texts, words)
            blocks = found
                .map { it.inBubble(bitmap.width, bitmap.height, found, pixels) ?: it.grownIntoBubble(bitmap) }
                .withOverlapsJoined()
                .mapTo(mutableListOf()) { it.withBackground(bitmap) }
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * Returns how much to enlarge this page for OCR. ML Kit misses or misreads lettering under about 20 px tall, which
     * pages around 800 px wide have, so they are read at about twice the size. Spreads count as two pages, and long
     * strips are enlarged less to keep the bitmap's memory in bounds.
     */
    private fun Bitmap.ocrScale(): Float {
        val pageWidth = if (width > height) width / 2 else width
        val byWidth = OCR_PAGE_WIDTH.toFloat() / pageWidth
        val byMemory = sqrt(MAX_OCR_PIXELS.toFloat() / (width.toLong() * height))
        return min(min(byWidth, byMemory), MAX_OCR_SCALE).coerceAtLeast(1f)
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

    /** Returns the pages of the chapter, from its download when it has one, otherwise from its source. */
    private suspend fun chapterPages(translation: Translation): ChapterPages {
        val chapter = translation.chapter
        val chapterDir = downloadProvider.findChapterDir(
            chapter.name,
            chapter.scanlator,
            chapter.url,
            translation.manga.title,
            translation.source,
        )
        return if (chapterDir != null) DownloadedPages(chapterDir) else OnlinePages(translation.source, chapter)
    }

    /** The page images of a chapter, each under the name its translation is saved under. */
    private interface ChapterPages {
        suspend fun forEach(
            block: suspend (index: Int, count: Int, name: String, openStream: () -> InputStream) -> Unit,
        )

        suspend fun <T> open(name: String, block: (() -> InputStream) -> T): T
    }

    private inner class DownloadedPages(private val chapterDir: UniFile) : ChapterPages {

        override suspend fun forEach(
            block: suspend (index: Int, count: Int, name: String, openStream: () -> InputStream) -> Unit,
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
                    .sortedWith { f1, f2 ->
                        f1.name.orEmpty().compareToCaseInsensitiveNaturalOrder(f2.name.orEmpty())
                    }
                files.forEachIndexed { i, file -> block(i, files.size, file.name!!) { file.openInputStream() } }
            }
        }

        override suspend fun <T> open(name: String, block: (() -> InputStream) -> T): T {
            if (chapterDir.isFile) {
                return chapterDir.archiveReader(context).use { reader -> block { reader.getInputStream(name)!! } }
            }
            val file = chapterDir.findFile(name) ?: throw IOException("Page $name not found")
            return block { file.openInputStream() }
        }
    }

    /**
     * The pages of a chapter that isn't downloaded, fetched through the cache the reader uses, so reading the chapter
     * while it is translated or after doesn't fetch them again.
     */
    private inner class OnlinePages(private val source: HttpSource, private val chapter: Chapter) : ChapterPages {

        private val pages = mutableMapOf<String, Page>()

        override suspend fun forEach(
            block: suspend (index: Int, count: Int, name: String, openStream: () -> InputStream) -> Unit,
        ) {
            val list = chapterCache.getPageList(chapter)
                ?: source.getPageList(chapter.toSChapter()).also { chapterCache.putPageList(chapter, it) }
            list.forEachIndexed { i, page -> pages[onlinePageName(i, list.size)] = page }
            coroutineScope {
                // The next page downloads while this one is read
                var next = list.firstOrNull()?.let { async { imageFile(it) } }
                list.forEachIndexed { i, page ->
                    val file = next!!.await()
                    next = list.getOrNull(i + 1)?.let { async { imageFile(it) } }
                    block(i, list.size, onlinePageName(i, list.size)) { file.inputStream() }
                }
            }
        }

        override suspend fun <T> open(name: String, block: (() -> InputStream) -> T): T {
            val file = imageFile(pages[name] ?: throw IOException("Page $name not found"))
            return block { file.inputStream() }
        }

        private suspend fun imageFile(page: Page): File {
            val imageUrl = page.imageUrl?.takeIf { it.isNotEmpty() }
                ?: source.getImageUrl(page).also { page.imageUrl = it }
            return chapterCache.getImageFile(imageUrl) ?: chapterCache.putImage(imageUrl, source.getImage(page))
        }
    }

    /** Returns the [blocks] read on a page enlarged [scale] times, at the size of the page itself. */
    private fun toPageTranslation(
        blocks: List<Text.TextBlock>,
        width: Int,
        height: Int,
        scale: Float,
        pixels: LightPixels,
        textBoxes: List<TextBox>?,
    ): PageTranslation {
        var lineNumber = 0
        val words = blocks.flatMap { block ->
            block.lines.flatMap { line ->
                val number = lineNumber++
                line.elements.mapNotNull { element ->
                    val letters = element.symbols.mapNotNull { symbol ->
                        val bounds = symbol.boundingBox ?: return@mapNotNull null
                        Letter(
                            text = symbol.text,
                            x = bounds.left / scale,
                            y = bounds.top / scale,
                            width = bounds.width() / scale,
                            height = bounds.height() / scale,
                        )
                    }
                    wordOf(letters, number, line.angle)
                }
            }
        }
        return PageTranslation(
            blocks = textBoxes?.let { blocksInBoxes(it, words) }?.toMutableList()
                ?: bubbleBlocks(words, pixels).toMutableList(),
            imgWidth = width.toFloat(),
            imgHeight = height.toFloat(),
            words = words,
        )
    }

    private companion object {
        const val BATCH_SIZE = 5
        const val PARALLEL_BATCHES = 3
        const val MODEL_IMAGE_SIZE = 1024
        const val MODEL_IMAGE_QUALITY = 75

        const val OCR_PAGE_WIDTH = 1600
        const val MAX_OCR_SCALE = 2.5f
        const val MAX_OCR_PIXELS = 16_000_000
        const val MAX_GLOSSARY_TERMS = 300
        const val MAX_SEEN_WORDS = 5000
        val LOOKUP_TIMEOUT = 15.seconds
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
