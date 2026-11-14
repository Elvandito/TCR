package app.gic

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import net.jpountz.lz4.LZ4Factory
import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.LZMAInputStream
import org.tukaani.xz.LZMAOutputStream
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.CRC32

class UnsupportedBundleException(message: String) : Exception(message)

internal data class UHeader(
    val signature: String,
    val version: Int,
    val unityVersion: String,
    val unityRevision: String,
    val fileSize: Long,
    val compressedInfoSize: Long,
    val uncompressedInfoSize: Long,
    val flags: Long
)

internal data class UBlock(
    val uncompressedSize: Long,
    val compressedSize: Long,
    val flags: Int
)

internal data class UNode(
    val offset: Long,
    val size: Long,
    val flags: Long,
    val path: String
)

internal data class UMeta(
    val name: String,
    val header: UHeader,
    val hash: ByteArray,
    val blocks: List<UBlock>,
    val nodes: List<UNode>,
    val fileLength: Long,
    val dataOffset: Long,
    val alignedHeader: Boolean,
    val infoAtEnd: Boolean
) {
    val totalUncompressed: Long = blocks.sumOf { it.uncompressedSize }
    val totalCompressedBlocks: Long = blocks.sumOf { it.compressedSize }

    val compressionLabel: String
        get() {
            val used = blocks.map { it.flags and 0x3f }.distinct()
            return when {
                used.isEmpty() -> compressionName(header.flags.toInt() and 0x3f)
                used.size == 1 -> compressionName(used[0])
                else -> "Mixed (${used.joinToString("/") { compressionName(it) }})"
            }
        }
}

data class PickedBundle(
    val name: String,
    val relativePath: String,
    val uri: Uri,
    val size: Long
)

data class UnityOutput(val name: String, val size: Long, val detail: String)

internal const val TARGET_NONE = 0
internal const val TARGET_LZMA = 1
internal const val TARGET_LZ4 = 2

private const val MAX_INFO_COMPRESSED = 128L * 1024 * 1024
private const val MAX_INFO_RAW = 128L * 1024 * 1024
private const val MAX_LZ4_BLOCK = 64L * 1024 * 1024
private const val MAX_LZMA_DICT = 64L * 1024 * 1024
private const val MAX_PATH_BYTES = 8192

private val lz4 by lazy { LZ4Factory.fastestJavaInstance() }

internal fun compressionName(code: Int): String = when (code) {
    0 -> "None"
    1 -> "LZMA"
    2 -> "LZ4"
    3 -> "LZ4HC"
    4 -> "LZHAM"
    else -> "Unknown ($code)"
}

private fun cStringBytes(input: InputStream, max: Int, what: String): ByteArray {
    val out = ByteArrayOutputStream()
    while (true) {
        val b = input.read()
        if (b < 0) throw UnsupportedBundleException("Truncated UnityFS header while reading $what")
        if (b == 0) break
        out.write(b)
        if (out.size() > max) throw UnsupportedBundleException("UnityFS $what is too long")
    }
    return out.toByteArray()
}

private fun readHeader(input: DataInputStream, what: String): UHeader {
    val signatureBytes = try {
        cStringBytes(input, 64, "signature")
    } catch (e: UnsupportedBundleException) {
        throw UnsupportedBundleException("Not a UnityFS bundle: unreadable signature")
    }
    val signature = String(signatureBytes, StandardCharsets.UTF_8)
    if (signature != "UnityFS") {
        val shown = signature.take(32).map { if (it.code in 32..126) it else '?' }.joinToString("")
        throw UnsupportedBundleException("Not a UnityFS bundle (signature '${shown}')")
    }
    val version = input.readInt()
    if (version < 0) throw UnsupportedBundleException("Invalid UnityFS format version")
    val unityVersion = String(cStringBytes(input, 256, "version string"), StandardCharsets.UTF_8)
    val unityRevision = String(cStringBytes(input, 256, "engine revision"), StandardCharsets.UTF_8)
    val size = input.readLong()
    val compressedInfoSize = input.readInt().toLong() and 0xFFFFFFFFL
    val uncompressedInfoSize = input.readInt().toLong() and 0xFFFFFFFFL
    val flags = input.readInt().toLong() and 0xFFFFFFFFL
    return UHeader(
        signature,
        version,
        unityVersion,
        unityRevision,
        size,
        compressedInfoSize,
        uncompressedInfoSize,
        flags
    )
}

