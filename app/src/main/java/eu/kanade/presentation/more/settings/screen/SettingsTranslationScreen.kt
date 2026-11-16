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
import tachiyomi.domain.translation.TranslationPreferences
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
        val correctsText by translationPreferences.correctTextWithGemini.collectAsState()

        var pickingModelFor by remember { mutableStateOf<TranslationService?>(null) }
        pickingModelFor?.let { picked ->
            val modelPref = remember(picked) { translationPreferences.model(picked) }
            val model by modelPref.collectAsState()
            ModelPickerDialog(
                title = stringResource(WTMR.strings.pref_model),
                value = model,
                loadModels = {
                    val apiKey = ApiKeyCipher.decrypt(translationPreferences.apiKey(picked).get())
                    val customBaseUrl = translationPreferences.customBaseUrl.get()
                    (PageTranslator.create(picked, apiKey, "", customBaseUrl) as LanguageModelTranslator).models()
                },
                onConfirm = modelPref::set,
                onDismissRequest = { pickingModelFor = null },
            )
        }

        return listOf(
            Preference.PreferenceGroup(
                title = stringResource(WTMR.strings.pref_group_reading),
                preferenceItems = listOf(
                    Preference.PreferenceItem.SwitchPreference(
                        preference = translationPreferences.detectBubbles,
                        title = stringResource(WTMR.strings.pref_detect_bubbles),
                        subtitle = stringResource(WTMR.strings.pref_detect_bubbles_summary),
                    ),
                    Preference.PreferenceItem.SwitchPreference(
                        preference = translationPreferences.findTextOnImages,
                        title = stringResource(WTMR.strings.pref_find_text_on_images),
                        subtitle = stringResource(WTMR.strings.pref_find_text_on_images_summary),
                        visible = service == TranslationService.GEMINI,
                    ),
                ),
            ),
            Preference.PreferenceGroup(
                title = stringResource(WTMR.strings.pref_group_review),
                preferenceItems = listOf(
                    Preference.PreferenceItem.SwitchPreference(
                        preference = translationPreferences.correctTextWithGemini,
                        title = stringResource(WTMR.strings.pref_correct_text_with_gemini),
                        subtitle = if (service == TranslationService.GEMINI) {
                            stringResource(WTMR.strings.pref_correct_text_with_gemini_skipped)
                        } else {
                            stringResource(WTMR.strings.pref_correct_text_with_gemini_summary)
                        },
                    ),
                ) + serviceItems(
                    translationPreferences,
                    TranslationService.GEMINI,
                    visible = correctsText && service != TranslationService.GEMINI,
                    onPickModel = { pickingModelFor = it },
                ),
            ),
            Preference.PreferenceGroup(
                title = stringResource(WTMR.strings.pref_group_translation),
                preferenceItems = listOf(
                    Preference.PreferenceItem.ListPreference(
                        preference = translationPreferences.service,
                        entries = TranslationService.entries.associateWith {
                            when (it) {
                                TranslationService.GEMINI -> "Gemini"
                                TranslationService.GROQ -> "Groq"
                                TranslationService.OPENROUTER -> "OpenRouter"
                                TranslationService.CEREBRAS -> "Cerebras"
                                TranslationService.DEEPL -> "DeepL"
                                TranslationService.OFFLINE ->
                                    stringResource(WTMR.strings.translation_service_offline)
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
                ) +
                    serviceItems(translationPreferences, service, visible = true, onPickModel = {
                        pickingModelFor = it
                    }),
            ),
            Preference.PreferenceGroup(
                title = stringResource(WTMR.strings.pref_group_reader),
                preferenceItems = listOf(
                    Preference.PreferenceItem.SwitchPreference(
                        preference = translationPreferences.tapToReveal,
                        title = stringResource(WTMR.strings.pref_tap_to_reveal),
                        subtitle = stringResource(WTMR.strings.pref_tap_to_reveal_summary),
                    ),
                    Preference.PreferenceItem.SwitchPreference(
                        preference = translationPreferences.translateOnDownload,
                        title = stringResource(WTMR.strings.pref_translate_on_download),
                        subtitle = stringResource(WTMR.strings.pref_translate_on_download_summary),
                    ),
                ),
            ),
        )
    }

    /** The API key and model of [service], shown only when [visible] and the service needs them. */
    @Composable
    private fun serviceItems(
        translationPreferences: TranslationPreferences,
        service: TranslationService,
        visible: Boolean,
        onPickModel: (TranslationService) -> Unit,
    ): List<Preference.PreferenceItem<out Any, out Any>> {
        val apiKeyPref = remember(service) { ApiKeyCipher.wrap(translationPreferences.apiKey(service)) }
        val apiKey by apiKeyPref.collectAsState()
        val model by remember(service) { translationPreferences.model(service) }.collectAsState()
        return listOf(
            Preference.PreferenceItem.EditTextPreference(
                preference = apiKeyPref,
                title = stringResource(WTMR.strings.pref_api_key),
                subtitle = if (apiKey.isBlank()) {
                    stringResource(WTMR.strings.pref_api_key_missing)
                } else {
                    stringResource(WTMR.strings.pref_api_key_summary)
                },
                visible = visible && service.needsApiKey,
            ),
            Preference.PreferenceItem.TextPreference(
                title = stringResource(WTMR.strings.pref_model),
                subtitle = model.ifBlank { stringResource(WTMR.strings.pref_model_choose) },
                onClick = { onPickModel(service) },
                visible = visible && service.hasModels,
            ),
        )
    }
}
