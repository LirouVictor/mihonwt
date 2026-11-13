package eu.kanade.translation.presentation

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
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
    onClick: (ChapterTranslationAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    var isMenuExpanded by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    Box(
        modifier = modifier
            .size(IconButtonTokens.StateLayerSize)
            .combinedClickable(
                enabled = enabled,
                interactionSource = null,
                indication = ripple(bounded = false, radius = IconButtonTokens.StateLayerSize / 2),
                role = Role.Button,
                // Tapping a failed chapter retries it, so long pressing is the way to clear it from the queue
                onLongClick = if (state == Translation.State.ERROR) {
                    {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onClick(ChapterTranslationAction.CANCEL)
                    }
                } else {
                    null
                },
                onClick = {
                    when (state) {
                        Translation.State.NOT_TRANSLATED, Translation.State.ERROR ->
                            onClick(ChapterTranslationAction.START)
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
            val (label, action) = if (state == Translation.State.TRANSLATED) {
                stringResource(WTMR.strings.action_delete_translation) to ChapterTranslationAction.DELETE
            } else {
                stringResource(MR.strings.action_cancel) to ChapterTranslationAction.CANCEL
            }
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
