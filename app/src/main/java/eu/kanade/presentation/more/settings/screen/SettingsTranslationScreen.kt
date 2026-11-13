package eu.kanade.presentation.more.settings.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import eu.kanade.presentation.more.settings.Preference
import mihon.app.di.appGraph
import tachiyomi.i18n.wt.WTMR
import tachiyomi.presentation.core.i18n.stringResource

object SettingsTranslationScreen : SearchableSettings {

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = WTMR.strings.pref_category_translation

    @Composable
    override fun getPreferences(): List<Preference> {
        val context = LocalContext.current
        val translationPreferences = remember { context.appGraph.translationPreferences }
        return listOf(
            Preference.PreferenceItem.EditTextPreference(
                preference = translationPreferences.geminiApiKey,
                title = stringResource(WTMR.strings.pref_gemini_api_key),
                subtitle = stringResource(WTMR.strings.pref_gemini_api_key_summary),
            ),
            Preference.PreferenceItem.EditTextPreference(
                preference = translationPreferences.geminiModel,
                title = stringResource(WTMR.strings.pref_gemini_model),
            ),
        )
    }
}
