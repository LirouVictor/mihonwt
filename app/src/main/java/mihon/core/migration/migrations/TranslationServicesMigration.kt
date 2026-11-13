package mihon.core.migration.migrations

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import mihon.core.migration.Migration
import mihon.core.migration.MigrationContext
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.translation.TranslationPreferences
import tachiyomi.domain.translation.TranslationService

/** Moves the single OpenAI-compatible setup of MihonWT 0.0.8 to the service its address belongs to. */
@Inject
@ContributesIntoSet(AppScope::class)
class TranslationServicesMigration(
    private val preferenceStore: PreferenceStore,
    private val translationPreferences: TranslationPreferences,
) : Migration {
    override val version: Float = 9f

    override suspend fun invoke(migrationContext: MigrationContext): Boolean {
        val oldBaseUrl = preferenceStore.getString("translation_openai_base_url", "https://api.groq.com/openai/v1")
        val oldApiKey = preferenceStore.getString(Preference.privateKey("translation_openai_api_key"), "")
        val oldModel = preferenceStore.getString("translation_openai_model", "")
        val oldService = preferenceStore.getString("translation_service", "")

        val baseUrl = oldBaseUrl.get().trimEnd('/')
        val service = TranslationService.entries.firstOrNull { it.baseUrl == baseUrl } ?: TranslationService.CUSTOM
        if (service == TranslationService.CUSTOM) translationPreferences.customBaseUrl.set(baseUrl)
        if (oldApiKey.isSet()) translationPreferences.apiKey(service).set(oldApiKey.get())
        if (oldModel.isSet()) translationPreferences.model(service).set(oldModel.get())
        if (oldService.get() == "OPENAI_COMPATIBLE") translationPreferences.service.set(service)

        listOf(oldBaseUrl, oldApiKey, oldModel).forEach { it.delete() }
        return true
    }
}
