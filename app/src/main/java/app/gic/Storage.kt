package app.gic

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Paths are relative to the game's `files` directory. delete() takes one top-level name. */
interface Src {
    fun list(): List<Pair<String, Long>>
    fun read(p: String): InputStream
    fun write(p: String): OutputStream
    fun delete(top: String)
    /** Deletes a top-level folder or a safe nested path (used by the optimizer). */
    fun deletePath(p: String) {
        require(!p.contains('/')) { "Implement deletePath for nested paths" }
        delete(p)
    }
    fun done() {}
}

private fun q(s: String): String = "'" + s.replace("'", "'\\''") + "'"

private fun validRel(p: String) {
    require(p.isNotBlank()) { "Empty path" }
    require(!p.startsWith('/')) { "Absolute path rejected: $p" }
    require(!p.contains('\\')) { "Backslash path rejected: $p" }
    require(!p.contains('\u0000')) { "NUL path rejected" }
    require(p.split('/').all { it.isNotEmpty() && it != "." && it != ".." }) { "Unsafe path rejected: $p" }
}

private fun validTop(p: String) {
    require(p.isNotBlank()) { "Empty folder name" }
    require(!p.contains('/') && !p.contains('\\')) { "Not a top-level name: $p" }
    require(p != "." && p != "..") { "Unsafe folder name" }
}

/**
 * Root source: every operation goes through one `su -c` shell, with paths single-quoted.
 * Nothing is silently treated as success; a non-zero su/cat exit becomes an IOException.
 */
class RootSrc(private val base: String) : Src {
    private fun start(cmd: String): Process = ProcessBuilder("su", "-c", cmd).start()

    private fun drainError(p: Process): Pair<StringBuilder, Thread> {
        val text = StringBuilder()
        val t = Thread {
            try {
                p.errorStream.bufferedReader().use { r ->
                    while (text.length < 4000) {
                        val line = r.readLine() ?: break
                        text.append(line).append('\n')
                    }
                }
            } catch (_: Exception) {
            }
        }
        t.isDaemon = true
        t.start()
        return text to t
    }

    private fun run(cmd: String): String {
        val p = start(cmd)
        p.outputStream.close()
        val (err, errThread) = drainError(p)
        val out = p.inputStream.bufferedReader().use { it.readText() }
        val code = p.waitFor()
        errThread.join(1000)
        if (code != 0) {
            val detail = err.toString().trim().ifBlank { "exit $code" }
            throw IOException("su failed: $detail")
        }
        return out
    }

    override fun list(): List<Pair<String, Long>> {
        val b = q(base)
        val cmd =
            "test -d $b || { echo 'Game files folder not found or not accessible' >&2; exit 3; }; " +
                "find $b -type f -exec stat -c '%s|%n' {} +"
        val text = run(cmd)
        return text.lineSequence().mapNotNull { line ->
            val i = line.indexOf('|')
            if (i <= 0) return@mapNotNull null
            val size = line.substring(0, i).toLongOrNull() ?: return@mapNotNull null
            val full = line.substring(i + 1)
            val rel = if (full.startsWith("$base/")) full.removePrefix("$base/") else return@mapNotNull null
            if (rel.isBlank()) null else rel to size
        }.toList()
    }

    private fun fullPath(p: String): String {
        validRel(p)
        return "$base/$p"
    }

    override fun read(p: String): InputStream {
        val path = fullPath(p)
        val proc = start("cat ${q(path)}")
        proc.outputStream.close()
        val (err, errThread) = drainError(proc)
        return object : FilterInputStream(BufferedInputStream(proc.inputStream)) {
            private var closed = false
            override fun close() {
                if (closed) return
                closed = true
                var failure: Throwable? = null
                try {
                    super.close()
                } catch (t: Throwable) {
                    failure = t
                }
                val code = proc.waitFor()
                errThread.join(1000)
                if (code != 0) {
                    val detail = err.toString().trim().ifBlank { "exit $code" }
                    throw IOException("Root read failed for $p: $detail")
                }
                failure?.let { throw it }
            }
        }
    }

