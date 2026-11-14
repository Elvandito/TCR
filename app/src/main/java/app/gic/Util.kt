package app.gic

import java.util.Locale

fun fmtBytes(bytes: Long): String {
    if (bytes < 0) return "—"
    val b = bytes.toDouble()
    return when {
        b >= 1024.0 * 1024 * 1024 -> String.format(Locale.US, "%.2f GB", b / (1024.0 * 1024 * 1024))
        b >= 1024.0 * 1024 -> String.format(Locale.US, "%.1f MB", b / (1024.0 * 1024))
        b >= 1024.0 -> String.format(Locale.US, "%.1f KB", b / 1024.0)
        else -> "$bytes B"
    }
}

fun fmtMb(bytes: Long): String =
    String.format(Locale.US, "%.1f MB", bytes.coerceAtLeast(0).toDouble() / (1024.0 * 1024.0))

fun errText(t: Throwable): String =
    (t.message ?: t.javaClass.simpleName).take(500)
