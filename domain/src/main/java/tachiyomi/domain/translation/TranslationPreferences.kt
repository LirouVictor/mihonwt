package tachiyomi.domain.translation

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

@Inject
@SingleIn(AppScope::class)
class TranslationPreferences(
    preferenceStore: PreferenceStore,
) {

    // Private so the key stays out of backups
    val geminiApiKey: Preference<String> = preferenceStore.getString(
        Preference.privateKey("translation_gemini_api_key"),
        "",
    )

    val geminiModel: Preference<String> = preferenceStore.getString("translation_gemini_model", "gemini-3.8-flash")
}