    override fun write(p: String): OutputStream {
        val full = fullPath(p)
        val parent = full.substringBeforeLast('/')
        val proc = start("mkdir -p ${q(parent)} && cat > ${q(full)}")
        val (err, errThread) = drainError(proc)
        return object : FilterOutputStream(BufferedOutputStream(proc.outputStream)) {
            private var closed = false
            override fun write(b: ByteArray, off: Int, len: Int) {
                out.write(b, off, len)
            }

            override fun close() {
                if (closed) return
                closed = true
                try {
                    super.close()
                } finally {
                    val code = proc.waitFor()
                    errThread.join(1000)
                    if (code != 0) {
                        val detail = err.toString().trim().ifBlank { "exit $code" }
                        throw IOException("Root write failed for $p: $detail")
                    }
                }
            }
        }
    }

    override fun delete(top: String) {
        validTop(top)
        run("rm -rf ${q("$base/$top")}")
    }

    override fun deletePath(p: String) {
        validRel(p)
        run("rm -rf ${q("$base/$p")}")
    }

    /** Restored files must once again belong to the game and carry its SELinux label. */
    override fun done() {
        val gameDir = q(base.substringBeforeLast('/'))
        val b = q(base)
        run(
            "u=\$(stat -c %u $gameDir) || exit 4; g=\$(stat -c %g $gameDir) || exit 4; " +
                "chown -R \"\$u:\$g\" $b; restorecon -R $b"
        )
    }
}

/**
 * SAF source. The selected tree is normally primary:Android/data/<pkg>; if the user picked
 * that package folder, we transparently descend into its `files` child.
 * The platform only allows this access to Android/data on Android 11-12 (some OEM builds differ).
 */
class SafSrc(private val cx: Context, uri: Uri) : Src {
    private val resolver = cx.contentResolver
    private val root: DocumentFile

    init {
        val picked = DocumentFile.fromTreeUri(cx, uri)
            ?: throw IOException("The selected SAF tree is not available")
        val files = picked.findFile("files")
        root = if (picked.name != "files" && files != null && files.isDirectory) files else picked
        if (!root.isDirectory) throw IOException("The selected item is not a folder")
    }

    val displayName: String get() = root.name ?: "selected folder"

    private val cache = HashMap<String, DocumentFile>()

    override fun list(): List<Pair<String, Long>> {
        cache.clear()
        val out = ArrayList<Pair<String, Long>>()
        fun walk(dir: DocumentFile, prefix: String) {
            for (f in dir.listFiles()) {
                val name = f.name ?: continue
                val rel = prefix + name
                if (f.isDirectory) {
                    walk(f, "$rel/")
                } else {
                    cache[rel] = f
                    out.add(rel to f.length())
                }
            }
        }
        walk(root, "")
        return out
    }

    private fun find(p: String): DocumentFile {
        cache[p]?.let { return it }
        validRel(p)
        var d = root
        val parts = p.split('/')
        for (part in parts.dropLast(1)) {
            d = d.findFile(part)?.takeIf { it.isDirectory }
                ?: throw IOException("Folder not found: $part")
        }
        return d.findFile(parts.last()) ?: throw IOException("File not found: $p")
    }

    override fun read(p: String): InputStream {
        val f = find(p)
        if (f.isDirectory) throw IOException("Not a file: $p")
        return resolver.openInputStream(f.uri) ?: throw IOException("Cannot open $p")
    }

    override fun write(p: String): OutputStream {
        validRel(p)
        var parent = root
        val parts = p.split('/')
        for (name in parts.dropLast(1)) {
            val existing = parent.findFile(name)
            parent = when {
                existing == null -> parent.createDirectory(name)
                    ?: throw IOException("Cannot create folder $name")
                existing.isDirectory -> existing
                else -> throw IOException("A file already blocks folder $name")
            }
        }
        val name = parts.last()
        val prior = parent.findFile(name)
        if (prior != null) {
            if (prior.isDirectory) throw IOException("A folder already has the name $name")
            if (!prior.delete()) throw IOException("Cannot replace $p")
        }
        var doc = parent.createFile("application/octet-stream", name)
            ?: throw IOException("Cannot create $p")
        if (doc.name != name) {
            val renamed = runCatching { DocumentsContract.renameDocument(resolver, doc.uri, name) }.getOrNull()
            if (renamed == null) {
                doc.delete()
                throw IOException("Provider changed the file name; cannot create $name")
            }
            doc = DocumentFile.fromSingleUri(cx, renamed) ?: doc
        }
        val stream = resolver.openOutputStream(doc.uri, "w")
        if (stream == null) {
            doc.delete()
            throw IOException("Cannot open $p for writing")
        }
        return object : FilterOutputStream(BufferedOutputStream(stream)) {
            override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
        }
    }

