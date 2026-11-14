package app.gic

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

private data class FolderRow(val name: String, val bytes: Long, val files: Int)

@Composable
fun StorageTab(pkg: String) {
    val cx = LocalContext.current
    val scope = rememberCoroutineScope()

    var rootMode by rememberSaveable { mutableStateOf(true) }
    var treeUri by remember { mutableStateOf<Uri?>(null) }
    var treeName by remember { mutableStateOf<String?>(null) }
    var files by remember { mutableStateOf<List<Pair<String, Long>>>(emptyList()) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var level by rememberSaveable { mutableIntStateOf(9) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    val base = "/storage/emulated/0/Android/data/$pkg/files"

    LaunchedEffect(pkg) {
        files = emptyList()
        selected = emptySet()
        treeUri = null
        treeName = null
        status = ""
        error = null
    }

    fun src(): Src = if (rootMode) {
        RootSrc(base)
    } else {
        val uri = treeUri ?: throw IOException("Pick the game's files folder first")
        SafSrc(cx, uri)
    }

    val groups = remember(files) {
        files.groupBy { it.first.substringBefore('/') }
            .map { (name, list) -> FolderRow(name, list.sumOf { it.second }, list.size) }
            .sortedByDescending { it.bytes }
    }

    fun scan() {
        if (busy) return
        busy = true
        error = null
        status = "Scanning…"
        scope.launch {
            try {
                val (list, usedRoot) = withContext(Dispatchers.IO) {
                    val s = src()
                    s.list() to rootMode
                }
                files = list
                selected = emptySet()
                status = if (usedRoot) {
                    "Root scan: ${list.size} files, ${fmtMb(list.sumOf { it.second })}"
                } else {
                    "SAF scan: ${list.size} files, ${fmtMb(list.sumOf { it.second })}"
                }
            } catch (e: Exception) {
                error = "Scan failed: ${errText(e)}"
                status = ""
            } finally {
                busy = false
            }
        }
    }

    fun startZip(out: Uri) {
        if (busy) return
        val chosen = files.filter { it.first.substringBefore('/') in selected }
        if (chosen.isEmpty()) return
        busy = true
        error = null
        status = "Starting zip…"
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val s = src()
                    zipSelected(cx, out, s, chosen, level) { done, total, current ->
                        status = "${fmtMb(done)} / ${fmtMb(total)}  •  $current"
                    }
                    val outSize = contentSize(cx, out)
                    status = "Zip complete: ${fmtMb(chosen.sumOf { it.second })} → ${fmtMb(outSize)}"
                }
            } catch (e: Exception) {
                runCatching { cx.contentResolver.delete(out, null, null) }
                error = "Zip failed: ${errText(e)}"
                status = ""
            } finally {
                busy = false
            }
        }
    }

    fun startRestore(zip: Uri) {
        if (busy) return
        busy = true
        error = null
        status = "Restoring…"
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val s = src()
                    val n = restoreZip(cx, zip, s) { count, name ->
                        status = "$count files  •  $name"
                    }
                    files = s.list()
                    selected = emptySet()
                    status = "Restored $n files into $pkg/files"
                }
            } catch (e: Exception) {
                error = "Restore failed: ${errText(e)}"
                status = ""
            } finally {
                busy = false
            }
        }
    }

    fun deleteSelected() {
        if (busy || selected.isEmpty()) return
        busy = true
        error = null
        status = "Deleting…"
        val tops = selected.toList()
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val s = src()
                    var failure: Exception? = null
                    for (top in tops) {
                        try {
                            s.delete(top)
                        } catch (e: Exception) {
                            failure = e
                            break
                        }
                    }
                    files = s.list()
                    selected = emptySet()
                    failure?.let { throw it }
                    status = "Deleted ${tops.size} top-level entr${if (tops.size == 1) "y" else "ies"}. Rescanned."
                }
            } catch (e: Exception) {
                error = "Delete failed: ${errText(e)}"
                status = ""
            } finally {
                busy = false
            }
        }
    }

    val treeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                cx.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: Exception) {
            }
            treeUri = uri
            files = emptyList()
            selected = emptySet()
            error = null
            busy = true
            status = "Checking selected folder…"
            scope.launch {
                try {
                    val s = withContext(Dispatchers.IO) { SafSrc(cx, uri) }
                    treeName = s.displayName
                    status = "Folder selected: ${s.displayName}. Tap Scan."
                } catch (e: Exception) {
                    treeUri = null
                    error = "Folder unusable: ${errText(e)}"
                    status = ""
                } finally {
                    busy = false
                }
            }
        }
    }

    val zipLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri != null) startZip(uri)
    }

    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) startRestore(uri)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text("Storage", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Target: $base",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        item {
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = rootMode,
                    onClick = {
                        rootMode = true
                        files = emptyList()
                        selected = emptySet()
                        status = ""
                        error = null
                    },
                    label = { Text("Root (su)") }
                )
                FilterChip(
                    selected = !rootMode,
                    onClick = {
                        rootMode = false
                        files = emptyList()
                        selected = emptySet()
                        status = ""
                        error = null
                    },
                    label = { Text("SAF (Android 11–12)") }
                )
            }
        }

        if (rootMode) {
            item {
                Text(
                    "Root mode executes find/stat/cat/rm through `su -c`. Your root manager may ask for permission. Restores are chowned to the game UID and relabelled with restorecon.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(
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
                        },
                        enabled = !busy
                    ) {
                        Text(if (treeUri == null) "Pick game files folder" else "Change folder")
                    }
                    treeName?.let { Text("Using: $it", style = MaterialTheme.typography.bodySmall) }
                    Text(
                        "SAF cannot normally reach Android/data on Android 13+. Use root mode there. If you pick the package folder, its `files` child is used automatically.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        item {
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Zip level", style = MaterialTheme.typography.labelLarge)
                FilterChip(level == 0, { level = 0 }, { Text("Store 0") })
                FilterChip(level == 1, { level = 1 }, { Text("Fast 1") })
                FilterChip(level == 9, { level = 9 }, { Text("Max 9") })
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = ::scan, enabled = !busy) { Text("Scan") }
                    TextButton(
                        onClick = {
                            selected = if (groups.isNotEmpty() && selected.size == groups.size) {
                                emptySet()
                            } else {
                                groups.map { it.name }.toSet()
                            }
                        },
                        enabled = !busy && groups.isNotEmpty()
                    ) {
                        Text(if (groups.isNotEmpty() && selected.size == groups.size) "Clear" else "Select all")
                    }
                }
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            zipLauncher.launch("genshin-${pkg.substringAfterLast('.')}.zip")
                        },
                        enabled = !busy && selected.isNotEmpty()
                    ) { Text("Zip") }

                    OutlinedButton(
                        onClick = {
                            restoreLauncher.launch(arrayOf("application/zip", "application/octet-stream"))
                        },
                        enabled = !busy && (rootMode || treeUri != null)
                    ) { Text("Restore") }

                    OutlinedButton(
                        onClick = { confirmDelete = true },
                        enabled = !busy && selected.isNotEmpty()
                    ) { Text("Delete") }
                }
            }
        }

        if (busy) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(status, style = MaterialTheme.typography.bodySmall)
                }
            }
        } else if (status.isNotBlank()) {
            item {
                Text(status, style = MaterialTheme.typography.bodySmall)
            }
        }

        error?.let { message ->
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Text(
                        message,
                        modifier = Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    if (groups.isEmpty()) "No scan yet" else "${groups.size} top-level entries",
                    style = MaterialTheme.typography.labelLarge
                )
                Text(
                    if (selected.isEmpty()) "" else "${selected.size} selected • ${
                        fmtMb(files.filter { it.first.substringBefore('/') in selected }.sumOf { it.second })
                    }",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        items(groups, key = { it.name }) { group ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        selected = if (group.name in selected) selected - group.name else selected + group.name
                    },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = group.name in selected,
                    onCheckedChange = { checked ->
                        selected = if (checked) selected + group.name else selected - group.name
                    }
                )
                Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                    Text(group.name, style = MaterialTheme.typography.bodyMedium)
                    Text("${group.files} files", style = MaterialTheme.typography.labelSmall)
                }
                Text(fmtMb(group.bytes), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${selected.size} checked top-level entr${if (selected.size == 1) "y" else "ies"}?") },
            text = {
                Text(
                    "This physically removes the folders from $pkg/files. " +
                        "The game will re-download anything it needs. This cannot be undone."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    deleteSelected()
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            }
        )
    }
}
