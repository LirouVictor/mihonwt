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
        return listOf(
            Preference.PreferenceItem.ListPreference(
                preference = translationPreferences.service,
                entries = mapOf(
                    TranslationService.GEMINI to "Gemini",
                    TranslationService.OPENAI_COMPATIBLE to stringResource(WTMR.strings.translation_service_openai),
                ),
                title = stringResource(WTMR.strings.pref_translation_service),
            ),
            Preference.PreferenceItem.EditTextPreference(
                preference = translationPreferences.geminiApiKey,
                title = stringResource(WTMR.strings.pref_gemini_api_key),
                subtitle = stringResource(WTMR.strings.pref_gemini_api_key_summary),
                visible = service == TranslationService.GEMINI,
            ),
            Preference.PreferenceItem.EditTextPreference(
                preference = translationPreferences.geminiModel,
                title = stringResource(WTMR.strings.pref_gemini_model),
                visible = service == TranslationService.GEMINI,
            ),
            Preference.PreferenceItem.EditTextPreference(
                preference = translationPreferences.openAiBaseUrl,
                title = stringResource(WTMR.strings.pref_openai_base_url),
                visible = service == TranslationService.OPENAI_COMPATIBLE,
            ),
            Preference.PreferenceItem.EditTextPreference(
                preference = translationPreferences.openAiApiKey,
                title = stringResource(WTMR.strings.pref_openai_api_key),
                subtitle = stringResource(WTMR.strings.pref_gemini_api_key_summary),
                visible = service == TranslationService.OPENAI_COMPATIBLE,
            ),
            Preference.PreferenceItem.EditTextPreference(
                preference = translationPreferences.openAiModel,
                title = stringResource(WTMR.strings.pref_openai_model),
                visible = service == TranslationService.OPENAI_COMPATIBLE,
            ),
        )
    }
}