private fun versionTriple(s: String): Triple<Int, Int, Int>? {
    val m = Regex("""^(\d+)\.(\d+)\.(\d+)""").find(s) ?: return null
    return Triple(m.groupValues[1].toIntOrNull() ?: return null,
        m.groupValues[2].toIntOrNull() ?: return null,
        m.groupValues[3].toIntOrNull() ?: return null)
}

private fun compare(v: Triple<Int, Int, Int>, a: Int, b: Int, c: Int = 0): Int {
    if (v.first != a) return v.first.compareTo(a)
    if (v.second != b) return v.second.compareTo(b)
    return v.third.compareTo(c)
}

private fun alignsHeader(h: UHeader): Boolean {
    if (h.version >= 7) return true
    val v = versionTriple(h.unityRevision) ?: return false
    return compare(v, 2019, 4, 15) >= 0
}

private fun oldArchiveFlags(h: UHeader): Boolean {
    val v = versionTriple(h.unityRevision) ?: return true
    if (v.first < 2020) return true
    return when (v.first) {
        2020 -> compare(v, 2020, 3, 34) < 0
        2021 -> compare(v, 2021, 3, 2) < 0
        2022 -> compare(v, 2022, 1, 1) < 0
        else -> false
    }
}

private fun align16(value: Long): Long = (value + 15L) and 15L.inv()

private fun unsupportedExtension(name: String): String? {
    val ext = name.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "blk" -> "Genshin .blk files are encrypted/custom containers. Only regular UnityFS AssetBundles are supported; nothing was written."
        "pck" -> ".pck files are Wwise audio packages, not UnityFS bundles; nothing was written."
        "usm" -> ".usm files are CriWare video containers, not UnityFS bundles; nothing was written."
        else -> null
    }
}

private fun readExactBytes(input: InputStream, size: Long, what: String): ByteArray {
    if (size < 0 || size > Int.MAX_VALUE) throw UnsupportedBundleException("$what is too large")
    val data = ByteArray(size.toInt())
    var off = 0
    while (off < data.size) {
        val n = input.read(data, off, data.size - off)
        if (n < 0) throw EOFException("Truncated $what")
        off += n
    }
    return data
}

private fun skipFully(input: InputStream, count: Long) {
    var left = count
    while (left > 0) {
        val skipped = input.skip(left)
        if (skipped > 0) {
            left -= skipped
        } else {
            if (input.read() < 0) throw EOFException("Unexpected end of source")
            left--
        }
    }
}

private fun openContentAt(cx: Context, uri: Uri, offset: Long): InputStream {
    val raw = cx.contentResolver.openInputStream(uri)
        ?: throw IOException("Cannot open the selected bundle")
    val buffered = BufferedInputStream(raw, 64 * 1024)
    skipFully(buffered, offset)
    return buffered
}

private fun decompressBytes(compressed: ByteArray, expectedLong: Long, code: Int, what: String): ByteArray {
    if (expectedLong < 0 || expectedLong > MAX_INFO_RAW || expectedLong > Int.MAX_VALUE) {
        throw UnsupportedBundleException("$what size $expectedLong is unsupported")
    }
    val expected = expectedLong.toInt()
    return when (code) {
        0 -> {
            if (compressed.size != expected) throw IOException("$what: stored size does not match")
            compressed
        }
        1 -> {
            if (compressed.size < 5) throw IOException("$what: truncated LZMA properties")
            val props = compressed[0].toInt() and 0xFF
            val dict = leInt(compressed, 1)
            val dictSize = dict.toLong() and 0xFFFFFFFFL
            if (dictSize > MAX_LZMA_DICT) {
                throw UnsupportedBundleException("$what: LZMA dictionary $dictSize exceeds ${MAX_LZMA_DICT / 1048576} MB")
            }
            try {
                val source = ByteArrayInputStream(compressed, 5, compressed.size - 5)
                val lz = LZMAInputStream(source, expectedLong, props.toByte(), dict)
                lz.enableRelaxedEndCondition()
                lz.use { input ->
                    val out = ByteArrayOutputStream(expected.coerceAtLeast(1))
                    val buf = ByteArray(64 * 1024)
                    var total = 0
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > expected) throw IOException("$what: LZMA output overflow")
                        out.write(buf, 0, n)
                    }
                    if (total != expected) throw IOException("$what: LZMA produced $total of $expected bytes")
                    out.toByteArray()
                }
            } catch (e: UnsupportedBundleException) {
                throw e
            } catch (e: Exception) {
                throw IOException("$what: LZMA decompression failed: ${errText(e)}")
            }
        }
        2, 3 -> {
            if (compressed.size > Int.MAX_VALUE || expected > Int.MAX_VALUE) throw UnsupportedBundleException("$what is too large")
            try {
                val out = ByteArray(expected)
                val n = lz4.safeDecompressor().decompress(compressed, 0, compressed.size, out, 0, expected)
                if (n != expected) throw IOException("$what: LZ4 produced $n of $expected bytes")
                out
            } catch (e: Exception) {
                throw IOException("$what: LZ4 decompression failed: ${errText(e)}")
            }
        }
        else -> throw UnsupportedBundleException("$what uses unsupported compression ${compressionName(code)}")
    }
}