    private fun remove(doc: DocumentFile) {
        if (doc.isDirectory) {
            for (child in doc.listFiles()) remove(child)
        }
        if (!doc.delete()) throw IOException("Could not delete ${doc.name ?: "item"}")
    }

    override fun delete(top: String) {
        validTop(top)
        val doc = root.findFile(top) ?: return
        remove(doc)
    }

    override fun deletePath(p: String) {
        validRel(p)
        remove(find(p))
    }
}

fun contentSize(cx: Context, uri: Uri): Long {
    runCatching {
        cx.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
            if (afd.length >= 0) return afd.length
        }
    }
    runCatching {
        cx.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val i = c.getColumnIndex(OpenableColumns.SIZE)
                if (i >= 0 && !c.isNull(i)) return c.getLong(i)
            }
        }
    }
    return DocumentFile.fromSingleUri(cx, uri)?.length() ?: 0L
}

/**
 * Writes selected top-level folders as a zip. Progress is reported in uncompressed bytes.
 * Compression level: 0 = store-like deflate, 1 = fast, 9 = max.
 */
fun zipSelected(
    cx: Context,
    zipUri: Uri,
    src: Src,
    entries: List<Pair<String, Long>>,
    level: Int,
    onProgress: (Long, Long, String) -> Unit
) {
    val total = entries.sumOf { it.second }
    var done = 0L
    var nextReport = 0L
    val raw = cx.contentResolver.openOutputStream(zipUri)
        ?: throw IOException("Cannot open the zip output")
    ZipOutputStream(BufferedOutputStream(raw)).use { z ->
        z.setLevel(level)
        for ((path, declared) in entries.sortedBy { it.first }) {
            onProgress(done, total, path)
            z.putNextEntry(ZipEntry(path))
            src.read(path).use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (n == 0) continue
                    z.write(buf, 0, n)
                    done += n
                    if (done >= nextReport) {
                        onProgress(done, total, path)
                        nextReport = done + (1024 * 1024)
                    }
                }
            }
            z.closeEntry()
            if (declared <= 0) onProgress(done, total, path)
        }
    }
    onProgress(done, total, "done")
}

private fun safeZipEntry(raw: String): String {
    require(raw.isNotBlank()) { "Zip contains an empty entry name" }
    require(!raw.startsWith('/') && !raw.startsWith('\\')) { "Zip entry rejected (absolute): $raw" }
    require(!raw.contains('\\')) { "Zip entry rejected (backslash): $raw" }
    require(!raw.contains('\u0000')) { "Zip entry rejected (NUL)" }
    val name = raw.removeSuffix("/")
    val parts = name.split('/')
    require(parts.all { it.isNotEmpty() && it != "." && it != ".." }) {
        "Zip entry rejected (unsafe): $raw"
    }
    return name
}

/** Extracts a zip into the game folder. `..`, absolute paths and backslashes are rejected. */
fun restoreZip(
    cx: Context,
    zipUri: Uri,
    src: Src,
    onProgress: (Int, String) -> Unit
): Int {
    val input = cx.contentResolver.openInputStream(zipUri)
        ?: throw IOException("Cannot open the selected zip")
    var count = 0
    ZipInputStream(BufferedInputStream(input)).use { z ->
        while (true) {
            val entry: ZipEntry = z.nextEntry ?: break
            val name = safeZipEntry(entry.name)
            if (!entry.isDirectory) {
                src.write(name).use { out -> z.copyTo(out, 64 * 1024) }
                count++
                onProgress(count, name)
            }
            z.closeEntry()
        }
    }
    src.done()
    return count
}
