package eu.kanade.translation.translator

import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Translates on the device with ML Kit, which downloads the language models, about 30 MB each, on first use. */
class OfflineTranslator : BlockTranslator() {

    override val name = "ML Kit"

    override suspend fun translatePage(
        texts: List<String>,
        glossary: Map<String, String>,
        onRetry: (attempt: Int) -> Unit,
    ): List<String> {
        return withContext(Dispatchers.IO) {
            val options = TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.ENGLISH)
                .setTargetLanguage(TranslateLanguage.PORTUGUESE)
                .build()
            Translation.getClient(options).use { translator ->
                Tasks.await(translator.downloadModelIfNeeded(DownloadConditions.Builder().build()))
                texts.map { Tasks.await(translator.translate(it)) }
            }
        }
    }
}