private fun leInt(bytes: ByteArray, offset: Int): Int =
    (bytes[offset].toInt() and 0xFF) or
        ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
        ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
        ((bytes[offset + 3].toInt() and 0xFF) shl 24)

private fun readLeInt(input: InputStream): Int {
    val b = readExactBytes(input, 4, "LZMA dictionary")
    return leInt(b, 0)
}

private class CountingInputStream(input: InputStream) : FilterInputStream(input) {
    var count: Long = 0
        private set

    override fun read(): Int {
        val v = super.read()
        if (v >= 0) count++
        return v
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = super.read(b, off, len)
        if (n > 0) count += n
        return n
    }

    override fun skip(n: Long): Long {
        val skipped = super.skip(n)
        if (skipped > 0) count += skipped
        return skipped
    }
}

private class LimitedInput(private val source: InputStream, private var remaining: Long) : InputStream() {
    override fun read(): Int {
        if (remaining <= 0) return -1
        val v = source.read()
        if (v >= 0) remaining--
        return v
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (remaining <= 0) return -1
        val want = minOf(len.toLong(), remaining).toInt()
        val n = source.read(b, off, want)
        if (n > 0) remaining -= n
        return n
    }

    override fun close() {
        // The caller still needs the underlying bundle stream for subsequent blocks.
    }

    fun drain() {
        val buf = ByteArray(8192)
        while (remaining > 0) {
            val n = read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
            if (n < 0) break
        }
    }
}

private fun parseBlocksInfo(bytes: ByteArray): Triple<ByteArray, List<UBlock>, List<UNode>> {
    val din = DataInputStream(ByteArrayInputStream(bytes))
    val hash = ByteArray(16)
    din.readFully(hash)
    val blockCount = din.readInt()
    if (blockCount < 0 || blockCount > 1_000_000) throw IOException("Invalid block count $blockCount")
    val blocks = ArrayList<UBlock>(blockCount)
    var total = 0L
    repeat(blockCount) {
        val unc = din.readInt().toLong() and 0xFFFFFFFFL
        val comp = din.readInt().toLong() and 0xFFFFFFFFL
        val flags = din.readUnsignedShort()
        val code = flags and 0x3f
        if (code !in 0..3) throw UnsupportedBundleException("Block uses unsupported compression ${compressionName(code)}")
        if (unc > Long.MAX_VALUE - total) throw IOException("Block sizes overflow")
        total += unc
        blocks.add(UBlock(unc, comp, flags))
    }
    val nodeCount = din.readInt()
    if (nodeCount < 0 || nodeCount > 1_000_000) throw IOException("Invalid file count $nodeCount")
    val nodes = ArrayList<UNode>(nodeCount)
    repeat(nodeCount) {
        val offset = din.readLong()
        val size = din.readLong()
        val flags = din.readInt().toLong() and 0xFFFFFFFFL
        val path = String(cStringBytes(din, MAX_PATH_BYTES, "file path"), StandardCharsets.UTF_8)
        if (offset < 0 || size < 0) throw IOException("Directory entry has a negative offset/size")
        if (offset > total || size > total - offset) {
            throw IOException("Directory entry '$path' points outside the uncompressed blocks")
        }
        nodes.add(UNode(offset, size, flags, path))
    }
    return Triple(hash, blocks, nodes)
}

/**
 * Parses a UnityFS header, compressed block/directory table and validates it without
 * decompressing the content blocks. The caller supplies positioned reads.
 */
