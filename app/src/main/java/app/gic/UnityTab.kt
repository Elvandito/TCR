package app.gic

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

private const val MAX_FOLDER_FILES = 2000
private val containerExtensions = setOf("bundle", "blk", "pck", "usm")

private data class FolderScan(
    val name: String,
    val files: List<PickedBundle>,
    val truncated: Boolean
)

private fun extensionOf(name: String): String =
    name.substringAfterLast('.', "").lowercase()

private fun unsupportedExtensionLabel(name: String): String? = when (extensionOf(name)) {
    "blk" -> "encrypted .blk container"
    "pck" -> "Wwise .pck audio"
    "usm" -> "CriWare .usm video"
    else -> null
}

private fun displayName(cx: Context, uri: Uri): String {
    runCatching {
        cx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (i >= 0 && !c.isNull(i)) return c.getString(i)
            }
        }
    }
    return uri.lastPathSegment?.substringAfterLast('/')?.ifBlank { null } ?: "selected.bundle"
}

private fun persistRead(cx: Context, uri: Uri) {
    runCatching {
        cx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}

private fun persistReadWrite(cx: Context, uri: Uri) {
    runCatching {
        cx.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
    }
}

private fun scanFolder(cx: Context, uri: Uri): FolderScan {
    val root = DocumentFile.fromTreeUri(cx, uri)
        ?: throw IOException("The selected folder is unavailable")
    if (!root.isDirectory) throw IOException("The selected item is not a folder")
    val found = ArrayList<PickedBundle>()
    var truncated = false

    fun walk(dir: DocumentFile, prefix: String) {
        for (file in dir.listFiles()) {
            if (found.size >= MAX_FOLDER_FILES) {
                truncated = true
                return
            }
            val name = file.name ?: continue
            val relative = prefix + name
            if (file.isDirectory) {
                walk(file, "$relative/")
            } else if (extensionOf(name) in containerExtensions) {
                found.add(PickedBundle(name, relative, file.uri, file.length()))
            }
        }
    }

    walk(root, "")
    found.sortBy { it.relativePath.lowercase() }
    return FolderScan(root.name ?: "selected folder", found, truncated)
}

@Composable
fun UnityTab() {
    val cx = LocalContext.current
    val scope = rememberCoroutineScope()

    var picked by remember { mutableStateOf<PickedBundle?>(null) }
    var meta by remember { mutableStateOf<UMeta?>(null) }
    var analyzing by remember { mutableStateOf(false) }
    var analysisError by remember { mutableStateOf<String?>(null) }
    var analysisUnsupported by remember { mutableStateOf(false) }

    var folderUri by remember { mutableStateOf<Uri?>(null) }
    var folderName by remember { mutableStateOf<String?>(null) }
    var folderFiles by remember { mutableStateOf<List<PickedBundle>>(emptyList()) }
    var folderTruncated by remember { mutableStateOf(false) }
    var folderBusy by remember { mutableStateOf(false) }
    var folderError by remember { mutableStateOf<String?>(null) }

    var outputTree by remember { mutableStateOf<Uri?>(null) }
    var outputName by remember { mutableStateOf<String?>(null) }

    var target by rememberSaveable { mutableIntStateOf(TARGET_LZMA) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var opStatus by remember { mutableStateOf("") }
    var opError by remember { mutableStateOf<String?>(null) }
    var opUnsupported by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<UnityOutput?>(null) }

    var selfCheck by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        selfCheck = withContext(Dispatchers.IO) {
            runCatching { unityHeaderSelfCheck() }
                .getOrElse { "Self-check failed: ${errText(it)}" }
        }
    }

    LaunchedEffect(picked?.uri) {
        val source = picked ?: return@LaunchedEffect
        analyzing = true
        meta = null
        analysisError = null
        analysisUnsupported = false
        try {
            val parsed = withContext(Dispatchers.IO) { analyzeUri(cx, source) }
            if (picked?.uri == source.uri) meta = parsed
        } catch (e: UnsupportedBundleException) {
            if (picked?.uri == source.uri) {
                analysisUnsupported = true
                analysisError = errText(e)
            }
        } catch (e: Exception) {
            if (picked?.uri == source.uri) {
                analysisError = errText(e)
            }
        } finally {
            if (picked?.uri == source.uri) analyzing = false
        }
    }

    fun chooseSource(source: PickedBundle) {
        picked = source
        result = null
        opError = null
        opUnsupported = false
        opStatus = ""
        progress = 0f
    }

    val sourceLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            persistRead(cx, uri)
            val bundle = PickedBundle(
                name = displayName(cx, uri),
                relativePath = displayName(cx, uri),
                uri = uri,
                size = contentSize(cx, uri)
            )
            chooseSource(bundle)
        }
    }

    fun startFolderScan(uri: Uri) {
        folderUri = uri
        folderName = null
        folderFiles = emptyList()
        folderTruncated = false
        folderError = null
        folderBusy = true
        scope.launch {
            try {
                val scan = withContext(Dispatchers.IO) { scanFolder(cx, uri) }
                folderName = scan.name
                folderFiles = scan.files
                folderTruncated = scan.truncated
            } catch (e: Exception) {
                folderError = errText(e)
            } finally {
                folderBusy = false
            }
        }
    }

    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            persistRead(cx, uri)
            startFolderScan(uri)
        }
    }

    val outputLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            persistReadWrite(cx, uri)
            outputTree = uri
            outputName = runCatching { DocumentFile.fromTreeUri(cx, uri)?.name }.getOrNull()
                ?: "selected folder"
        }
    }

    fun runExtract() {
        val source = picked ?: return
        val outTree = outputTree ?: return
        busy = true
        progress = 0f
        result = null
        opError = null
        opUnsupported = false
        opStatus = "Analysing and extracting…"
        scope.launch {
            try {
                val output = withContext(Dispatchers.IO) {
                    extractRawBundle(cx, source, outTree) { done, total ->
                        progress = if (total > 0) done.toFloat() / total else 0f
                        opStatus = "Extracting ${fmtMb(done)} / ${fmtMb(total)}"
                    }
                }
                result = output
                progress = 1f
                opStatus = "Extraction complete."
            } catch (e: UnsupportedBundleException) {
                opUnsupported = true
                opError = errText(e)
                opStatus = ""
            } catch (e: Exception) {
                opError = errText(e)
                opStatus = ""
            } finally {
                busy = false
            }
        }
    }

    fun runRepack() {
        val source = picked ?: return
        val outTree = outputTree ?: return
        busy = true
        progress = 0f
        result = null
        opError = null
        opUnsupported = false
        opStatus = "Reading and recompressing…"
        scope.launch {
            try {
                val output = withContext(Dispatchers.IO) {
                    repackBundle(cx, source, outTree, target) { done, total ->
                        progress = if (total > 0) done.toFloat() / total else 0f
                        opStatus = "Recompressing ${fmtMb(done)} / ${fmtMb(total)}"
                    }
                }
                result = output
                progress = 1f
                opStatus = "Repack complete. Output was verified before publishing."
            } catch (e: UnsupportedBundleException) {
                opUnsupported = true
                opError = errText(e)
                opStatus = ""
            } catch (e: Exception) {
                opError = errText(e)
                opStatus = ""
            } finally {
                busy = false
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text("Unity", style = MaterialTheme.typography.headlineSmall)
            Text(
                "UnityFS AssetBundle inspector, raw extractor and verified repacker. Supports container versions 6–8.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        item {
            Card {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Source", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { sourceLauncher.launch(arrayOf("*/*")) }, enabled = !busy) {
                            Text("Open bundle file")
                        }
                        OutlinedButton(onClick = { folderLauncher.launch(null) }, enabled = !busy) {
                            Text("Scan folder")
                        }
                    }
                    picked?.let { source ->
                        Text(source.name, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "${source.relativePath}  •  ${fmtBytes(source.size)}",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        unsupportedExtensionLabel(source.name)?.let { label ->
                            Text(
                                "Known unsupported container: $label",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    } ?: Text(
                        "Choose a .bundle file, or scan the game's files folder.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        if (folderUri != null) {
            item {
                Card {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(Modifier.weight(1f)) {
                                Text("Scanned folder", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "${folderName ?: "…"}  •  ${folderFiles.size} bundle/container files" +
                                        if (folderTruncated) " (first $MAX_FOLDER_FILES)" else "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            TextButton(onClick = { folderUri?.let(::startFolderScan) }, enabled = !folderBusy && !busy) {
                                Text("Rescan")
                            }
                        }
                        if (folderBusy) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Text("Scanning folder…", style = MaterialTheme.typography.bodySmall)
                        }
                        folderError?.let {
                            Text("Folder error: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                        if (!folderBusy && folderError == null) {
                            if (folderFiles.isEmpty()) {
                                Text(
                                    "No .bundle, .blk, .pck or .usm files found here.",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            } else {
                                LazyColumn(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 280.dp),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    items(folderFiles, key = { it.relativePath }) { file ->
                                        val isPicked = picked?.uri == file.uri
                                        Row(
                                            Modifier
                                                .fillMaxWidth()
                                                .clickable(enabled = !busy) { chooseSource(file) }
                                                .padding(vertical = 6.dp, horizontal = 2.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(Modifier.weight(1f)) {
                                                Text(
                                                    file.name,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = if (isPicked) MaterialTheme.colorScheme.primary
                                                    else MaterialTheme.colorScheme.onSurface
                                                )
                                                Text(
                                                    file.relativePath,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                            Column(horizontalAlignment = Alignment.End) {
                                                Text(
                                                    fmtBytes(file.size),
                                                    style = MaterialTheme.typography.labelSmall
                                                )
                                                unsupportedExtensionLabel(file.name)?.let {
                                                    Text(
                                                        "unsupported",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.error
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                                Text(
                                    "Tapping a .blk/.pck/.usm is allowed so its rejection reason can be shown.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }

        if (analyzing) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("Reading UnityFS header and block table…", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        if (analysisError != null) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            if (analysisUnsupported) "Unsupported bundle" else "Bundle analysis failed",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Text(
                            analysisError ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Text(
                            "No output has been written. .blk, .pck and .usm files are intentionally rejected; encrypted UnityFS archives and malformed signatures are not bypassed.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }
        }

        meta?.let { info ->
            item {
                Card {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text("Bundle metadata", style = MaterialTheme.typography.titleMedium)
                        MetaLine("Format", "${info.header.signature} v${info.header.version}")
                        MetaLine("Unity engine", "${info.header.unityVersion}  (${info.header.unityRevision})")
                        MetaLine(
                            "Header flags",
                            "0x${info.header.flags.toString(16)}  •  block table " +
                                if (info.infoAtEnd) "at end" else "at start"
                        )
                        MetaLine(
                            "Compression",
                            info.compressionLabel + "  (block-table compression: " +
                                compressionName(info.header.flags.toInt() and 0x3f) + ")"
                        )
                        MetaLine(
                            "Block-table sizes",
                            "${fmtBytes(info.header.compressedInfoSize)} compressed / " +
                                "${fmtBytes(info.header.uncompressedInfoSize)} uncompressed"
                        )
                        MetaLine(
                            "Block data sizes",
                            "${fmtBytes(info.totalCompressedBlocks)} compressed / " +
                                "${fmtBytes(info.totalUncompressed)} uncompressed"
                        )
                        MetaLine("Blocks / directory", "${info.blocks.size} blocks  •  ${info.nodes.size} file entries")
                        MetaLine("Block data offset", info.dataOffset.toString())
                        MetaLine("Source size", "${fmtBytes(info.fileLength)} (header declared ${fmtBytes(info.header.fileSize)})")
                        Text(
                            "Extraction returns the concatenated plain payload, not individual Unity assets. Repacking preserves the directory table and re-chunks that payload.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        item {
            Card {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Output folder", style = MaterialTheme.typography.titleMedium)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { outputLauncher.launch(null) }, enabled = !busy) {
                            Text(if (outputTree == null) "Choose output folder" else "Change output folder")
                        }
                        Text(
                            outputName ?: "Required before extract/repack",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (outputTree == null) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        "Output is written only into this user-selected SAF folder. Existing files are never overwritten; names get a numeric suffix.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        meta?.let {
            item {
                Card {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Actions", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Repack destination compression",
                            style = MaterialTheme.typography.labelLarge
                        )
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(
                                selected = target == TARGET_NONE,
                                onClick = { target = TARGET_NONE },
                                label = { Text("None") },
                                enabled = !busy
                            )
                            FilterChip(
                                selected = target == TARGET_LZ4,
                                onClick = { target = TARGET_LZ4 },
                                label = { Text("LZ4") },
                                enabled = !busy
                            )
                            FilterChip(
                                selected = target == TARGET_LZMA,
                                onClick = { target = TARGET_LZMA },
                                label = { Text("LZMA") },
                                enabled = !busy
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = ::runExtract,
                                enabled = !busy && outputTree != null
                            ) { Text("Extract raw") }
                            OutlinedButton(
                                onClick = ::runRepack,
                                enabled = !busy && outputTree != null
                            ) { Text("Repack") }
                        }
                        if (busy) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Text(
                                "${(progress.coerceIn(0f, 1f) * 100f).toInt()}%",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                        if (busy || opStatus.isNotBlank()) {
                            Text(opStatus, style = MaterialTheme.typography.bodySmall)
                        }
                        opError?.let { message ->
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer
                                )
                            ) {
                                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text(
                                        if (opUnsupported) "Unsupported" else "Operation failed",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                    Text(
                                        message,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                }
                            }
                        }
                        result?.let { output ->
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer
                                )
                            ) {
                                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text(
                                        "Saved: ${output.name}",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                    Text(
                                        "${fmtBytes(output.size)}  •  ${output.detail}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }
                        }
                        Text(
                            "Repack uses a cache temp file, validates the complete new UnityFS block table and payload checksum, then publishes. On failure the partial output is deleted and the source is left untouched.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "Format notes",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "Supported: unencrypted UnityFS v6–v8 with None, LZMA, LZ4 or LZ4HC blocks. Rejected with a reason: .blk/.pck/.usm, encrypted archives, version 9+ and bad signatures. Version 9 alignment is not implemented.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        item {
            Text(
                selfCheck ?: "Running UnityFS header parser self-check…",
                style = MaterialTheme.typography.labelSmall,
                color = if (selfCheck?.contains("OK") == true) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun MetaLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
            label,
            modifier = Modifier.weight(0.38f),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            modifier = Modifier.weight(0.62f),
            style = MaterialTheme.typography.bodySmall
        )
    }
}

