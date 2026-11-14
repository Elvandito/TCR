package app.gic

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.github.luben.zstd.ZstdOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.XZOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale

const val SAMPLE_CAP_BYTES = 64L * 1024 * 1024
private const val MAX_SAMPLE_FILES = 64
private const val ZSTD_LEVEL = 19
private const val XZ_PRESET = 6

enum class GroupKind { CLEANUP, OPT_IN, DATA }

data class GroupHint(
    val kind: GroupKind,
    val label: String,
    val reason: String
)

data class OptGroup(
    val path: String,
    val bytes: Long,
    val files: Int,
    val hint: GroupHint
) {
    val depth: Int get() = path.count { it == '/' } + 1
}

data class ScanModel(
    val files: List<Pair<String, Long>>,
    val total: Long,
    val top: List<OptGroup>,
    val second: Map<String, List<OptGroup>>,
    val cleanup: List<OptGroup>,
    val optIn: List<OptGroup>
)

data class Estimate(
    val path: String,
    val original: Long,
    val sample: Long,
    val zstd: Long,
    val xz: Long,
    val projected: Long,
    val error: String? = null
) {
    val best: Long get() = minOf(zstd, xz)
    val measuredRatio: Double
        get() = if (error == null && sample > 0) {
            (1.0 - best.toDouble() / sample).coerceIn(0.0, 1.0)
        } else {
            0.0
        }
    val savings: Long get() = (original - projected).coerceAtLeast(0)
}

data class ReportStep(
    val label: String,
    val before: Long,
    val after: Long,
    val removed: List<String>,
    val optIn: Boolean,
    val note: String = ""
) {
    val saved: Long get() = (before - after).coerceAtLeast(0)
}

data class RecompressSupport(
    val listed: List<String>,
    val root: Boolean,
    val reason: String
)

private val cleanupWords = setOf(
    "cache", "caches", "log", "logs", "temp", "temps", "tmp", "crash", "crashes", "dump", "dumps"
)

private val mediaWords = setOf(
    "audio", "audios", "voice", "voices", "vo", "video", "videos", "movie", "movies",
    "cutscene", "cutscenes", "cinematic", "cinematics", "sound", "sounds", "bink"
)

private fun nameMatches(segment: String, word: String): Boolean {
    if (segment == word || segment == "${word}s") return true
    return segment.startsWith("$word-") || segment.startsWith("${word}_") ||
        segment.startsWith("$word.")
}

private fun classify(path: String, parent: GroupHint? = null): GroupHint {
    val segments = path.lowercase(Locale.US).split('/').filter { it.isNotBlank() }
    val cleanup = segments.firstOrNull { segment -> cleanupWords.any { nameMatches(segment, it) } }
    if (cleanup != null) {
        return GroupHint(
            GroupKind.CLEANUP,
            "Cleanup name hint",
            "Name segment '$cleanup' matched a generic cache/log/temp/crash keyword. It is not proof of safety."
        )
    }
    val media = segments.firstOrNull { segment -> mediaWords.any { nameMatches(segment, it) } }
    if (media != null) {
        return GroupHint(
            GroupKind.OPT_IN,
            "Media name hint",
            "Name segment '$media' matched audio/voice/video keywords. Removing it is opt-in and may remove gameplay media."
        )
    }
    if (parent != null && parent.kind != GroupKind.DATA) {
        return GroupHint(
            parent.kind,
            "Inherited name hint",
            "Parent folder matched a ${parent.kind.name.lowercase(Locale.US)} keyword; verify the actual contents."
        )
    }
    return GroupHint(
        GroupKind.DATA,
        "App data",
        "No generic cleanup or media keyword in the name. Treat it as required game data."
    )
}