internal fun analyzeBundle(
    name: String,
    suppliedLength: Long,
    openAt: (Long) -> InputStream
): UMeta {
    unsupportedExtension(name)?.let { throw UnsupportedBundleException(it) }

    lateinit var header: UHeader
    var headerEnd = 0L
    var inlineInfo: ByteArray? = null
    var infoOffset = 0L
    var firstCount = 0L

    openAt(0).use { raw ->
        val counting = CountingInputStream(BufferedInputStream(raw, 64 * 1024))
        val din = DataInputStream(counting)
        header = readHeader(din, name)
        if (header.version !in 6..8) {
            throw UnsupportedBundleException(
                "UnityFS format version ${header.version} is not supported (this build supports 6, 7 and 8)."
            )
        }
        val lowCompression = header.flags.toInt() and 0x3f
        if (lowCompression !in 0..3) {
            throw UnsupportedBundleException("Block table uses unsupported compression ${compressionName(lowCompression)}")
        }
        if (header.flags and 0x40L == 0L) {
            throw UnsupportedBundleException("UnityFS has no combined block/directory table (flag 0x40 is clear)")
        }
        val oldFlags = oldArchiveFlags(header)
        if (header.flags and 0x1400L != 0L || (oldFlags && header.flags and 0x200L != 0L)) {
            throw UnsupportedBundleException(
                "UnityFS asset-bundle encryption is enabled (flags 0x${header.flags.toString(16)}). Encrypted bundles are not supported."
            )
        }
        if (alignsHeader(header)) {
            val pad = (16 - (counting.count % 16).toInt()) % 16
            if (pad > 0) {
                skipFully(din, pad.toLong())
            }
        }
        headerEnd = counting.count
        firstCount = headerEnd
        if (header.flags and 0x80L == 0L) {
            if (header.compressedInfoSize > MAX_INFO_COMPRESSED) {
                throw UnsupportedBundleException("UnityFS block table exceeds the 128 MB safety limit")
            }
            inlineInfo = readExactBytes(din, header.compressedInfoSize, "block table")
        }
    }

    val sourceLength = when {
        suppliedLength > 0 -> suppliedLength
        header.fileSize > 0 -> header.fileSize
        else -> 0L
    }
    if (sourceLength <= 0) throw IOException("Cannot determine bundle length")
    if (headerEnd > sourceLength) throw IOException("UnityFS header is larger than the file")

    val infoAtEnd = header.flags and 0x80L != 0L
    val compressedInfo: ByteArray
    val infoLimit: Long
    if (infoAtEnd) {
        infoOffset = sourceLength - header.compressedInfoSize
        if (header.compressedInfoSize > MAX_INFO_COMPRESSED) {
            throw UnsupportedBundleException("UnityFS block table exceeds the 128 MB safety limit")
        }
        if (infoOffset < headerEnd) throw IOException("Block table offset overlaps the header")
        compressedInfo = openAt(infoOffset).use { raw ->
            DataInputStream(BufferedInputStream(raw, 64 * 1024)).let {
                readExactBytes(it, header.compressedInfoSize, "block table")
            }
        }
        infoLimit = infoOffset
    } else {
        compressedInfo = inlineInfo ?: throw IOException("Block table is missing")
        infoLimit = sourceLength
    }

    if (header.uncompressedInfoSize > MAX_INFO_RAW) {
        throw UnsupportedBundleException("UnityFS block table expands beyond the 128 MB safety limit")
    }
    val infoCode = header.flags.toInt() and 0x3f
    val infoBytes = decompressBytes(compressedInfo, header.uncompressedInfoSize, infoCode, "block table")
    val (hash, blocks, nodes) = parseBlocksInfo(infoBytes)

    var dataOffset = if (infoAtEnd) headerEnd else firstCount + header.compressedInfoSize
    // Newer archives may set 0x200 to request 16-byte alignment before block data.
    if (header.flags and 0x200L != 0L) dataOffset = align16(dataOffset)
    val dataEnd = blocks.sumOf { it.compressedSize }
    if (dataOffset > infoLimit || dataEnd > infoLimit - dataOffset) {
        throw IOException("Block data extends outside the bundle")
    }

    return UMeta(
        name = name,
        header = header,
        hash = hash,
        blocks = blocks,
        nodes = nodes,
        fileLength = sourceLength,
        dataOffset = dataOffset,
        alignedHeader = alignsHeader(header),
        infoAtEnd = infoAtEnd
    )
}

