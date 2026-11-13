package eu.kanade.tachiyomi.ui.reader.model

import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.translation.model.PageTranslation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.InputStream

open class ReaderPage(
    index: Int,
    url: String = "",
    imageUrl: String? = null,
    var stream: (() -> InputStream)? = null,
) : Page(index, url, imageUrl, null) {

    open lateinit var chapter: ReaderChapter

    private val _translationFlow = MutableStateFlow<PageTranslation?>(null)
    val translationFlow = _translationFlow.asStateFlow()

    /** The translation of the page's image, in that image's coordinates. */
    var translation: PageTranslation?
        get() = _translationFlow.value
        set(value) {
            _translationFlow.value = value
        }

    /** Name of the file or archive entry the page's image comes from, which keys its translation. */
    var translationKey: String? = null

    /** Set by a viewer before the download; filled during [Page.State.DownloadImage]. */
    @Volatile
    var downloadStream: DownloadStream? = null
}
