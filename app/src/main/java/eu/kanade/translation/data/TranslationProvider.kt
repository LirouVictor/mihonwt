// Adapted from https://github.com/mannu691/TachiyomiAT
// Copyright mannu691 and TachiyomiAT contributors, Apache License 2.0

package eu.kanade.translation.data

import android.content.Context
import com.hippo.unifile.UniFile
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.util.storage.DiskUtil
import kotlinx.serialization.json.Json
import logcat.LogPriority
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.storage.displayablePath
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.storage.service.StorageManager
import tachiyomi.domain.translation.TargetLanguage
import tachiyomi.i18n.MR
import java.io.IOException

/**
 * Locates the translation files, one JSON file per chapter, laid out like downloads:
 * /<root translations dir>/<source name>/<manga>/<chapter>.json
 */
@Inject
@SingleIn(AppScope::class)
class TranslationProvider(
    private val context: Context,
    private val storageManager: StorageManager,
) {

    private val translationsDir: UniFile?
        get() = storageManager.getTranslationsDirectory()

    /** Returns the translation directory for a manga, creating it if needed. */
    fun getMangaDir(mangaTitle: String, source: Source): UniFile {
        val translationsDir = translationsDir
        return translationsDir
            ?.createDirectory(getSourceDirName(source))
            ?.createDirectory(getMangaDirName(mangaTitle))
            ?: run {
                logcat(LogPriority.ERROR) { "Invalid translation directory" }
                throw IOException(
                    context.stringResource(MR.strings.invalid_location, translationsDir?.displayablePath.orEmpty()),
                )
            }
    }

    fun findSourceDir(source: Source): UniFile? {
        return translationsDir?.findFile(getSourceDirName(source))
    }

    fun findMangaDir(mangaTitle: String, source: Source): UniFile? {
        return findSourceDir(source)?.findFile(getMangaDirName(mangaTitle))
    }

    fun findTranslationFile(
        chapterName: String,
        chapterScanlator: String?,
        mangaTitle: String,
        source: Source,
    ): UniFile? {
        return findMangaDir(mangaTitle, source)?.findFile(getTranslationFileName(chapterName, chapterScanlator))
    }

    /** Returns the names of a manga's translation files, read in one listing. */
    fun getTranslationFileNames(mangaTitle: String, source: Source): Set<String> {
        return findMangaDir(mangaTitle, source)?.listFiles().orEmpty().mapNotNullTo(HashSet()) { it.name }
    }

    /** Returns how a manga's names and recurring terms were translated into [language], so later chapters keep them. */
    fun getGlossary(mangaDir: UniFile, language: TargetLanguage): Map<String, String> {
        val file = mangaDir.findFile(glossaryFileName(language)) ?: return emptyMap()
        return runCatching {
            file.openInputStream().use { Json.decodeFromString<Map<String, String>>(it.reader().readText()) }
        }.getOrElse {
            logcat(LogPriority.WARN, it) { "Unreadable glossary" }
            emptyMap()
        }
    }

    /** Returns the English terms of a manga's glossaries in every language, names that OCR can then read right. */
    fun getGlossaryTerms(mangaDir: UniFile): Set<String> {
        val languages = TargetLanguage.entries.associateBy(::glossaryFileName)
        return mangaDir.listFiles().orEmpty()
            .mapNotNull { languages[it.name] }
            .flatMapTo(HashSet()) { getGlossary(mangaDir, it).keys }
    }

    fun saveGlossary(mangaDir: UniFile, language: TargetLanguage, glossary: Map<String, String>) {
        val fileName = glossaryFileName(language)
        mangaDir.findFile(fileName)?.delete()
        mangaDir.createFile(fileName)!!.openOutputStream().use {
            it.write(Json.encodeToString(glossary).toByteArray())
        }
    }

    /**
     * Returns the words OCR read in a manga that aren't English, each with the chapter it was first read in, so a word
     * read again in another chapter is taken as one of the manga's names rather than a misreading.
     */
    fun getSeenWords(mangaDir: UniFile): Map<String, String> {
        val file = mangaDir.findFile(SEEN_WORDS_FILE_NAME) ?: return emptyMap()
        return runCatching {
            file.openInputStream().use { Json.decodeFromString<Map<String, String>>(it.reader().readText()) }
        }.getOrElse {
            logcat(LogPriority.WARN, it) { "Unreadable seen words" }
            emptyMap()
        }
    }

    fun saveSeenWords(mangaDir: UniFile, words: Map<String, String>) {
        mangaDir.findFile(SEEN_WORDS_FILE_NAME)?.delete()
        mangaDir.createFile(SEEN_WORDS_FILE_NAME)!!.openOutputStream().use {
            it.write(Json.encodeToString(words).toByteArray())
        }
    }

    private fun getSourceDirName(source: Source): String {
        return DiskUtil.buildValidFilename(source.toString())
    }

    private fun getMangaDirName(mangaTitle: String): String {
        return DiskUtil.buildValidFilename(mangaTitle)
    }

    fun getTranslationFileName(chapterName: String, chapterScanlator: String?): String {
        val name = chapterName.ifBlank { "Chapter" }
        return DiskUtil.buildValidFilename(
            if (chapterScanlator.isNullOrBlank()) "$name.json" else "${chapterScanlator}_$name.json",
        )
    }

    // Hidden, and named unlike any chapter's translation file. Brazilian Portuguese, the first language, keeps the
    // name glossaries had before there were others
    private fun glossaryFileName(language: TargetLanguage) =
        if (language == TargetLanguage.PT_BR) ".glossary.json" else ".glossary.${language.tag}.json"

    private companion object {
        const val SEEN_WORDS_FILE_NAME = ".ocr_words.json"
    }
}