internal fun analyzeUri(cx: Context, picked: PickedBundle): UMeta {
    val measured = contentSize(cx, picked.uri)
    val length = if (measured > 0) measured else picked.size
    return analyzeBundle(picked.name, length) { offset -> openContentAt(cx, picked.uri, offset) }
}

private fun writeHeader(
    header: UHeader,
    totalSize: Long,
    compressedInfoSize: Long,
    uncompressedInfoSize: Long,
    flags: Int
): ByteArray {
    val bos = ByteArrayOutputStream()
    val out = DataOutputStream(bos)
    out.write("UnityFS".toByteArray(StandardCharsets.UTF_8))
    out.writeByte(0)
    out.writeInt(header.version)
    out.write(header.unityVersion.toByteArray(StandardCharsets.UTF_8))
    out.writeByte(0)
    out.write(header.unityRevision.toByteArray(StandardCharsets.UTF_8))
    out.writeByte(0)
    out.writeLong(totalSize)
    out.writeInt(compressedInfoSize.toInt())
    out.writeInt(uncompressedInfoSize.toInt())
    out.writeInt(flags)
    if (header.version >= 7 || alignsHeader(header)) {
        val pad = (16 - (bos.size() % 16)) % 16
        repeat(pad) { out.writeByte(0) }
    }
    out.flush()
    return bos.toByteArray()
}

private fun encodeChunk(raw: ByteArray, target: Int): Pair<ByteArray, Int> {
    return when (target) {
        TARGET_NONE -> raw to 0
        TARGET_LZ4 -> {
            try {
                val dest = ByteArray(lz4.fastCompressor().maxCompressedLength(raw.size))
                val n = lz4.fastCompressor().compress(raw, 0, raw.size, dest, 0, dest.size)
                if (n <= 0 || n >= raw.size) raw to 0 else dest.copyOf(n) to 2
            } catch (e: Exception) {
                throw IOException("LZ4 compression failed: ${errText(e)}")
            }
        }
        TARGET_LZMA -> {
            try {
                val options = LZMA2Options(6)
                val bos = ByteArrayOutputStream()
                val lz = LZMAOutputStream(bos, options, true)
                val props = lz.props
                lz.write(raw)
                lz.close()
                val compressed = bos.toByteArray()
                if (compressed.size + 5 >= raw.size) {
                    raw to 0
                } else {
                    val result = ByteArray(5 + compressed.size)
                    result[0] = props.toByte()
                    val d = options.dictSize
                    result[1] = (d and 0xFF).toByte()
                    result[2] = ((d ushr 8) and 0xFF).toByte()
                    result[3] = ((d ushr 16) and 0xFF).toByte()
                    result[4] = ((d ushr 24) and 0xFF).toByte()
                    System.arraycopy(compressed, 0, result, 5, compressed.size)
                    result to 1
                }
            } catch (e: Exception) {
                throw IOException("LZMA compression failed: ${errText(e)}")
            }
        }
        else -> throw IllegalArgumentException("Unknown compression target")
    }
}

private class ChunkEncoder(
    private val target: Int,
    private val dataOut: OutputStream,
    chunkSize: Int
) : OutputStream() {
    val blocks = ArrayList<UBlock>()
    private val buffer = ByteArray(chunkSize)
    private var used = 0
    private val crc = CRC32()
    var total: Long = 0
        private set

    override fun write(b: Int) {
        write(byteArrayOf(b.toByte()), 0, 1)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        var p = off
        var left = len
        crc.update(b, off, len)
        total += len
        while (left > 0) {
            val n = minOf(left, buffer.size - used)
            System.arraycopy(b, p, buffer, used, n)
            used += n
            p += n
            left -= n
            if (used == buffer.size) emit()
        }
    }

    private fun emit() {
        if (used == 0) return
        val raw = buffer.copyOf(used)
        val encoded = encodeChunk(raw, target)
        dataOut.write(encoded.first)
        blocks.add(UBlock(raw.size.toLong(), encoded.first.size.toLong(), encoded.second))
        used = 0
    }

    fun finish() {
        emit()
        dataOut.flush()
    }

    fun checksum(): Long = crc.value
}

private fun chunkSizeFor(target: Int): Int = when (target) {
    TARGET_LZ4 -> 128 * 1024
    TARGET_LZMA -> 4 * 1024 * 1024
    else -> 1024 * 1024
}

