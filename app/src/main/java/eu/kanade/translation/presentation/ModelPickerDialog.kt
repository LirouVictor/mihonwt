package eu.kanade.translation.presentation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import tachiyomi.i18n.MR
import tachiyomi.i18n.wt.WTMR
import tachiyomi.presentation.core.components.ScrollbarLazyColumn
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Lets the user pick one of the models [loadModels] returns, or type a name when the list can't be loaded or
 * misses the model. The text field filters the list.
 */
@Composable
fun ModelPickerDialog(
    title: String,
    value: String,
    loadModels: suspend () -> List<String>,
    onConfirm: (String) -> Unit,
    onDismissRequest: () -> Unit,
) {
    var text by rememberSaveable { mutableStateOf("") }
    var models by remember { mutableStateOf<Result<List<String>>?>(null) }
    LaunchedEffect(Unit) { models = runCatching { loadModels() } }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(text = title) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(text = stringResource(WTMR.strings.pref_model_search)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                val result = models
                when {
                    result == null -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 16.dp))
                    result.isFailure -> Text(
                        text = stringResource(
                            WTMR.strings.pref_model_list_error,
                            result.exceptionOrNull()?.message.orEmpty(),
                        ),
                        modifier = Modifier.padding(top = 16.dp),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    else -> {
                        val filtered = result.getOrThrow().filter { it.contains(text.trim(), ignoreCase = true) }
                        Box(modifier = Modifier.weight(1f, fill = false).padding(top = 8.dp)) {
                            ScrollbarLazyColumn(state = rememberLazyListState()) {
                                items(filtered) { model ->
                                    Text(
                                        text = model,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                onConfirm(model)
                                                onDismissRequest()
                                            }
                                            .padding(vertical = 12.dp),
                                        color = if (model == value) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurface
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = text.isNotBlank() && text.trim() != value,
                onClick = {
                    onConfirm(text.trim())
                    onDismissRequest()
                },
            ) {
                Text(text = stringResource(MR.strings.action_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(MR.strings.action_cancel))
            }
        },
    )
}
