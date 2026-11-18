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
import tachiyomi.domain.translation.BubbleFinder
import tachiyomi.domain.translation.OcrEngine
import tachiyomi.domain.translation.TargetLanguage
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
        val bubbleFinder by translationPreferences.bubbleFinder.collectAsState()
        val geminiTranslates = service == TranslationService.GEMINI

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
                    val language = translationPreferences.targetLanguage.get()
                    (PageTranslator.create(picked, apiKey, "", customBaseUrl, language) as LanguageModelTranslator)
                        .models()
                },
                onConfirm = modelPref::set,
                onDismissRequest = { pickingModelFor = null },
            )
        }

        return listOf(
            Preference.PreferenceGroup(
                title = stringResource(WTMR.strings.pref_group_reading),
                preferenceItems = listOf(
                    Preference.PreferenceItem.ListPreference(
                        preference = translationPreferences.ocrEngine,
                        entries = mapOf(
                            OcrEngine.ML_KIT to stringResource(WTMR.strings.ocr_engine_ml_kit),
                            OcrEngine.PP_OCR to stringResource(WTMR.strings.ocr_engine_pp_ocr),
                            OcrEngine.BABERU to stringResource(WTMR.strings.ocr_engine_baberu),
                        ),
                        title = stringResource(WTMR.strings.pref_ocr_engine),
                        subtitleProvider = { engine, _ ->
                            when (engine) {
                                OcrEngine.ML_KIT -> stringResource(WTMR.strings.ocr_engine_ml_kit_summary)
                                OcrEngine.PP_OCR -> stringResource(WTMR.strings.ocr_engine_pp_ocr_summary)
                                OcrEngine.BABERU -> stringResource(WTMR.strings.ocr_engine_baberu_summary)
                            }
                        },
                    ),
                    Preference.PreferenceItem.ListPreference(
                        preference = translationPreferences.bubbleFinder,
                        entries = buildMap {
                            put(BubbleFinder.DETECTOR, stringResource(WTMR.strings.bubble_finder_detector))
                            // Only Gemini finds bubbles, and only while it translates
                            if (geminiTranslates || bubbleFinder == BubbleFinder.GEMINI) {
                                put(BubbleFinder.GEMINI, stringResource(WTMR.strings.bubble_finder_gemini))
                            }
                            put(BubbleFinder.OCR, stringResource(WTMR.strings.bubble_finder_ocr))
                        },
                        title = stringResource(WTMR.strings.pref_bubble_finder),
                        subtitleProvider = { finder, _ ->
                            when {
                                finder == BubbleFinder.GEMINI && geminiTranslates ->
                                    stringResource(WTMR.strings.bubble_finder_gemini_summary)
                                finder == BubbleFinder.GEMINI ->
                                    stringResource(WTMR.strings.bubble_finder_gemini_unused_summary)
                                finder == BubbleFinder.OCR -> stringResource(WTMR.strings.bubble_finder_ocr_summary)
                                else -> stringResource(WTMR.strings.bubble_finder_detector_summary)
                            }
                        },
                    ),
                    // Gemini translating already reads the pages, so it would only correct what it then translates
                    Preference.PreferenceItem.SwitchPreference(
                        preference = translationPreferences.correctTextWithGemini,
                        title = stringResource(WTMR.strings.pref_correct_text_with_gemini),
                        subtitle = stringResource(WTMR.strings.pref_correct_text_with_gemini_summary),
                        visible = !geminiTranslates,
                    ),
                ) + serviceItems(
                    translationPreferences,
                    TranslationService.GEMINI,
                    visible = correctsText && !geminiTranslates,
                    onPickModel = { pickingModelFor = it },
                ),
            ),
            Preference.PreferenceGroup(
                title = stringResource(WTMR.strings.pref_group_translation),
                preferenceItems = listOf(
                    Preference.PreferenceItem.ListPreference(
                        preference = translationPreferences.targetLanguage,
                        entries = TargetLanguage.entries.associateWith { it.displayName },
                        title = stringResource(WTMR.strings.pref_target_language),
                        subtitle = stringResource(WTMR.strings.pref_target_language_summary),
                    ),
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
