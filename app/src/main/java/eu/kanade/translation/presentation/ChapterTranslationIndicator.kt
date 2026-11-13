package eu.kanade.translation.presentation

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.DropdownMenu
import eu.kanade.translation.model.Translation
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Error
import mihon.icons.materialsymbols.rounded.Translate
import tachiyomi.i18n.MR
import tachiyomi.i18n.wt.WTMR
import tachiyomi.presentation.core.components.material.IconButtonTokens
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.secondaryItemAlpha

enum class ChapterTranslationAction {
    START,
    CANCEL,
    DELETE,
}

/** Translate button of a chapter row, showing whether the chapter is queued, translating, translated or failed. */
@Composable
fun ChapterTranslationIndicator(
    enabled: Boolean,
    state: Translation.State,
    error: String?,
    onClick: (ChapterTranslationAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    var isMenuExpanded by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .size(IconButtonTokens.StateLayerSize)
            .combinedClickable(
                enabled = enabled,
                interactionSource = null,
                indication = ripple(bounded = false, radius = IconButtonTokens.StateLayerSize / 2),
                role = Role.Button,
                onClick = {
                    when (state) {
                        Translation.State.NOT_TRANSLATED -> onClick(ChapterTranslationAction.START)
                        else -> isMenuExpanded = true
                    }
                },
            )
            .then(if (state == Translation.State.NOT_TRANSLATED) Modifier.secondaryItemAlpha() else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        when (state) {
            Translation.State.NOT_TRANSLATED -> TranslateIcon(
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                contentDescription = stringResource(WTMR.strings.action_translate_chapter),
            )
            Translation.State.QUEUE, Translation.State.TRANSLATING -> CircularWavyProgressIndicator(
                modifier = Modifier.size(IndicatorSize),
            )
            Translation.State.TRANSLATED -> TranslateIcon(
                tint = MaterialTheme.colorScheme.primary,
                contentDescription = null,
            )
            Translation.State.ERROR -> Icon(
                imageVector = MaterialSymbols.Rounded.Error,
                contentDescription = stringResource(MR.strings.chapter_error),
                modifier = Modifier.size(IndicatorSize),
                tint = MaterialTheme.colorScheme.error,
            )
        }
        DropdownMenu(expanded = isMenuExpanded, onDismissRequest = { isMenuExpanded = false }) {
            val actions = when (state) {
                Translation.State.TRANSLATED -> listOf(
                    stringResource(WTMR.strings.action_delete_translation) to ChapterTranslationAction.DELETE,
                )
                Translation.State.ERROR -> listOf(
                    stringResource(MR.strings.action_retry) to ChapterTranslationAction.START,
                    stringResource(MR.strings.action_remove) to ChapterTranslationAction.CANCEL,
                )
                else -> listOf(stringResource(MR.strings.action_cancel) to ChapterTranslationAction.CANCEL)
            }
            if (state == Translation.State.ERROR && error != null) {
                Text(
                    text = error,
                    modifier = Modifier
                        .widthIn(max = 280.dp)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            actions.forEach { (label, action) ->
                DropdownMenuItem(
                    text = { Text(text = label) },
                    onClick = {
                        onClick(action)
                        isMenuExpanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun TranslateIcon(tint: Color, contentDescription: String?) {
    Icon(
        imageVector = MaterialSymbols.Rounded.Translate,
        contentDescription = contentDescription,
        modifier = Modifier.size(IndicatorSize),
        tint = tint,
    )
}

private val IndicatorSize = 26.dp
