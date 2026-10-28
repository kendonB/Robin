package org.evergreenlabs.robin.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import org.evergreenlabs.robin.ui.theme.OptimalGreen
import java.util.Locale
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RemoveCircle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.evergreenlabs.robin.AppConfig
import org.evergreenlabs.robin.AppGraph
import org.evergreenlabs.robin.R
import org.evergreenlabs.robin.security.ProtectDialogWindow
import org.evergreenlabs.robin.services.FeedRefreshIntervalStore
import org.evergreenlabs.robin.services.FontScaleStore
import org.evergreenlabs.robin.xfilter.FilterSettings
import org.evergreenlabs.robin.xfilter.HomeFeedMode

/**
 * Settings for the WebView filter path.
 * Text size scales the X feed only, not this sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InfoSheet(
    onDismiss: () -> Unit,
    onSignOut: () -> Unit = {},
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val uriHandler = LocalUriHandler.current
    val minimalTwitterUrl = stringResource(R.string.about_minimal_twitter_link)
    val robinRepoUrl = stringResource(R.string.about_robin_repo_link)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        ProtectDialogWindow()
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
        ) {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.CenterStart),
            ) {
                Text(stringResource(R.string.done))
            }
            Text(
                text = stringResource(R.string.settings),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
        ) {
            Text(
                text = stringResource(R.string.user_interface),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(8.dp))
            GeneralSettingsGroup()
            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(12.dp))
            HomeFeedSection(onDismiss = onDismiss)
            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(12.dp))
            ShowTogglesSection()
            Spacer(modifier = Modifier.height(16.dp))
            HideKeywordsSection()
            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.privacy),
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(
                    if (AppConfig.aiEnabled) R.string.privacy_ai_enabled else R.string.privacy_ai_disabled,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.privacy_screen_protection),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(16.dp))
            if (AppConfig.showDebugSettings) {
                HorizontalDivider()
                Spacer(modifier = Modifier.height(12.dp))
                PerformanceBenchmarkSection()
                Spacer(modifier = Modifier.height(16.dp))
            }
            HorizontalDivider()
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.credits),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.about_minimal_twitter),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(4.dp))
            TextButton(
                onClick = { uriHandler.openUri(minimalTwitterUrl) },
                modifier = Modifier.padding(horizontal = 0.dp),
            ) {
                Text(stringResource(R.string.about_minimal_twitter_button))
            }
            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.about),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(
                    R.string.version_label,
                    AppConfig.versionName,
                    AppConfig.versionCode,
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(4.dp))
            TextButton(
                onClick = { uriHandler.openUri(robinRepoUrl) },
                modifier = Modifier.padding(horizontal = 0.dp),
            ) {
                Text(stringResource(R.string.about_robin_repo_button))
            }
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = {
                    onSignOut()
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.sign_out))
            }
        }
    }
}

private val SettingsRowMinHeight = 52.dp
private val AdjustButtonWidth = 64.dp
private val AdjustValueWidth = 56.dp

@Composable
private fun GeneralSettingsGroup() {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
            FontSizeRow()
            AutoRefreshRow()
        }
    }
}

@Composable
private fun FontSizeRow() {
    val fontScale = AppGraph.fontScale
    val scale by fontScale.scale.collectAsStateWithLifecycle()
    val percent = remember(scale) { "${(scale * 100f).toInt()}%" }
    val decreaseDesc = stringResource(R.string.decrease_text_size)
    val increaseDesc = stringResource(R.string.increase_text_size)
    val sizeDesc = stringResource(R.string.text_size_percent, percent)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = SettingsRowMinHeight),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = stringResource(R.string.text_size),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AdjustButton(
                label = "A−",
                enabled = scale > FontScaleStore.MIN + 1e-4f,
                contentDescription = decreaseDesc,
                onClick = { fontScale.bump(-FontScaleStore.STEP) },
            )
            Text(
                text = percent,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .width(AdjustValueWidth)
                    .semantics { contentDescription = sizeDesc },
            )
            AdjustButton(
                label = "A+",
                enabled = scale < FontScaleStore.MAX - 1e-4f,
                contentDescription = increaseDesc,
                onClick = { fontScale.bump(FontScaleStore.STEP) },
            )
        }
    }
}

@Composable
private fun AutoRefreshRow() {
    val store = AppGraph.feedRefreshInterval
    val seconds by store.seconds.collectAsStateWithLifecycle()
    val label = remember(seconds) { FeedRefreshIntervalStore.labelFor(seconds) }
    val decreaseDesc = stringResource(R.string.decrease_auto_refresh)
    val increaseDesc = stringResource(R.string.increase_auto_refresh)
    val intervalDesc = stringResource(R.string.auto_refresh_interval, label)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = SettingsRowMinHeight),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = stringResource(R.string.auto_refresh),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AdjustButton(
                label = "−",
                enabled = store.canDecrease,
                contentDescription = decreaseDesc,
                onClick = { store.bump(-1) },
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .width(AdjustValueWidth)
                    .semantics { contentDescription = intervalDesc },
            )
            AdjustButton(
                label = "+",
                enabled = store.canIncrease,
                contentDescription = increaseDesc,
                onClick = { store.bump(1) },
            )
        }
    }
}

@Composable
private fun AdjustButton(
    label: String,
    enabled: Boolean,
    contentDescription: String,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .width(AdjustButtonWidth)
            .height(36.dp)
            .semantics { this.contentDescription = contentDescription },
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
    ) {
        Text(label, maxLines = 1)
    }
}

@Composable
private fun HomeFeedSection(onDismiss: () -> Unit = {}) {
    val store = AppGraph.filterSettings
    val settings by store.settings.collectAsStateWithLifecycle(initialValue = FilterSettings.Default)
    val scope = rememberCoroutineScope()

    Text(
        text = stringResource(R.string.filter_home_timeline),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Spacer(modifier = Modifier.height(8.dp))
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 6.dp)
                .selectableGroup()
        ) {
            HomeFeedMode.entries.forEach { mode ->
                val selected = settings.homeFeedMode == mode
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = SettingsRowMinHeight)
                        .selectable(
                            selected = selected,
                            onClick = {
                                if (settings.homeFeedMode != mode) {
                                    scope.launch {
                                        store.update { it.withHomeFeedMode(mode) }
                                        onDismiss()
                                    }
                                }
                            },
                            role = Role.RadioButton,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = selected,
                        onClick = null,
                    )
                    Text(
                        text = stringResource(mode.labelRes),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ShowTogglesSection() {
    val store = AppGraph.filterSettings
    val settings by store.settings.collectAsStateWithLifecycle(initialValue = FilterSettings.Default)
    val scope = rememberCoroutineScope()

    Text(
        text = stringResource(R.string.filter_display),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Spacer(modifier = Modifier.height(8.dp))
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
            FilterSwitchRow(
                label = stringResource(R.string.filter_show_page_header),
                checked = !settings.hidePageHeader,
                onCheckedChange = { v ->
                    scope.launch { store.update { it.copy(hidePageHeader = !v) } }
                },
            )
            FilterSwitchRow(
                label = stringResource(R.string.filter_show_live_content),
                checked = !settings.hideLiveContent,
                onCheckedChange = { v ->
                    scope.launch { store.update { it.copy(hideLiveContent = !v) } }
                },
            )
        }
    }
}

@Composable
private fun FilterSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = SettingsRowMinHeight),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            },
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp),
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                uncheckedTrackColor = MaterialTheme.colorScheme.surface,
                uncheckedBorderColor = MaterialTheme.colorScheme.outline,
                uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        )
    }
}

@Composable
private fun HideKeywordsSection() {
    val store = AppGraph.filterSettings
    val settings by store.settings.collectAsStateWithLifecycle(initialValue = FilterSettings.Default)
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf("") }

    fun addDraft() {
        val next = FilterSettings.addingKeyword(settings.hideKeywords, draft) ?: return
        draft = ""
        scope.launch { store.update { it.copy(hideKeywords = next) } }
    }

    Text(
        text = stringResource(R.string.filter_hide_keywords),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Spacer(modifier = Modifier.height(8.dp))
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = SettingsRowMinHeight),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    placeholder = { Text(stringResource(R.string.filter_keyword_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { addDraft() }),
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = { addDraft() },
                    modifier = Modifier.padding(start = 4.dp),
                ) {
                    Text(stringResource(R.string.filter_keyword_add))
                }
            }
            settings.hideKeywords.forEach { keyword ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = SettingsRowMinHeight),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = keyword,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 8.dp),
                    )
                    IconButton(
                        onClick = {
                            scope.launch {
                                store.update { current ->
                                    current.copy(
                                        hideKeywords = current.hideKeywords.filterNot {
                                            it.equals(keyword, ignoreCase = true)
                                        },
                                    )
                                }
                            }
                        },
                    ) {
                        Icon(
                            imageVector = Icons.Filled.RemoveCircle,
                            contentDescription = stringResource(R.string.filter_keyword_remove, keyword),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PerformanceBenchmarkSection() {
    val benchmark = AppGraph.benchmark
    val latest by benchmark.latestResult.collectAsStateWithLifecycle()
    val baseline by benchmark.baselineResult.collectAsStateWithLifecycle()
    val isRunning by benchmark.isRunning.collectAsStateWithLifecycle()

    val current = latest ?: baseline
    val delta = current.deltaVs(baseline)

    Text(
        text = stringResource(R.string.benchmark_title),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Spacer(modifier = Modifier.height(8.dp))
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
            // Initial load row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = SettingsRowMinHeight),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(R.string.benchmark_initial_load),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "${current.initialLoadMs} ms",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (latest != null && baseline.initialLoadMs > 0) {
                        Spacer(modifier = Modifier.width(6.dp))
                        val pctText = String.format(Locale.US, "%+.1f%%", delta.initialLoadPct)
                        val color = if (delta.initialLoadPct <= 5.0) OptimalGreen else MaterialTheme.colorScheme.error
                        Text(
                            text = "($pctText)",
                            style = MaterialTheme.typography.labelSmall,
                            color = color,
                        )
                    }
                }
            }

            // Scroll render completion row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = SettingsRowMinHeight),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(R.string.benchmark_scroll_completion),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "${current.scrollRenderCompletionMs} ms",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (latest != null && baseline.scrollRenderCompletionMs > 0) {
                        Spacer(modifier = Modifier.width(6.dp))
                        val pctText = String.format(Locale.US, "%+.1f%%", delta.scrollRenderPct)
                        val color = if (delta.scrollRenderPct <= 5.0) OptimalGreen else MaterialTheme.colorScheme.error
                        Text(
                            text = "($pctText)",
                            style = MaterialTheme.typography.labelSmall,
                            color = color,
                        )
                    }
                }
            }

            // Status chip
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = SettingsRowMinHeight),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "Status",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = if (delta.isRegressed) {
                        stringResource(R.string.benchmark_status_regression)
                    } else {
                        stringResource(R.string.benchmark_status_optimal)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (delta.isRegressed) MaterialTheme.colorScheme.error else OptimalGreen,
                )
            }

            // Action buttons
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = SettingsRowMinHeight),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = { benchmark.runBenchmark() },
                    enabled = !isRunning,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                ) {
                    if (isRunning) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .width(16.dp)
                                .height(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.benchmark_running))
                    } else {
                        Text(stringResource(R.string.benchmark_run_button))
                    }
                }

                if (latest != null) {
                    TextButton(
                        onClick = { benchmark.setAsBaseline(latest!!) },
                        enabled = !isRunning,
                    ) {
                        Text(
                            text = stringResource(R.string.benchmark_set_baseline),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }
    }
}
