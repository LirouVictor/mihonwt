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
    preferenceStore: PreferenceStore,
) {

    val service: Preference<TranslationService> = preferenceStore.getEnum(
        "translation_service",
        TranslationService.GEMINI,
    )

    // Private so the keys stay out of backups
    val geminiApiKey: Preference<String> = preferenceStore.getString(
        Preference.privateKey("translation_gemini_api_key"),
        "",
    )

    val geminiModel: Preference<String> = preferenceStore.getString("translation_gemini_model", "gemini-3.8-flash")

    val openAiBaseUrl: Preference<String> = preferenceStore.getString(
        "translation_openai_base_url",
        "https://api.groq.com/openai/v1",
    )

    val openAiApiKey: Preference<String> = preferenceStore.getString(
        Preference.privateKey("translation_openai_api_key"),
        "",
    )

    val openAiModel: Preference<String> = preferenceStore.getString("translation_openai_model", "")

    val showTranslations: Preference<Boolean> = preferenceStore.getBoolean("translation_show_in_reader", true)
}

enum class TranslationService {
    GEMINI,

    /** Any API following OpenAI's chat completions, such as Groq or OpenRouter. */
    OPENAI_COMPATIBLE,
}
