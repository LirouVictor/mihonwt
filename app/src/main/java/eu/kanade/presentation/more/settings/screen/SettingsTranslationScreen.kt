package eu.kanade.presentation.more.settings.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import eu.kanade.presentation.more.settings.Preference
import mihon.app.di.appGraph
import tachiyomi.domain.translation.TranslationService
import tachiyomi.i18n.wt.WTMR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState

object SettingsTranslationScreen : SearchableSettings {

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = WTMR.strings.pref_category_translation

    @Composable
    override fun getPreferences(): List<Preference> {
        val context = LocalContext.current
        val translationPreferences = remember { context.appGraph.translationPreferences }
        val service by translationPreferences.service.collectAsState()
        val apiKeyPref = remember(service) { translationPreferences.apiKey(service) }
        val apiKey by apiKeyPref.collectAsState()
        return listOf(
            Preference.PreferenceItem.ListPreference(
                preference = translationPreferences.service,
                entries = TranslationService.entries.associateWith {
                    when (it) {
                        TranslationService.GEMINI -> "Gemini"
                        TranslationService.GROQ -> "Groq"
                        TranslationService.OPENROUTER -> "OpenRouter"
                        TranslationService.CEREBRAS -> "Cerebras"
                        TranslationService.CUSTOM -> stringResource(WTMR.strings.translation_service_custom)
                    }
                },
                title = stringResource(WTMR.strings.pref_translation_service),
            ),
            Preference.PreferenceItem.EditTextPreference(
                preference = translationPreferences.customBaseUrl,
                title = stringResource(WTMR.strings.pref_custom_base_url),
                visible = service == TranslationService.CUSTOM,
            ),
            Preference.PreferenceItem.EditTextPreference(
                preference = apiKeyPref,
                title = stringResource(WTMR.strings.pref_api_key),
                subtitle = if (apiKey.isBlank()) {
                    stringResource(WTMR.strings.pref_api_key_missing)
                } else {
                    stringResource(WTMR.strings.pref_api_key_summary)
                },
            ),
            Preference.PreferenceItem.EditTextPreference(
                preference = remember(service) { translationPreferences.model(service) },
                title = stringResource(WTMR.strings.pref_model),
            ),
        )
    }
}