fun buildScan(files: List<Pair<String, Long>>): ScanModel {
    val total = files.sumOf { it.second }
    val topMap = LinkedHashMap<String, MutableList<Pair<String, Long>>>()
    for (entry in files) {
        val top = entry.first.substringBefore('/')
        if (top.isBlank()) continue
        topMap.getOrPut(top) { ArrayList() }.add(entry)
    }
    val topHints = HashMap<String, GroupHint>()
    val topGroups = topMap.map { (path, list) ->
        val hint = classify(path)
        topHints[path] = hint
        OptGroup(path, list.sumOf { it.second }, list.size, hint)
    }.sortedByDescending { it.bytes }

    val secondMap = LinkedHashMap<String, MutableList<Pair<String, Long>>>()
    for ((path, size) in files) {
        val parts = path.split('/')
        if (parts.size < 3) continue
        val key = parts[0] + "/" + parts[1]
        secondMap.getOrPut(key) { ArrayList() }.add(path to size)
    }
    val secondMutable = HashMap<String, MutableList<OptGroup>>()
    for ((path, list) in secondMap) {
        val top = path.substringBefore('/')
        val hint = classify(path, topHints[top])
        val group = OptGroup(path, list.sumOf { it.second }, list.size, hint)
        secondMutable.getOrPut(top) { ArrayList() }.add(group)
    }
    val secondGroups: Map<String, List<OptGroup>> =
        secondMutable.mapValues { (_, list) -> list.sortedByDescending { it.bytes } }

    val cleanup = ArrayList<OptGroup>()
    val optIn = ArrayList<OptGroup>()
    for (top in topGroups) {
        if (top.hint.kind == GroupKind.CLEANUP) {
            cleanup.add(top)
            continue
        }
        val children = secondGroups[top.path].orEmpty()
        if (top.hint.kind == GroupKind.OPT_IN) {
            if (children.isEmpty()) optIn.add(top) else optIn.addAll(children)
        } else {
            cleanup.addAll(children.filter { it.hint.kind == GroupKind.CLEANUP })
            val mediaKids = children.filter { it.hint.kind == GroupKind.OPT_IN }
            if (mediaKids.isNotEmpty()) optIn.addAll(mediaKids)
        }
    }

    return ScanModel(
        files = files,
        total = total,
        top = topGroups,
        second = secondGroups,
        cleanup = cleanup.distinctBy { it.path }.sortedByDescending { it.bytes },
        optIn = optIn.distinctBy { it.path }.sortedByDescending { it.bytes }
    )
}

fun filesUnder(files: List<Pair<String, Long>>, paths: Collection<String>): List<Pair<String, Long>> {
    val roots = paths.sortedBy { it.length }
    return files.filter { (path, _) ->
        roots.any { root -> path == root || path.startsWith("$root/") }
    }
}

fun sizeUnder(files: List<Pair<String, Long>>, paths: Collection<String>): Long =
    filesUnder(files, paths).sumOf { it.second }

private fun minimalPaths(paths: Collection<String>): List<String> {
    val out = ArrayList<String>()
    for (path in paths.sortedBy { it.length }) {
        if (out.none { path.startsWith("$it/") }) out.add(path)
    }
    return out
}

private data class SamplePlan(
    val group: OptGroup,
    val entries: List<Pair<String, Long>>,
    val bytes: Long
)

private fun planSamples(files: List<Pair<String, Long>>, groups: List<OptGroup>): List<SamplePlan> {
    return groups.mapNotNull { group ->
        val groupFiles = files.filter { it.first.startsWith("${group.path}/") }.sortedBy { it.first }
        if (groupFiles.isEmpty() || group.bytes <= 0) return@mapNotNull null
        val step = (groupFiles.size / MAX_SAMPLE_FILES).coerceAtLeast(1)
        var budget = SAMPLE_CAP_BYTES
        val entries = ArrayList<Pair<String, Long>>()
        var picked = 0
        for (i in groupFiles.indices) {
            if (picked >= MAX_SAMPLE_FILES || budget <= 0) break
            if (i % step != 0) continue
            val (path, size) = groupFiles[i]
            if (size <= 0) continue
            val take = minOf(size, budget)
            entries.add(path to take)
            budget -= take
            picked++
        }
        if (entries.isEmpty()) null
        else SamplePlan(group, entries, SAMPLE_CAP_BYTES - budget)
    }
}