private fun writeBlockInfo(meta: UMeta, blocks: List<UBlock>): ByteArray {
    val bos = ByteArrayOutputStream()
    val out = DataOutputStream(bos)
    out.write(meta.hash)
    out.writeInt(blocks.size)
    for (b in blocks) {
        if (b.uncompressedSize > 0xFFFFFFFFL || b.compressedSize > 0xFFFFFFFFL) {
            throw IOException("Block size cannot be represented in UnityFS")
        }
        out.writeInt(b.uncompressedSize.toInt())
        out.writeInt(b.compressedSize.toInt())
        out.writeShort(b.flags and 0xFFFF)
    }
    out.writeInt(meta.nodes.size)
    for (n in meta.nodes) {
        out.writeLong(n.offset)
        out.writeLong(n.size)
        out.writeInt(n.flags.toInt())
        out.write(n.path.toByteArray(StandardCharsets.UTF_8))
        out.writeByte(0)
    }
    out.flush()
    return bos.toByteArray()
}

private class DiscardCrcSink : OutputStream() {
    private val crc = CRC32()
    var count: Long = 0
        private set

    override fun write(b: Int) {
        crc.update(b)
        count++
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        crc.update(b, off, len)
        count += len
    }

    fun checksum(): Long = crc.value
}

private fun decompressBlock(input: DataInputStream, block: UBlock, out: OutputStream): Long {
    val code = block.flags and 0x3f
    if (block.uncompressedSize == 0L) {
        skipFully(input, block.compressedSize)
        return 0
    }
    when (code) {
        0 -> {
            if (block.compressedSize != block.uncompressedSize) {
                throw IOException("Uncompressed block size mismatch")
            }
            var left = block.compressedSize
            var written = 0L
            val buf = ByteArray(64 * 1024)
            while (left > 0) {
                val n = input.read(buf, 0, minOf(left, buf.size.toLong()).toInt())
                if (n < 0) throw EOFException("Truncated uncompressed block")
                out.write(buf, 0, n)
                written += n
                left -= n
            }
            return written
        }
        1 -> {
            if (block.compressedSize < 5) throw IOException("Truncated LZMA block")
            val props = input.readUnsignedByte()
            val dict = readLeInt(input)
            val dictSize = dict.toLong() and 0xFFFFFFFFL
            if (dictSize > MAX_LZMA_DICT) {
                throw UnsupportedBundleException("LZMA dictionary $dictSize exceeds the supported 64 MB")
            }
            if (block.uncompressedSize < 0 || block.uncompressedSize > 0xFFFFFFFFL) {
                throw UnsupportedBundleException("LZMA block is too large")
            }
            val limited = LimitedInput(input, block.compressedSize - 5)
            var total = 0L
            try {
                val lz = LZMAInputStream(
                    limited,
                    block.uncompressedSize,
                    props.toByte(),
                    dict
                )
                lz.enableRelaxedEndCondition()
                try {
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = lz.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        total += n
                        if (total > block.uncompressedSize) throw IOException("LZMA block overran its declared size")
                    }
                } finally {
                    try {
                        lz.close()
                    } catch (_: Exception) {
                    }
                }
                limited.drain()
            } catch (e: UnsupportedBundleException) {
                throw e
            } catch (e: Exception) {
                throw IOException("LZMA block failed: ${errText(e)}")
            }
            if (total != block.uncompressedSize) {
                throw IOException("LZMA block produced $total of ${block.uncompressedSize} bytes")
            }
            return total
        }
        2, 3 -> {
            if (block.compressedSize > MAX_LZ4_BLOCK || block.uncompressedSize > MAX_LZ4_BLOCK) {
                throw UnsupportedBundleException("LZ4 block exceeds the supported 64 MB safety limit")
            }
            val comp = readExactBytes(input, block.compressedSize, "LZ4 block")
            try {
                val outBytes = ByteArray(block.uncompressedSize.toInt())
                val n = lz4.safeDecompressor().decompress(
                    comp, 0, comp.size, outBytes, 0, outBytes.size
                )
                if (n != outBytes.size) throw IOException("LZ4 produced $n of ${outBytes.size} bytes")
                out.write(outBytes)
                return outBytes.size.toLong()
            } catch (e: UnsupportedBundleException) {
                throw e
            } catch (e: Exception) {
                throw IOException("LZ4 block failed: ${errText(e)}")
            }
        }
        else -> throw UnsupportedBundleException("Unsupported block compression ${compressionName(code)}")
    }
}

