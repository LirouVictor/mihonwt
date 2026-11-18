package tachiyomi.domain.translation

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.getEnum

@Inject
@SingleIn(AppScope::class)
class TranslationPreferences(
    private val preferenceStore: PreferenceStore,
) {

    val service: Preference<TranslationService> = preferenceStore.getEnum(
        "translation_service",
        TranslationService.GEMINI,
    )

    /**
     * Each service keeps its own key, so switching between them doesn't lose it. Stored encrypted by the app, and
     * private to stay out of backups.
     */
    fun apiKey(service: TranslationService): Preference<String> = preferenceStore.getString(
        Preference.privateKey("translation_${service.key}_api_key"),
        "",
    )

    fun model(service: TranslationService): Preference<String> = preferenceStore.getString(
        "translation_${service.key}_model",
        service.defaultModel,
    )

    /** Address of the API used by [TranslationService.CUSTOM]. */
    val customBaseUrl: Preference<String> = preferenceStore.getString("translation_custom_base_url", "")

    /** The language chapters are translated into. */
    val targetLanguage: Preference<TargetLanguage> = preferenceStore.getEnum(
        "translation_target_language",
        TargetLanguage.PT_BR,
    )

    /** What reads the text on the pages. */
    val ocrEngine: Preference<OcrEngine> = preferenceStore.getEnum("translation_ocr_engine", OcrEngine.ML_KIT)

    // Replaces two switches, whether the model finds the text and whether the detector runs, starting from their values
    val bubbleFinder: Preference<BubbleFinder> = preferenceStore.getEnum(
        "translation_bubble_finder",
        when {
            preferenceStore.getBoolean("translation_find_text_on_images", false).get() -> BubbleFinder.GEMINI
            !preferenceStore.getBoolean("translation_detect_bubbles", true).get() -> BubbleFinder.OCR
            else -> BubbleFinder.DETECTOR
        },
    )

    /**
     * Whether Gemini, given the page images, fixes the text OCR read before a translator that can't see the pages
     * translates it.
     */
    val correctTextWithGemini: Preference<Boolean> =
        preferenceStore.getBoolean("translation_correct_text_with_gemini", false)

    val translateOnDownload: Preference<Boolean> = preferenceStore.getBoolean("translation_on_download", false)

    val showTranslations: Preference<Boolean> = preferenceStore.getBoolean("translation_show_in_reader", true)

    /** Whether the reader keeps the original lettering and shows a bubble's translation only when it is tapped. */
    val tapToReveal: Preference<Boolean> = preferenceStore.getBoolean("translation_tap_to_reveal", false)
}

/** What reads the text on the pages. */
enum class OcrEngine {
    /** Google's ML Kit, made for photos and documents. */
    ML_KIT,

    /** PaddleOCR's PP-OCRv6 small, which reads comic lettering better but is slower. */
    PP_OCR,

    /** ML Kit's lines with the text of each bubble read again by Baberu, a model trained on manga lettering. */
    BABERU,
}

/** What groups the text OCR read into bubbles. */
enum class BubbleFinder {
    /** An on-device detector, which also leaves out sound effects. */
    DETECTOR,

    /** Gemini, from the page images, when it is the translator. Otherwise the detector does. */
    GEMINI,

    /** OCR's own layout of lines. */
    OCR,
}

/**
 * A language chapters are translated into, with its [tag] (BCP 47), its own [displayName], its [englishName] for
 * prompts and its code in DeepL's API.
 */
enum class TargetLanguage(
    val tag: String,
    val displayName: String,
    val englishName: String,
    val deepLCode: String,
) {
    PT_BR("pt-BR", "Português (Brasil)", "Brazilian Portuguese", "PT-BR"),
    ES_419("es-419", "Español (Latinoamérica)", "Latin American Spanish", "ES-419"),
    ES_ES("es-ES", "Español (España)", "European Spanish", "ES"),
    FR("fr", "Français", "French", "FR"),
    IT("it", "Italiano", "Italian", "IT"),
    DE("de", "Deutsch", "German", "DE"),
}

/**
 * A translation service: a language model API, which all but [GEMINI] reach through OpenAI's chat completions, or a
 * machine translator, which has no models to choose from.
 */
enum class TranslationService(
    val baseUrl: String?,
    val defaultModel: String = "",
    val needsApiKey: Boolean = true,
    val hasModels: Boolean = true,
) {
    GEMINI(baseUrl = null, defaultModel = "gemini-3.8-flash"),
    GROQ(baseUrl = "https://api.groq.com/openai/v1"),
    OPENROUTER(baseUrl = "https://openrouter.ai/api/v1"),
    CEREBRAS(baseUrl = "https://api.cerebras.ai/v1"),
    DEEPL(baseUrl = null, hasModels = false),

    /** ML Kit's on-device translator. */
    OFFLINE(baseUrl = null, needsApiKey = false, hasModels = false),

    /** Any other OpenAI-compatible API, at [TranslationPreferences.customBaseUrl]. */
    CUSTOM(baseUrl = null),
    ;

    val key = name.lowercase()
}
