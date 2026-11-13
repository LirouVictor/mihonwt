package eu.kanade.tachiyomi.ui.reader.loader

import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalOrder
import eu.kanade.translation.model.PageTranslation
import tachiyomi.core.common.util.system.ImageUtil

/**
 * Loader used to load a chapter from a directory given on [file], with the [translations] of its pages keyed by
 * file name.
 */
internal class DirectoryPageLoader(
    val file: UniFile,
    private val translations: Map<String, PageTranslation> = emptyMap(),
) : PageLoader() {

    override var isLocal: Boolean = true

    override suspend fun getPages(): List<ReaderPage> {
        return file.listFiles()
            ?.filter { !it.isDirectory && ImageUtil.isImage(it.name) { it.openInputStream() } }
            ?.sortedWith { f1, f2 -> f1.name.orEmpty().compareToCaseInsensitiveNaturalOrder(f2.name.orEmpty()) }
            ?.mapIndexed { i, file ->
                val streamFn = { file.openInputStream() }
                ReaderPage(i).apply {
                    stream = streamFn
                    translationKey = file.name
                    translation = translations[file.name]
                    status = Page.State.Ready
                }
            }
            .orEmpty()
    }
}
