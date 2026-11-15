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

    /**
     * Whether a model that reads page images also finds the text on them, rather than translating the text OCR
     * grouped, which can't tell lettering from the artwork or touching bubbles apart.
     */
    val findTextOnImages: Preference<Boolean> = preferenceStore.getBoolean("translation_find_text_on_images", true)

    /** Whether OCR text is grouped by the bubbles an on-device detector finds, rather than by its layout alone. */
    val detectBubbles: Preference<Boolean> = preferenceStore.getBoolean("translation_detect_bubbles", true)

    val translateOnDownload: Preference<Boolean> = preferenceStore.getBoolean("translation_on_download", false)

    val showTranslations: Preference<Boolean> = preferenceStore.getBoolean("translation_show_in_reader", true)

    /** Whether the reader keeps the original lettering and shows a bubble's translation only when it is tapped. */
    val tapToReveal: Preference<Boolean> = preferenceStore.getBoolean("translation_tap_to_reveal", false)
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