private class CountSink : OutputStream() {
    var count: Long = 0
        private set

    override fun write(b: Int) {
        count++
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        count += len
    }
}

private class TeeOutputStream(
    private val first: OutputStream,
    private val second: OutputStream
) : OutputStream() {
    override fun write(b: Int) {
        first.write(b)
        second.write(b)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        first.write(b, off, len)
        second.write(b, off, len)
    }

    override fun flush() {
        first.flush()
        second.flush()
    }

    override fun close() {
        var failure: Throwable? = null
        try {
            first.close()
        } catch (t: Throwable) {
            failure = t
        }
        try {
            second.close()
        } catch (t: Throwable) {
            if (failure == null) failure = t
        }
        failure?.let { throw it }
    }
}

private data class CompressedSample(val sample: Long, val zstd: Long, val xz: Long)

private fun compressPlan(
    src: Src,
    plan: SamplePlan,
    onRead: (Long) -> Unit
): CompressedSample {
    val zCount = CountSink()
    val xCount = CountSink()
    val sink = TeeOutputStream(
        ZstdOutputStream(zCount, ZSTD_LEVEL),
        XZOutputStream(xCount, LZMA2Options(XZ_PRESET))
    )
    var totalRead = 0L
    val buffer = ByteArray(256 * 1024)
    try {
        for ((path, limit) in plan.entries) {
            var left = limit
            if (left <= 0) continue
            src.read(path).use { input: InputStream ->
                while (left > 0) {
                    val want = minOf(buffer.size.toLong(), left).toInt()
                    val n = input.read(buffer, 0, want)
                    if (n < 0) break
                    if (n == 0) continue
                    sink.write(buffer, 0, n)
                    totalRead += n
                    left -= n
                    onRead(n.toLong())
                }
            }
        }
    } finally {
        sink.close()
    }
    if (totalRead <= 0) throw IOException("No readable sample bytes")
    return CompressedSample(totalRead, zCount.count, xCount.count)
}

internal fun estimatorSelfCheck(): String {
    val data = ByteArray(512 * 1024)
    val zCount = CountSink()
    val xCount = CountSink()
    ZstdOutputStream(zCount, ZSTD_LEVEL).use { it.write(data) }
    XZOutputStream(xCount, LZMA2Options(XZ_PRESET)).use { it.write(data) }
    check(zCount.count > 0 && zCount.count < data.size) { "zstd self-check did not compress" }
    check(xCount.count > 0 && xCount.count < data.size) { "xz self-check did not compress" }
    return "Estimator self-check OK: ${fmtBytes(data.size.toLong())} zeros -> " +
        "zstd ${fmtBytes(zCount.count)} / xz ${fmtBytes(xCount.count)}"
}

internal fun readProcFilesystems(): List<String> {
    val process = ProcessBuilder("su", "-c", "cat /proc/filesystems")
        .redirectErrorStream(true)
        .start()
    process.outputStream.close()
    val text = process.inputStream.bufferedReader().use { it.readText() }
    val code = process.waitFor()
    if (code != 0) throw IOException("su failed reading /proc/filesystems: ${text.trim().take(300)}")
    return text.lineSequence()
        .map { it.trim().removePrefix("nodev").trim() }
        .filter { it.isNotBlank() }
        .map { it.substringAfterLast(' ') }
        .toList()
}

internal fun evaluateRecompress(listed: List<String>): RecompressSupport {
    val present = listOf("erofs", "squashfs", "f2fs").filter { it in listed }
    val reason = if (present.isEmpty()) {
        "Kernel /proc/filesystems lists none of erofs, squashfs or f2fs. " +
            "No files were rewritten; there is no verified lossless in-place compression path."
    } else {
        "Kernel lists ${present.joinToString(", ")}. Standard Android exposes no verified " +
            "per-file in-place compression API for writable game data. Replacing files with " +
            "zstd/xz bytes would break the game, so 0 files were rewritten."
    }
    return RecompressSupport(present, true, reason)
}

