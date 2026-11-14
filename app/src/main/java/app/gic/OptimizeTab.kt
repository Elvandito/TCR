package app.gic

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class PendingDelete(
    val paths: List<String>,
    val label: String,
    val title: String,
    val optIn: Boolean
)

private fun togglePath(set: Set<String>, path: String): Set<String> =
    if (path in set) set - path else set + path

@Composable
fun OptimizeTab(pkg: String) {
    val cx = LocalContext.current
    val st = OptimizerState
    val prefs = remember { cx.getSharedPreferences("gic_prefs", Context.MODE_PRIVATE) }
    var showWarning by remember { mutableStateOf(!prefs.getBoolean("tos_ack", false)) }
    var expanded by remember { mutableStateOf<Set<String>>(emptySet()) }
    var confirmAction by remember { mutableStateOf<PendingDelete?>(null) }
    var pendingAction by remember { mutableStateOf<PendingDelete?>(null) }
    var restoreUri by remember { mutableStateOf<Uri?>(null) }
    var selfCheck by remember { mutableStateOf("Running estimator self-check...") }

    LaunchedEffect(pkg) { OptimizerState.resetForPackage(pkg) }
    LaunchedEffect(Unit) {
        selfCheck = withContext(Dispatchers.IO) {
            runCatching { estimatorSelfCheck() }
                .getOrElse { "Estimator self-check failed: ${errText(it)}" }
        }
    }

    val treeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) OptimizerState.selectTree(cx, uri)
    }
    val deleteBackupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        val action = pendingAction
        pendingAction = null
        if (uri != null && action != null) {
            OptimizerState.backupAndDelete(cx, uri, action.paths, action.label, action.optIn)
        }
    }
    val manualBackupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri != null) OptimizerState.backupAll(cx, uri)
    }
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) restoreUri = uri
    }

    if (showWarning) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Before changing game files") },
            text = {
                Text(
                    "Modifying files under Android/data can violate the game's terms of service " +
                        "and may put the account at risk. Keep a backup, use only the opt-in steps " +
                        "you understand, and never delete arbitrary game data. This warning is shown once."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    prefs.edit().putBoolean("tos_ack", true).apply()
                    showWarning = false
                }) { Text("I understand") }
            }
        )
    }

    val baseline = st.baseline ?: st.total
    val target = baseline * 40L / 100L
    val saved = st.savedBytes
    val savedTargetFraction = if (target > 0) (saved.toDouble() / target.toDouble()).toFloat() else 0f
    val savedInstallFraction = if (baseline > 0) (saved.toDouble() / baseline.toDouble()).toFloat() else 0f
    val gap = (target - saved).coerceAtLeast(0)
    val remainingOptIn = st.optIn
    val remainingOptInBytes = st.sizeOf(remainingOptIn.map { it.path })
    val eligibleRecompress = st.estimates.values.filter {
        it.error == null && it.measuredRatio > 0.10
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 22.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text("Optimize", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Target: ${st.base}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "Read-only estimate first. Nothing is written without selection, confirmation and a backup.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }

        item {
            Card {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text("Access", style = MaterialTheme.typography.titleMedium)
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = st.rootMode,
                            enabled = !st.busy,
                            onClick = { OptimizerState.useMode(true) },
                            label = { Text("Root (su)") }
                        )
                        FilterChip(
                            selected = !st.rootMode,
                            enabled = !st.busy,
                            onClick = { OptimizerState.useMode(false) },
                            label = { Text("SAF (Android 11-12)") }
                        )
                    }
                    if (st.rootMode) {
                        Text(
                            "Root mode uses `su -c` for find/stat/cat/mkdir/rm. Restores are chowned " +
                                "to the game UID and relabelled with restorecon.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        OutlinedButton(
                            enabled = !st.busy,
                            onClick = {
                                val initial = DocumentsContract.buildDocumentUri(
                                    "com.android.externalstorage.documents",
                                    "primary:Android/data/$pkg"
                                )
                                try {
                                    treeLauncher.launch(initial)
                                } catch (_: Exception) {
                                    treeLauncher.launch(null)
                                }
                            }
                        ) {
                            Text(if (st.treeUri == null) "Pick game files folder" else "Change folder")
                        }
                        st.treeName?.let {
                            Text("Using: $it", style = MaterialTheme.typography.bodySmall)
                        }
                        Text(
                            "SAF normally cannot reach Android/data on Android 13+. If you select the " +
                                "package folder, its `files` child is used automatically.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            enabled = !st.busy && (st.rootMode || st.treeUri != null),
                            onClick = { OptimizerState.scan(cx) }
                        ) { Text(if (st.files.isEmpty()) "Scan" else "Rescan") }
                    }
                    Text(
                        "Scan groups files by top-level and second-level folder. Categories below are " +
                            "name hints from this scan, not assumptions about the game. Close the game " +
                            "before cleaning so the backup matches what is deleted.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        if (st.busy || st.status.isNotBlank() || st.error != null) {
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (st.error != null) {
                            MaterialTheme.colorScheme.errorContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        }
                    )
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (st.busy) {
                            val p = st.progress
                            if (p == null) {
                                LinearProgressIndicator(Modifier.fillMaxWidth())
                            } else {
                                FractionBar(p)
                            }
                        }
                        if (st.status.isNotBlank()) {
                            Text(st.status, style = MaterialTheme.typography.bodySmall)
                        }
                        st.error?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("Estimator self-check (read-only)", style = MaterialTheme.typography.titleMedium)
                    Text(
                        selfCheck,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (selfCheck.startsWith("Estimator self-check OK")) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                    Text(
                        "Runs zstd-19 and xz on a 512 KiB in-memory sample. No game file is touched.",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }

        if (st.files.isNotEmpty()) {
            item {
                Card {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Savings gauge", style = MaterialTheme.typography.titleMedium)
                            Text(fmtBytes(baseline), style = MaterialTheme.typography.labelLarge)
                        }
                        Text(
                            "Saved ${fmtBytes(saved)} (${(savedTargetFraction * 100f).coerceAtLeast(0f).toInt()}% of 40% target)",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        FractionBar(savedTargetFraction)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("0%", style = MaterialTheme.typography.labelSmall)
                            Text("40% target", style = MaterialTheme.typography.labelSmall)
                        }
                        Text(
                            "That is ${pct(saved, baseline)} of the scanned install. Target bytes: " +
                                "${fmtBytes(target)}; gap: ${fmtBytes(gap)}.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (st.projectedBytes > 0) {
                            Text(
                                "Sample projection: ${fmtBytes(st.projectedBytes)} " +
                                    "(${pct(st.projectedBytes, baseline)} of install) could be saved by " +
                                    "recompression. This is measured from samples, not achieved.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                    }
                }
            }

            item {
                Card {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Estimator (read-only, default)", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Samples up to 64 MB and 64 files per top-level group with zstd-19 and xz, " +
                                "then projects the measured ratio over that group. No game byte is modified.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                enabled = !st.busy,
                                onClick = { OptimizerState.estimate(cx) }
                            ) { Text(if (st.estimates.isEmpty()) "Run estimator" else "Measure again") }
                        }
                        if (st.estimates.isEmpty()) {
                            Text("No samples yet.", style = MaterialTheme.typography.bodySmall)
                        } else {
                            for (group in st.top) {
                                val estimate = st.estimates[group.path] ?: continue
                                EstimateLine(group, estimate)
                            }
                        }
                    }
                }
            }

            item {
                Card {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Safe cleanup (after confirmation)", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Only name-matched cache, log, temp, crash and dump groups are listed. " +
                                "They are not preselected. A zip backup is written before anything is deleted.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (st.cleanup.isEmpty()) {
                            Text(
                                "No generic cleanup groups matched this scan.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        } else {
                            for (group in st.cleanup) {
                                GroupChoiceRow(
                                    group = group,
                                    checked = group.path in st.cleanupSelected,
                                    enabled = !st.busy,
                                    onToggle = {
                                        st.cleanupSelected = togglePath(st.cleanupSelected, group.path)
                                    }
                                )
                            }
                            Text(
                                "Selected: ${fmtBytes(st.sizeOf(st.cleanupSelected))}",
                                style = MaterialTheme.typography.labelLarge
                            )
                            Button(
                                enabled = !st.busy && st.cleanupSelected.isNotEmpty(),
                                onClick = {
                                    confirmAction = PendingDelete(
                                        st.cleanupSelected.toList(),
                                        "Safe cleanup",
                                        "Delete ${st.cleanupSelected.size} cleanup item(s)?",
                                        false
                                    )
                                }
                            ) { Text("Back up & clean selected") }
                        }
                    }
                }
            }

            item {
                Card {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Opt-in reversible trade-offs", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Name-matched audio, voice, video or cutscene groups. Nothing is selected by " +
                                "default. Removing these can remove voice-over or media; the game may need " +
                                "to re-download them. A backup is required and restore is available below.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (st.optIn.isEmpty()) {
                            Text(
                                "No media name hints in this scan. Arbitrary data is not offered for removal.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        } else {
                            for (group in st.optIn) {
                                GroupChoiceRow(
                                    group = group,
                                    checked = group.path in st.optInSelected,
                                    enabled = !st.busy,
                                    onToggle = {
                                        st.optInSelected = togglePath(st.optInSelected, group.path)
                                    }
                                )
                            }
                            Text(
                                "Selected: ${fmtBytes(st.sizeOf(st.optInSelected))}",
                                style = MaterialTheme.typography.labelLarge
                            )
                            Button(
                                enabled = !st.busy && st.optInSelected.isNotEmpty(),
                                onClick = {
                                    confirmAction = PendingDelete(
                                        st.optInSelected.toList(),
                                        "Opt-in media removal",
                                        "Remove ${st.optInSelected.size} opt-in media item(s)?",
                                        true
                                    )
                                }
                            ) { Text("Back up & remove selected") }
                        }
                    }
                }
            }

            item {
                val fs = st.recompress
                Card {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Lossless recompression check", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Root mode checks /proc/filesystems for erofs, squashfs or f2fs. Only a " +
                                "verified in-place compression API with a measured gain above 10% would " +
                                "be eligible. Files are never replaced with raw zstd/xz data.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Button(
                            enabled = !st.busy,
                            onClick = { OptimizerState.checkRecompress() }
                        ) { Text(if (fs == null) "Check kernel support" else "Check again") }
                        if (fs != null) {
                            Text(
                                if (fs.root) "Listed: ${fs.listed.ifEmpty { listOf("none of erofs/squashfs/f2fs") }.joinToString(", ")}"
                                else "Root mode is required to run this check.",
                                style = MaterialTheme.typography.labelLarge,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(fs.reason, style = MaterialTheme.typography.bodySmall)
                        }
                        Text(
                            "Sampled groups above 10%: ${if (eligibleRecompress.isEmpty()) "none" else eligibleRecompress.joinToString { it.path }}",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            "Result: skipped unless a verified filesystem API exists. No recompressed " +
                                "file is ever written by this build.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            item {
                Card {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Backup and restore", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Backup runs before every delete. Choose a zip level here. Store is fastest; " +
                                "Fast and Max trade CPU for a smaller backup. Restore rejects absolute paths, " +
                                "`..` and backslashes, then fixes root ownership and SELinux labels.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text("Zip level", style = MaterialTheme.typography.labelLarge)
                            FilterChip(
                                selected = st.level == 0,
                                enabled = !st.busy,
                                onClick = { st.level = 0 },
                                label = { Text("Store 0") }
                            )
                            FilterChip(
                                selected = st.level == 1,
                                enabled = !st.busy,
                                onClick = { st.level = 1 },
                                label = { Text("Fast 1") }
                            )
                            FilterChip(
                                selected = st.level == 9,
                                enabled = !st.busy,
                                onClick = { st.level = 9 },
                                label = { Text("Max 9") }
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                enabled = !st.busy && st.files.isNotEmpty(),
                                onClick = {
                                    manualBackupLauncher.launch("gic-full-${pkg.substringAfterLast('.')}.zip")
                                }
                            ) { Text("Back up all files") }
                            OutlinedButton(
                                enabled = !st.busy && (st.rootMode || st.treeUri != null),
                                onClick = {
                                    restoreLauncher.launch(arrayOf("application/zip", "application/octet-stream"))
                                }
                            ) { Text("Restore backup") }
                        }
                    }
                }
            }

            if (st.steps.isNotEmpty() || st.estimates.isNotEmpty()) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            Text("Final report", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Before ${fmtBytes(baseline)} -> After ${fmtBytes(st.total)}. " +
                                    "Actual reduction: ${fmtBytes(saved)} (${pct(saved, baseline)}).",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            if (st.steps.isEmpty()) {
                                Text(
                                    "No changes applied. Estimator numbers are projections, not achieved savings.",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            } else {
                                for (step in st.steps) {
                                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(
                                            "${step.label}: ${fmtBytes(step.before)} -> ${fmtBytes(step.after)} " +
                                                "(saved ${fmtBytes(step.saved)}, " +
                                                "${pct(step.saved, step.before)} of that step)",
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                        if (step.removed.isNotEmpty()) {
                                            Text(
                                                "${if (step.optIn) "Removed opt-in" else "Removed"}: " +
                                                    step.removed.joinToString(", "),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = if (step.optIn) {
                                                    MaterialTheme.colorScheme.error
                                                } else {
                                                    MaterialTheme.colorScheme.onSurfaceVariant
                                                }
                                            )
                                        }
                                        if (step.note.isNotBlank()) {
                                            Text(
                                                step.note,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.error
                                            )
                                        }
                                    }
                                }
                            }
                            if (baseline > 0) {
                                if (saved >= target) {
                                    Text(
                                        "40% target reached by measured changes.",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                } else {
                                    Text(
                                        "Gap to 40%: ${fmtBytes(gap)}. The measured steps above do not " +
                                            "reach the target.",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    if (remainingOptIn.isNotEmpty()) {
                                        Text(
                                            "Remaining opt-in candidates: ${fmtBytes(remainingOptInBytes)} " +
                                                "(${remainingOptIn.joinToString { it.path }}).",
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                        Text(
                                            if (remainingOptInBytes >= gap) {
                                                "Selecting all remaining opt-in candidates would close the " +
                                                    "gap, but each needs its own confirmation and backup."
                                            } else {
                                                "Even all remaining opt-in candidates would not reach 40%. " +
                                                    "This install does not have a safe 40% path in this scan."
                                            },
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    } else {
                                        Text(
                                            "No remaining opt-in candidates. This install does not have a " +
                                                "safe 40% path in this scan.",
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    }
                                }
                            }
                            val recompressReason = st.recompress?.reason
                                ?: "No verified per-file in-place compression API; run the kernel check for details."
                            Text(
                                "Recompression step: skipped. $recompressReason",
                                style = MaterialTheme.typography.labelSmall
                            )
                            Text(
                                "Unapplied sample projection: ${fmtBytes(st.projectedBytes)}. It is not " +
                                    "counted in the before/after numbers.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            item {
                Text(
                    "Scan groups (top-level and second-level)",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    "Tap a top-level group to expand its second-level folders. Sizes are real scan sizes.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            items(st.top, key = { it.path }) { group ->
                val isOpen = group.path in expanded
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { expanded = togglePath(expanded, group.path) },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(group.path, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "${group.files} files - ${group.hint.label}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = hintColor(group.hint.kind)
                                )
                            }
                            Text(fmtBytes(group.bytes), style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.size(8.dp))
                            Text(if (isOpen) "-" else "+", style = MaterialTheme.typography.titleMedium)
                        }
                        if (isOpen) {
                            Text(
                                group.hint.reason,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            val children = st.second[group.path].orEmpty()
                            if (children.isEmpty()) {
                                Text(
                                    "No second-level folders.",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            } else {
                                for (child in children) {
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(start = 10.dp, top = 5.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(Modifier.weight(1f)) {
                                            Text(child.path.substringAfter('/'), style = MaterialTheme.typography.bodySmall)
                                            Text(
                                                child.hint.label,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = hintColor(child.hint.kind)
                                            )
                                        }
                                        Text(fmtBytes(child.bytes), style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                            st.estimates[group.path]?.let { estimate ->
                                EstimateLine(group, estimate)
                            }
                        }
                    }
                }
            }
        }
    }

    confirmAction?.let { action ->
        AlertDialog(
            onDismissRequest = { confirmAction = null },
            title = { Text(action.title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "A zip backup is mandatory before deletion. You will pick its location next. " +
                            "If the backup fails, nothing is deleted."
                    )
                    Text(action.paths.take(12).joinToString("\n"))
                    if (action.paths.size > 12) {
                        Text("...and ${action.paths.size - 12} more.")
                    }
                    if (action.optIn) {
                        Text(
                            "Trade-off: this may remove voice-over, video or cutscene data. The game " +
                                "may need to re-download it; restore from the backup if needed."
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmAction = null
                    pendingAction = action
                    deleteBackupLauncher.launch("gic-backup-${pkg.substringAfterLast('.')}.zip")
                }) { Text("Choose backup location") }
            },
            dismissButton = {
                TextButton(onClick = { confirmAction = null }) { Text("Cancel") }
            }
        )
    }

    restoreUri?.let { uri ->
        AlertDialog(
            onDismissRequest = { restoreUri = null },
            title = { Text("Restore this backup?") },
            text = {
                Text(
                    "This writes the selected zip under ${OptimizerState.base}. Same-named files " +
                        "will be overwritten. Restore rejects absolute paths, backslashes and `..`, " +
                        "then root mode fixes ownership and SELinux labels. Keep the game closed."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    restoreUri = null
                    OptimizerState.restore(cx, uri)
                }) { Text("Restore") }
            },
            dismissButton = {
                TextButton(onClick = { restoreUri = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun EstimateLine(group: OptGroup, estimate: Estimate) {
    if (estimate.error != null) {
        Text(
            "${group.path}: sample failed - ${estimate.error}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error
        )
        return
    }
    Text(
        "${group.path}: sampled ${fmtBytes(estimate.sample)}; zstd-19 " +
            "${fmtBytes(estimate.zstd)} / xz ${fmtBytes(estimate.xz)}; projected saving " +
            "${fmtBytes(estimate.savings)} (${pct(estimate.savings, group.bytes)}).",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun GroupChoiceRow(
    group: OptGroup,
    checked: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onToggle() },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, enabled = enabled, onCheckedChange = { onToggle() })
        Column(Modifier.weight(1f)) {
            Text(group.path, style = MaterialTheme.typography.bodySmall)
            Text(
                group.hint.reason,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(fmtBytes(group.bytes), style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun FractionBar(fraction: Float) {
    val f = fraction.coerceIn(0f, 1f)
    Box(
        Modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Box(
            Modifier
                .fillMaxWidth(f)
                .fillMaxHeight()
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.primary)
        )
    }
}

@Composable
private fun hintColor(kind: GroupKind): Color = when (kind) {
    GroupKind.CLEANUP -> MaterialTheme.colorScheme.primary
    GroupKind.OPT_IN -> MaterialTheme.colorScheme.secondary
    GroupKind.DATA -> MaterialTheme.colorScheme.onSurfaceVariant
}
