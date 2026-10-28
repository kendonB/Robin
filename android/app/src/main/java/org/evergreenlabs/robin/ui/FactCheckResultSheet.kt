package org.evergreenlabs.robin.ui

import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.SecureFlagPolicy
import kotlinx.coroutines.CancellationException
import org.evergreenlabs.robin.BuildConfig
import org.evergreenlabs.robin.firebase.FactCheckException
import org.evergreenlabs.robin.firebase.FactCheckFinding
import org.evergreenlabs.robin.firebase.FactCheckFindingStatus
import org.evergreenlabs.robin.firebase.FactCheckResult
import org.evergreenlabs.robin.firebase.GenerativeAIService
import org.evergreenlabs.robin.firebase.SourceCredibilityRating
import org.evergreenlabs.robin.security.FactCheckInputPolicy
import org.evergreenlabs.robin.security.ProtectDialogWindow
import org.evergreenlabs.robin.security.WebUrlPolicy

data class FactCheckRequest(
    val text: String,
    val author: String? = null,
    val tweetURL: String? = null,
    val externalLinks: List<String> = emptyList(),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FactCheckSheet(
    request: FactCheckRequest,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current

    val input = remember(request) {
        FactCheckInputPolicy.validate(request.text, request.author, request.tweetURL, request.externalLinks)
    }
    var isLoading by remember(request) { mutableStateOf(false) }
    var result by remember(request) { mutableStateOf<FactCheckResult?>(null) }
    var errorMessage by remember(request) { mutableStateOf<String?>(null) }
    // Only a native button changes this. Merely opening/replacing the sheet sends nothing.
    var submissionCount by remember(request) { mutableStateOf(0) }

    fun openUrl(url: String) {
        val safeUrl = WebUrlPolicy.safeHttpsUrl(url) ?: return
        runCatching {
            val customTabsIntent = CustomTabsIntent.Builder().setShowTitle(true).build()
            customTabsIntent.launchUrl(context, Uri.parse(safeUrl))
        }
    }

    LaunchedEffect(request, submissionCount) {
        if (submissionCount == 0 || !BuildConfig.ENABLE_AI || input == null) return@LaunchedEffect
        isLoading = true
        errorMessage = null
        try {
            val outcome = GenerativeAIService.factCheckTweet(
                text = input.text,
                author = input.author,
                tweetURL = input.tweetURL,
                externalLinks = input.externalLinks,
            )
            result = outcome.result
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (safe: FactCheckException) {
            errorMessage = safe.message
        } catch (_: Exception) {
            errorMessage = "The fact check is unavailable. You can choose to send it again."
        } finally {
            isLoading = false
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        properties = ModalBottomSheetProperties(securePolicy = SecureFlagPolicy.SecureOn),
    ) {
        ProtectDialogWindow()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        text = "Fact Check",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    input?.author?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("Done")
                }
            }

            when {
                !BuildConfig.ENABLE_AI -> {
                    Text("Fact-checking is disabled in this build. No post has been sent.")
                }
                input == null -> {
                    Text("This post is too long or contains unsupported links. No post has been sent.")
                }
                submissionCount == 0 -> {
                    Text(
                        text = "Send this post for fact-checking?",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "The text below, author, post permalink and listed links will be sent to Google " +
                            "for analysis with Google Search. Your X cookies and password are not included.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (input.externalLinks.isNotEmpty()) {
                        Text(
                            "Robin also downloads publisher ratings from evergreen-labs.org. Linked pages are not fetched by Robin.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    FactCheckSection(title = "Post to send") {
                        Text(input.text, style = MaterialTheme.typography.bodyMedium)
                        input.tweetURL?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        input.externalLinks.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                    Button(onClick = {
                        if (!isLoading) {
                            isLoading = true
                            submissionCount++
                        }
                    }) { Text("Send to Google") }
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                }
                isLoading -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(48.dp))
                        Text(
                            text = "Analysing claims with Gemini 3.8 Flash…",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = "Grounding with Google Search",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                errorMessage != null -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 36.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(48.dp),
                        )
                        Text(
                            text = "Fact Check Unavailable",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = errorMessage.orEmpty(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(onClick = {
                            if (!isLoading) {
                                isLoading = true
                                submissionCount++
                            }
                        }) {
                            Text("Send Again")
                        }
                    }
                }
                result != null -> {
                    val res = result!!
                    val scoreColor = when (res.score) {
                        in 1..3 -> Color(0xFFD32F2F)
                        in 4..6 -> Color(0xFFF57C00)
                        else -> Color(0xFF388E3C)
                    }

                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = "${res.score}",
                            fontSize = 56.sp,
                            fontWeight = FontWeight.Bold,
                            color = scoreColor,
                        )
                        Text(
                            text = " / 10",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 10.dp, start = 4.dp),
                        )
                    }

                    Text(
                        text = res.verdict,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )

                    if (res.justification.isNotEmpty()) {
                        FactCheckSection(title = "Summary") {
                            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                res.justification.forEachIndexed { index, point ->
                                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Text(
                                            text = "${index + 1}.",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        Text(
                                            text = point,
                                            style = MaterialTheme.typography.bodyMedium,
                                            modifier = Modifier.weight(1f),
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (res.findings.isNotEmpty()) {
                        FactCheckSection(title = "Claims check") {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                res.findings.forEach { finding ->
                                    FindingRow(finding = finding)
                                }
                            }
                        }
                    }

                    if (res.sources.isNotEmpty()) {
                        FactCheckSection(title = "Sources") {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                res.sources.forEach { source ->
                                    TextButton(
                                        onClick = { openUrl(source.url) },
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Column(modifier = Modifier.fillMaxWidth()) {
                                            Text(
                                                text = source.title,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.SemiBold,
                                            )
                                            Text(
                                                text = source.url,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    res.sourceRating?.let { rating ->
                        FactCheckSection(title = "Linked Source Rating") {
                            SourceRatingContent(
                                rating = rating,
                                onOpenMbfc = { url -> openUrl(url) },
                            )
                        }
                    }

                    val suggestionsHtml = res.searchSuggestionsHTML
                    if (!suggestionsHtml.isNullOrBlank()) {
                        FactCheckSection(title = "Google Search suggestions") {
                            FactCheckSearchSuggestions(
                                html = suggestionsHtml,
                                onOpenUrl = ::openUrl,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 72.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FactCheckSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                    RoundedCornerShape(10.dp),
                )
                .padding(12.dp),
            content = content,
        )
    }
}
@Composable
private fun FindingRow(finding: FactCheckFinding) {
    val chipColors = findingStatusColors(finding.status)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                RoundedCornerShape(8.dp),
            )
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = finding.status.displayLabel,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = chipColors.first,
            modifier = Modifier
                .background(chipColors.second, RoundedCornerShape(50))
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
        Text(
            text = finding.claim,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
        if (finding.note.isNotBlank()) {
            Text(
                text = finding.note,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun findingStatusColors(status: FactCheckFindingStatus): Pair<Color, Color> {
    return when (status) {
        FactCheckFindingStatus.VERIFIED ->
            Color(0xFF063B1A) to Color(0xFFB9F6CA)
        FactCheckFindingStatus.PARTIALLY_VERIFIED ->
            Color(0xFF4A3000) to Color(0xFFFFE082)
        FactCheckFindingStatus.UNVERIFIED ->
            Color(0xFF3F3F46) to Color(0xFFE0E0E4)
        FactCheckFindingStatus.FALSE_MISLEADING ->
            Color(0xFF5C0000) to Color(0xFFFFCDD2)
    }
}

@Composable
private fun SourceRatingContent(
    rating: SourceCredibilityRating,
    onOpenMbfc: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = rating.sourceName,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
        RatingRow(label = "Factual reporting", value = rating.factualReporting)
        rating.displayBias?.let { bias ->
            RatingRow(label = "Bias", value = bias)
        }
        RatingRow(label = "Credibility", value = rating.credibility)
        if (rating.mbfcURL.isNotBlank()) {
            TextButton(
                onClick = { onOpenMbfc(rating.mbfcURL) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "Media Bias/Fact Check",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun RatingRow(label: String, value: String) {
    if (value.isBlank()) return
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
        )
    }
}