/**
 * Process-wide Optimize state. Work runs on the main dispatcher with disk I/O on
 * Dispatchers.IO, so it survives tab changes without touching UI from a background thread.
 */
object OptimizerState {
    var packageName by mutableStateOf("")
        private set
    var rootMode by mutableStateOf(true)
        private set
    var treeUri by mutableStateOf<Uri?>(null)
        private set
    var treeName by mutableStateOf<String?>(null)
        private set
    var files by mutableStateOf<List<Pair<String, Long>>>(emptyList())
        private set
    var top by mutableStateOf<List<OptGroup>>(emptyList())
        private set
    var second by mutableStateOf<Map<String, List<OptGroup>>>(emptyMap())
        private set
    var cleanup by mutableStateOf<List<OptGroup>>(emptyList())
        private set
    var optIn by mutableStateOf<List<OptGroup>>(emptyList())
        private set
    var total by mutableStateOf(0L)
        private set
    var estimates by mutableStateOf<Map<String, Estimate>>(emptyMap())
        private set
    var baseline by mutableStateOf<Long?>(null)
        private set
    var steps by mutableStateOf<List<ReportStep>>(emptyList())
        private set
    var cleanupSelected by mutableStateOf<Set<String>>(emptySet())
    var optInSelected by mutableStateOf<Set<String>>(emptySet())
    var level by mutableStateOf(1)
    var busy by mutableStateOf(false)
        private set
    var progress by mutableStateOf<Float?>(null)
        private set
    var status by mutableStateOf("")
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var recompress by mutableStateOf<RecompressSupport?>(null)
        private set

    private var job: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val base: String get() = "/storage/emulated/0/Android/data/$packageName/files"
    val savedBytes: Long get() = ((baseline ?: total) - total).coerceAtLeast(0)
    val projectedBytes: Long get() = estimates.values.sumOf { it.savings }

    fun resetForPackage(pkg: String) {
        if (pkg == packageName) return
        job?.cancel()
        packageName = pkg
        rootMode = true
        treeUri = null
        treeName = null
        clearResults()
        level = 1
    }

    fun useMode(root: Boolean) {
        if (busy || rootMode == root) return
        rootMode = root
        treeUri = null
        treeName = null
        clearResults()
    }