private fun extractPayloadTo(
    meta: UMeta,
    openAt: (Long) -> InputStream,
    output: OutputStream,
    onProgress: (Long, Long) -> Unit
) {
    openAt(meta.dataOffset).use { raw ->
        val din = DataInputStream(BufferedInputStream(raw, 64 * 1024))
        var done = 0L
        for (block in meta.blocks) {
            val produced = decompressBlock(din, block, output)
            if (produced != block.uncompressedSize) {
                throw IOException("Block size mismatch: $produced != ${block.uncompressedSize}")
            }
            done += produced
            onProgress(done, meta.totalUncompressed)
        }
    }
}

private fun baseName(name: String): String {
    val withoutExt = name.substringBeforeLast('.', name)
    val cleaned = withoutExt.replace(Regex("""[^A-Za-z0-9._-]+"""), "_").trim('_', '.')
    return cleaned.ifBlank { "bundle" }.take(96)
}

private fun uniqueName(dir: DocumentFile, desired: String): String {
    if (dir.findFile(desired) == null) return desired
    val dot = desired.lastIndexOf('.')
    val stem = if (dot > 0) desired.substring(0, dot) else desired
    val ext = if (dot > 0) desired.substring(dot) else ""
    var i = 2
    while (i < 10000) {
        val candidate = "$stem ($i)$ext"
        if (dir.findFile(candidate) == null) return candidate
        i++
    }
    return "$stem-${System.currentTimeMillis()}$ext"
}

private fun publishToTree(cx: Context, treeUri: Uri, desired: String, temp: File): String {
    val dir = DocumentFile.fromTreeUri(cx, treeUri)
        ?: throw IOException("Output folder is unavailable")
    if (!dir.isDirectory) throw IOException("Output selection is not a folder")
    val name = uniqueName(dir, desired)
    val doc = dir.createFile("application/octet-stream", name)
        ?: throw IOException("Could not create $name in the output folder")
    try {
        val out = cx.contentResolver.openOutputStream(doc.uri, "w")
            ?: throw IOException("Could not open output document")
        out.use { target ->
            temp.inputStream().use { source -> source.copyTo(target, 64 * 1024) }
        }
        val written = contentSize(cx, doc.uri)
        if (written > 0 && written != temp.length()) {
            throw IOException("Output copy was incomplete ($written of ${temp.length()} bytes)")
        }
        return name
    } catch (e: Exception) {
        runCatching { doc.delete() }
        throw e
    }
}

private fun tempFile(cx: Context, suffix: String): File =
    File.createTempFile("gic-unity-", suffix, cx.cacheDir)

internal fun extractRawBundle(
    cx: Context,
    picked: PickedBundle,
    outputTree: Uri,
    onProgress: (Long, Long) -> Unit
): UnityOutput {
    val meta = analyzeUri(cx, picked)
    val temp = tempFile(cx, ".raw")
    try {
        FileOutputStream(temp).use { fileOut ->
            BufferedOutputStream(fileOut, 64 * 1024).use { out ->
                extractPayloadTo(meta, { offset -> openContentAt(cx, picked.uri, offset) }, out, onProgress)
            }
        }
        if (temp.length() != meta.totalUncompressed) {
            throw IOException("Extracted size does not match block table")
        }
        val desired = "${baseName(picked.name)}.decompressed.raw"
        val name = publishToTree(cx, outputTree, desired, temp)
        return UnityOutput(name, temp.length(), "Raw concatenated uncompressed blocks")
    } finally {
        temp.delete()
    }
}

private fun localOpenAt(file: File): (Long) -> InputStream = { offset ->
    val input = BufferedInputStream(FileInputStream(file), 64 * 1024)
    skipFully(input, offset)
    input
}

private fun verifyRepacked(
    path: File,
    source: UMeta,
    expectedCrc: Long,
    expectedSize: Long
) {
    val meta = analyzeBundle(path.name, path.length(), localOpenAt(path))
    if (meta.nodes != source.nodes) throw IOException("Verification failed: directory table changed")
    if (meta.totalUncompressed != expectedSize) throw IOException("Verification failed: size changed")
    val sink = DiscardCrcSink()
    extractPayloadTo(meta, localOpenAt(path), sink) { _, _ -> }
    if (sink.count != expectedSize) throw IOException("Verification failed: wrong payload size")
    if (sink.checksum() != expectedCrc) throw IOException("Verification failed: payload checksum differs")
}

