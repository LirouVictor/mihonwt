package eu.kanade.presentation.more.settings.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.translation.data.ApiKeyCipher
import eu.kanade.translation.presentation.ModelPickerDialog
import eu.kanade.translation.translator.LanguageModelTranslator
import eu.kanade.translation.translator.PageTranslator
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
        val apiKeyPref = remember(service) { ApiKeyCipher.wrap(translationPreferences.apiKey(service)) }
        val apiKey by apiKeyPref.collectAsState()
        val modelPref = remember(service) { translationPreferences.model(service) }
        val model by modelPref.collectAsState()

        var isModelPickerShown by remember { mutableStateOf(false) }
        if (isModelPickerShown) {
            ModelPickerDialog(
                title = stringResource(WTMR.strings.pref_model),
                value = model,
                loadModels = {
                    val customBaseUrl = translationPreferences.customBaseUrl.get()
                    (PageTranslator.create(service, apiKey, "", customBaseUrl) as LanguageModelTranslator).models()
                },
                onConfirm = modelPref::set,
                onDismissRequest = { isModelPickerShown = false },
            )
        }

        return listOf(
            Preference.PreferenceItem.ListPreference(
                preference = translationPreferences.service,
                entries = TranslationService.entries.associateWith {
                    when (it) {
                        TranslationService.GEMINI -> "Gemini"
                        TranslationService.GROQ -> "Groq"
                        TranslationService.OPENROUTER -> "OpenRouter"
                        TranslationService.CEREBRAS -> "Cerebras"
                        TranslationService.DEEPL -> "DeepL"
                        TranslationService.OFFLINE -> stringResource(WTMR.strings.translation_service_offline)
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
                visible = service.needsApiKey,
            ),
            Preference.PreferenceItem.TextPreference(
                title = stringResource(WTMR.strings.pref_model),
                subtitle = model.ifBlank { stringResource(WTMR.strings.pref_model_choose) },
                onClick = { isModelPickerShown = true },
                visible = service.hasModels,
            ),
            Preference.PreferenceItem.SwitchPreference(
                preference = translationPreferences.translateOnDownload,
                title = stringResource(WTMR.strings.pref_translate_on_download),
                subtitle = stringResource(WTMR.strings.pref_translate_on_download_summary),
            ),
        )
    }
}
