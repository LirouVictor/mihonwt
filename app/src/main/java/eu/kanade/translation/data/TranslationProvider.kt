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

    /** Returns how a manga's names and recurring terms were translated, so later chapters keep them. */
    fun getGlossary(mangaDir: UniFile): Map<String, String> {
        val file = mangaDir.findFile(GLOSSARY_FILE_NAME) ?: return emptyMap()
        return runCatching {
            file.openInputStream().use { Json.decodeFromString<Map<String, String>>(it.reader().readText()) }
        }.getOrElse {
            logcat(LogPriority.WARN, it) { "Unreadable glossary" }
            emptyMap()
        }
    }

    fun saveGlossary(mangaDir: UniFile, glossary: Map<String, String>) {
        mangaDir.findFile(GLOSSARY_FILE_NAME)?.delete()
        mangaDir.createFile(GLOSSARY_FILE_NAME)!!.openOutputStream().use {
            it.write(Json.encodeToString(glossary).toByteArray())
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

    private companion object {
        // Hidden, and named unlike any chapter's translation file
        const val GLOSSARY_FILE_NAME = ".glossary.json"
    }
}