    fun selectTree(cx: Context, uri: Uri) {
        if (busy) return
        runCatching {
            cx.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
        rootMode = false
        treeUri = uri
        treeName = null
        clearResults()
        job = scope.launch {
            busy = true
            status = "Checking selected folder..."
            try {
                val name = withContext(Dispatchers.IO) { SafSrc(cx, uri).displayName }
                treeName = name
                status = "Folder selected: $name. Tap Scan."
            } catch (e: Exception) {
                treeUri = null
                error = "Folder unusable: ${errText(e)}"
                status = ""
            } finally {
                busy = false
            }
        }
    }

    private fun source(cx: Context): Src = if (rootMode) {
        RootSrc(base)
    } else {
        val uri = treeUri ?: throw IOException("Pick the game's files folder first")
        SafSrc(cx, uri)
    }

    private fun clearResults() {
        files = emptyList()
        top = emptyList()
        second = emptyMap()
        cleanup = emptyList()
        optIn = emptyList()
        total = 0
        estimates = emptyMap()
        baseline = null
        steps = emptyList()
        cleanupSelected = emptySet()
        optInSelected = emptySet()
        busy = false
        progress = null
        status = ""
        error = null
        recompress = null
    }

    private fun applyScan(list: List<Pair<String, Long>>) {
        val model = buildScan(list)
        files = model.files
        top = model.top
        second = model.second
        cleanup = model.cleanup
        optIn = model.optIn
        total = model.total
        cleanupSelected = emptySet()
        optInSelected = emptySet()
        estimates = emptyMap()
        recompress = null
        if (baseline == null) baseline = model.total
    }

    fun sizeOf(paths: Collection<String>): Long = sizeUnder(files, paths)

    fun scan(cx: Context) {
        if (busy) return
        job = scope.launch {
            busy = true
            error = null
            progress = null
            status = "Scanning $base ..."
            try {
                val list = withContext(Dispatchers.IO) { source(cx).list() }
                applyScan(list)
                status = "Scanned ${list.size} files, ${fmtBytes(total)} in ${top.size} top-level groups."
            } catch (e: Exception) {
                error = "Scan failed: ${errText(e)}"
                status = ""
            } finally {
                busy = false
                progress = null
            }
        }
    }

    fun estimate(cx: Context) {
        if (busy) return
        if (files.isEmpty()) {
            error = "Scan the game files first."
            return
        }
        val groups = top
        job = scope.launch {
            busy = true
            error = null
            progress = 0f
            status = "Planning samples (up to 64 MB and 64 files per group)..."
            try {
                val src = withContext(Dispatchers.IO) { source(cx) }
                val plans = planSamples(files, groups)
                if (plans.isEmpty()) throw IOException("No groups with readable files to sample")
                val totalSample = plans.sumOf { it.bytes }
                var read = 0L
                var lastPublish = 0L
                val results = LinkedHashMap<String, Estimate>()
                for (plan in plans) {
                    status = "Sampling ${plan.group.path} (${fmtBytes(plan.bytes)} max)"
                    val result = try {
                        withContext(Dispatchers.IO) {
                            val raw = compressPlan(src, plan) { n ->
                                read += n
                                if (read - lastPublish >= 2L * 1024 * 1024) {
                                    lastPublish = read
                                    val fraction = (read.toDouble() / totalSample.toDouble())
                                        .toFloat().coerceIn(0f, 1f)
                                    scope.launch { progress = fraction }
                                }
                            }
                            val best = minOf(raw.zstd, raw.xz)
                            val projected = ((plan.group.bytes.toDouble() * best) / raw.sample)
                                .toLong().coerceIn(0L, plan.group.bytes)
                            Estimate(
                                plan.group.path,
                                plan.group.bytes,
                                raw.sample,
                                raw.zstd,
                                raw.xz,
                                projected
                            )
                        }
                    } catch (e: Exception) {
                        Estimate(
                            plan.group.path,
                            plan.group.bytes,
                            0,
                            0,
                            0,
                            plan.group.bytes,
                            errText(e)
                        )
                    }
                    results[plan.group.path] = result
                    estimates = results.toMap()
                }
                progress = 1f
                status = "Sample projection: ${fmtBytes(projectedBytes)} " +
                    "(${pct(projectedBytes, total)} of install) could be saved. Measurements only; nothing changed."
            } catch (e: Exception) {
                error = "Estimator failed: ${errText(e)}"
                status = ""
            } finally {
                busy = false
                progress = null
            }
        }
    }

    fun checkRecompress() {
        if (busy) return
        if (!rootMode) {
            recompress = RecompressSupport(
                emptyList(),
                false,
                "Root mode is required to inspect /proc/filesystems. No files were rewritten."
            )
            return
        }
        job = scope.launch {
            busy = true
            error = null
            status = "Reading /proc/filesystems through root..."
            try {
                val listed = withContext(Dispatchers.IO) { readProcFilesystems() }
                recompress = evaluateRecompress(listed)
                status = "Kernel compression capability check complete. No files were rewritten."
            } catch (e: Exception) {
                recompress = RecompressSupport(
                    emptyList(),
                    true,
                    "Could not read /proc/filesystems: ${errText(e)}. No files were rewritten."
                )
                status = ""
            } finally {
                busy = false
            }
        }
    }

    fun backupAll(cx: Context, zipUri: Uri) {
        if (busy) return
        if (files.isEmpty()) {
            error = "Scan the game files first."
            return
        }
        job = scope.launch {
            busy = true
            error = null
            progress = 0f
            status = "Writing full backup..."
            try {
                withContext(Dispatchers.IO) {
                    val src = source(cx)
                    val current = src.list()
                    if (current.isEmpty()) throw IOException("No files to back up")
                    zipSelected(cx, zipUri, src, current, level) { done, totalBytes, path ->
                        val fraction = if (totalBytes > 0) {
                            done.toFloat() / totalBytes.toFloat()
                        } else {
                            null
                        }
                        scope.launch {
                            progress = fraction
                            status = "Backup ${fmtMb(done)} / ${fmtMb(totalBytes)} - $path"
                        }
                    }
                }
                progress = 1f
                status = "Full backup complete at zip level $level."
            } catch (e: Exception) {
                error = "Backup failed: ${errText(e)}"
                status = ""
            } finally {
                busy = false
                progress = null
            }
        }
    }

    fun backupAndDelete(
        cx: Context,
        zipUri: Uri,
        paths: Collection<String>,
        label: String,
        optIn: Boolean
    ) {
        if (busy) return
        val selected = minimalPaths(paths)
        if (selected.isEmpty()) {
            error = "Nothing selected."
            return
        }
        job = scope.launch {
            busy = true
            error = null
            progress = 0f
            val before = total
            status = "Backing up ${selected.size} item(s) before deletion..."
            try {
                val result = withContext(Dispatchers.IO) {
                    val src = source(cx)
                    val current = src.list()
                    val entries = filesUnder(current, selected)
                    if (entries.isEmpty()) throw IOException("Selected items contain no files")
                    zipSelected(cx, zipUri, src, entries, level) { done, totalBytes, path ->
                        val fraction = if (totalBytes > 0) {
                            done.toFloat() / totalBytes.toFloat()
                        } else {
                            null
                        }
                        scope.launch {
                            progress = fraction
                            status = "Backup ${fmtMb(done)} / ${fmtMb(totalBytes)} - $path"
                        }
                    }
                    val removed = ArrayList<String>()
                    var failure: Exception? = null
                    for (path in selected) {
                        try {
                            src.deletePath(path)
                            removed.add(path)
                        } catch (e: Exception) {
                            failure = e
                            break
                        }
                    }
                    val newList = try {
                        src.list()
                    } catch (e: Exception) {
                        if (failure == null) failure = e
                        files
                    }
                    Triple(newList, removed, failure)
                }
                val (newList, removed, failure) = result
                applyScan(newList)
                steps = steps + ReportStep(
                    label,
                    before,
                    total,
                    removed,
                    optIn,
                    if (failure == null) "" else "Deletion stopped: ${errText(failure)}"
                )
                if (optIn) {
                    optInSelected = optInSelected - selected.toSet()
                } else {
                    cleanupSelected = cleanupSelected - selected.toSet()
                }
                progress = 1f
                status = "Backup saved. Removed ${removed.size}/${selected.size} selected item(s); " +
                    "freed ${fmtBytes((before - total).coerceAtLeast(0))}."
                if (failure != null) {
                    error = "Backup succeeded, but deletion stopped: ${errText(failure)}"
                }
            } catch (e: Exception) {
                error = "Nothing deleted. Backup was not completed: ${errText(e)}"
                status = ""
            } finally {
                busy = false
                progress = null
            }
        }
    }

    fun restore(cx: Context, zipUri: Uri) {
        if (busy) return
        job = scope.launch {
            busy = true
            error = null
            progress = null
            status = "Restoring backup..."
            try {
                val list = withContext(Dispatchers.IO) {
                    val src = source(cx)
                    restoreZip(cx, zipUri, src) { count, name ->
                        scope.launch { status = "Restoring $count files - $name" }
                    }
                    src.list()
                }
                applyScan(list)
                baseline = total
                steps = emptyList()
                status = "Backup restored into $pkg/files. Baseline reset to ${fmtBytes(total)}."
            } catch (e: Exception) {
                error = "Restore failed: ${errText(e)}"
                status = ""
            } finally {
                busy = false
            }
        }
    }
}

fun pct(part: Long, whole: Long): String {
    if (whole <= 0) return "0.0%"
    return String.format(Locale.US, "%.1f%%", part.toDouble() * 100.0 / whole.toDouble())
}
