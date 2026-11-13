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

    val showTranslations: Preference<Boolean> = preferenceStore.getBoolean("translation_show_in_reader", true)
}

/** A language model API. All but [GEMINI] follow OpenAI's chat completions. */
enum class TranslationService(val baseUrl: String?, val defaultModel: String = "") {
    GEMINI(baseUrl = null, defaultModel = "gemini-3.8-flash"),
    GROQ(baseUrl = "https://api.groq.com/openai/v1"),
    OPENROUTER(baseUrl = "https://openrouter.ai/api/v1"),
    CEREBRAS(baseUrl = "https://api.cerebras.ai/v1"),

    /** Any other OpenAI-compatible API, at [TranslationPreferences.customBaseUrl]. */
    CUSTOM(baseUrl = null),
    ;

    val key = name.lowercase()
}