internal fun repackBundle(
    cx: Context,
    picked: PickedBundle,
    outputTree: Uri,
    target: Int,
    onProgress: (Long, Long) -> Unit
): UnityOutput {
    val meta = analyzeUri(cx, picked)
    if (meta.totalUncompressed == 0L) throw UnsupportedBundleException("Bundle has no block data to repack")
    val finalTemp = tempFile(cx, ".bundle")
    val dataTemp = tempFile(cx, ".data")
    try {
        var blocks: List<UBlock> = emptyList()
        var rawSize = 0L
        var rawCrc = 0L
        FileOutputStream(dataTemp).use { fileOut ->
            BufferedOutputStream(fileOut, 64 * 1024).use { dataOut ->
                val encoder = ChunkEncoder(target, dataOut, chunkSizeFor(target))
                openContentAt(cx, picked.uri, meta.dataOffset).use { raw ->
                    val din = DataInputStream(BufferedInputStream(raw, 64 * 1024))
                    var done = 0L
                    for (block in meta.blocks) {
                        val produced = decompressBlock(din, block, encoder)
                        if (produced != block.uncompressedSize) throw IOException("Source block size mismatch")
                        done += produced
                        onProgress(done, meta.totalUncompressed)
                    }
                }
                encoder.finish()
                if (encoder.total != meta.totalUncompressed) {
                    throw IOException("Source payload ended at ${encoder.total} of ${meta.totalUncompressed} bytes")
                }
                blocks = encoder.blocks.toList()
                rawSize = encoder.total
                rawCrc = encoder.checksum()
            }
        }

        val rawInfo = writeBlockInfo(meta, blocks)
        val encodedInfo = encodeChunk(rawInfo, target)
        val infoFlag = encodedInfo.second
        val headerFlags = 0x40 or infoFlag
        val placeholder = writeHeader(
            meta.header,
            0L,
            encodedInfo.first.size.toLong(),
            rawInfo.size.toLong(),
            headerFlags
        )
        val total = placeholder.size.toLong() + encodedInfo.first.size + dataTemp.length()
        val header = writeHeader(
            meta.header,
            total,
            encodedInfo.first.size.toLong(),
            rawInfo.size.toLong(),
            headerFlags
        )
        FileOutputStream(finalTemp).use { fileOut ->
            BufferedOutputStream(fileOut, 64 * 1024).use { out ->
                out.write(header)
                out.write(encodedInfo.first)
                dataTemp.inputStream().use { it.copyTo(out, 64 * 1024) }
            }
        }
        verifyRepacked(finalTemp, meta, rawCrc, rawSize)
        val label = compressionName(target).lowercase()
        val desired = "${baseName(picked.name)}.repacked.$label.bundle"
        val name = publishToTree(cx, outputTree, desired, finalTemp)
        return UnityOutput(
            name,
            finalTemp.length(),
            "${compressionName(target)}; ${blocks.size} blocks; verified before publishing"
        )
    } finally {
        finalTemp.delete()
        dataTemp.delete()
    }
}

/** Small executable sanity check for the independent UnityFS header reader. */
internal fun unityHeaderSelfCheck(): String {
    val bos = ByteArrayOutputStream()
    val out = DataOutputStream(bos)
    out.write("UnityFS".toByteArray(StandardCharsets.UTF_8))
    out.writeByte(0)
    out.writeInt(7)
    out.write("5.x.x".toByteArray(StandardCharsets.UTF_8)); out.writeByte(0)
    out.write("2021.3.16f1".toByteArray(StandardCharsets.UTF_8)); out.writeByte(0)
    out.writeLong(4096L)
    out.writeInt(41)
    out.writeInt(80)
    out.writeInt(0x42)
    out.flush()
    val h = readHeader(DataInputStream(ByteArrayInputStream(bos.toByteArray())), "self-check")
    check(h.signature == "UnityFS") { "signature" }
    check(h.version == 7) { "version" }
    check(h.fileSize == 4096L) { "file size" }
    check(h.compressedInfoSize == 41L) { "compressed block-table size" }
    check(h.uncompressedInfoSize == 80L) { "uncompressed block-table size" }
    check(h.flags and 0x3f == 2L) { "compression flags" }
    check(alignsHeader(h)) { "alignment decision" }
    return "UnityFS header self-check: OK"
}
