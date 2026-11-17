package eu.kanade.translation.translator

import tachiyomi.domain.translation.TargetLanguage

/** How translations into this language should sound, beyond natural colloquial speech, for prompts and DeepL. */
internal val TargetLanguage.styleNotes: List<String>
    get() = when (this) {
        TargetLanguage.PT_BR -> listOf(
            "Use Brazilian spelling and vocabulary, never European Portuguese forms.",
            "Avoid false cognates: \"amusing\" is \"divertido\", never \"amusante\".",
        )
        TargetLanguage.ES_419 -> listOf(
            "Use neutral Latin American Spanish, with \"ustedes\" and never \"vosotros\".",
        )
        TargetLanguage.ES_ES -> listOf("Use the Spanish of Spain.")
        TargetLanguage.FR, TargetLanguage.IT, TargetLanguage.DE -> emptyList()
    }

/** [styleNotes] as one text, each after a space, to follow a sentence of a prompt. */
internal val TargetLanguage.styleNotesText: String
    get() = styleNotes.joinToString("") { " $it" }

/**
 * The translation of the prompt's example blocks "IF IT", "ISN'T MISS VAMOLA!", "COULD I GET YOUR HELP?" and the noise
 * "english3", so the model sees the language and the tone it should answer in.
 */
internal val TargetLanguage.example: List<String>
    get() = when (this) {
        TargetLanguage.PT_BR -> listOf("OLHA SÓ", "SE NÃO É A SRTA. VAMOLA!", "PODERIA ME AJUDAR?", "portuguese3")
        TargetLanguage.ES_419 -> listOf("VAYA, VAYA", "¡SI ES LA SEÑORITA VAMOLA!", "¿ME PUEDES AYUDAR?", "spanish3")
        TargetLanguage.ES_ES -> listOf("ANDA", "¡SI ES LA SEÑORITA VAMOLA!", "¿ME ECHAS UNA MANO?", "spanish3")
        TargetLanguage.FR -> listOf("TIENS, TIENS", "MAIS C'EST MLLE VAMOLA !", "TU POURRAIS M'AIDER ?", "french3")
        TargetLanguage.IT -> listOf(
            "GUARDA UN PO'",
            "SE NON È LA SIGNORINA VAMOLA!",
            "MI DARESTI UNA MANO?",
            "italian3",
        )
        TargetLanguage.DE -> listOf(
            "SIEH MAL AN",
            "WENN DAS NICHT FRAU VAMOLA IST!",
            "KANNST DU MIR HELFEN?",
            "german3",
        )
    }
