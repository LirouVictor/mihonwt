package eu.kanade.presentation.manga.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import eu.kanade.domain.chapter.interactor.ScanlatorCoverage
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.CheckBoxOutlineBlank
import mihon.icons.materialsymbols.roundedfilled.CheckBox
import tachiyomi.i18n.MR
import tachiyomi.i18n.wt.WTMR
import tachiyomi.presentation.core.components.material.TextButton
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun ScanlatorFilterDialog(
    coverage: ScanlatorCoverage,
    excludedScanlators: Set<String>,
    onDismissRequest: () -> Unit,
    onConfirm: (Set<String>) -> Unit,
) {
    val availableScanlators = coverage.counts.keys
    val sortedAvailableScanlators = remember(coverage) {
        availableScanlators.sortedWith(
            compareByDescending<String> { coverage.counts.getValue(it) }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it },
        )
    }
    val mutableExcludedScanlators = remember(excludedScanlators) { excludedScanlators.toMutableStateList() }
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(text = stringResource(WTMR.strings.scanlator_filter_title)) },
        text = textFunc@{
            if (sortedAvailableScanlators.isEmpty()) {
                Text(text = stringResource(MR.strings.no_scanlators_found))
                return@textFunc
            }
            Box {
                val state = rememberLazyListState()
                LazyColumn(state = state) {
                    if (coverage.hasUnidentifiedScanlator) {
                        item {
                            Text(
                                text = stringResource(WTMR.strings.scanlator_filter_unidentified),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    items(sortedAvailableScanlators, key = { it }) { scanlator ->
                        val isExcluded = scanlator in mutableExcludedScanlators
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(MaterialTheme.shapes.small)
                                    .toggleable(
                                        value = !isExcluded,
                                        role = Role.Checkbox,
                                        onValueChange = { included ->
                                            if (included) {
                                                mutableExcludedScanlators.remove(scanlator)
                                            } else {
                                                mutableExcludedScanlators.add(scanlator)
                                            }
                                        },
                                    )
                                    .minimumInteractiveComponentSize()
                                    .padding(horizontal = MaterialTheme.padding.small),
                            ) {
                                Icon(
                                    imageVector = if (!isExcluded) {
                                        MaterialSymbols.RoundedFilled.CheckBox
                                    } else {
                                        MaterialSymbols.Rounded.CheckBoxOutlineBlank
                                    },
                                    tint = if (!isExcluded) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        LocalContentColor.current
                                    },
                                    contentDescription = null,
                                )
                                Column(modifier = Modifier.padding(start = 12.dp)) {
                                    Text(text = scanlator, style = MaterialTheme.typography.bodyMedium)
                                    if (coverage.numberedChapters > 0) {
                                        Text(
                                            text = stringResource(
                                                WTMR.strings.scanlator_chapter_coverage,
                                                coverage.counts.getValue(scanlator),
                                                coverage.numberedChapters,
                                            ),
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                }
                            }
                            if (sortedAvailableScanlators.size > 1) {
                                TextButton(
                                    onClick = {
                                        mutableExcludedScanlators.clear()
                                        mutableExcludedScanlators.addAll(availableScanlators - scanlator)
                                    },
                                ) {
                                    Text(text = stringResource(WTMR.strings.scanlator_keep_only))
                                }
                            }
                        }
                    }
                }
                if (state.canScrollBackward) HorizontalDivider(modifier = Modifier.align(Alignment.TopCenter))
                if (state.canScrollForward) HorizontalDivider(modifier = Modifier.align(Alignment.BottomCenter))
            }
        },
        properties = DialogProperties(
            usePlatformDefaultWidth = true,
        ),
        confirmButton = {
            if (sortedAvailableScanlators.isEmpty()) {
                TextButton(onClick = onDismissRequest) {
                    Text(text = stringResource(MR.strings.action_cancel))
                }
            } else {
                FlowRow {
                    if (mutableExcludedScanlators.isEmpty()) {
                        TextButton(onClick = { mutableExcludedScanlators.addAll(availableScanlators) }) {
                            Text(text = stringResource(MR.strings.action_select_all))
                        }
                    } else {
                        TextButton(onClick = mutableExcludedScanlators::clear) {
                            Text(text = stringResource(MR.strings.action_reset))
                        }
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismissRequest) {
                        Text(text = stringResource(MR.strings.action_cancel))
                    }
                    TextButton(
                        onClick = {
                            onConfirm(mutableExcludedScanlators.toSet())
                            onDismissRequest()
                        },
                    ) {
                        Text(text = stringResource(MR.strings.action_ok))
                    }
                }
            }
        },
    )
}
