package app.gic

import android.Manifest
import android.app.Activity
import android.app.AppOpsManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneId

private data class UsageWindow(val label: String, val start: Long)
private data class UsageRow(val label: String, val mobile: Long?, val wifi: Long?)
private data class UsageResult(val rows: List<UsageRow>, val note: String?)

fun hasUsageAccess(context: Context): Boolean {
    val ops = context.getSystemService(AppOpsManager::class.java)
    val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        ops.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        )
    } else {
        ops.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        )
    }
    return mode == AppOpsManager.MODE_ALLOWED
}

private fun queryNetwork(
    stats: NetworkStatsManager,
    type: Int,
    uid: Int,
    start: Long,
    end: Long
): Long {
    val result = stats.queryDetailsForUid(type, null, start, end, uid)
    var bytes = 0L
    val bucket = NetworkStats.Bucket()
    try {
        while (result.hasNextBucket()) {
            if (!result.getNextBucket(bucket)) break
            bytes += bucket.rxBytes + bucket.txBytes
        }
    } finally {
        result.close()
    }
    return bytes
}

private fun queryUsage(context: Context, pkg: String): UsageResult {
    val uid = try {
        context.packageManager.getApplicationInfo(pkg, 0).uid
    } catch (_: PackageManager.NameNotFoundException) {
        throw IOException("$pkg is not installed on this device")
    }
    val nsm = context.getSystemService(NetworkStatsManager::class.java)
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
    val now = System.currentTimeMillis()
    val periods = listOf(
        UsageWindow("Today", today),
        UsageWindow("7 days", LocalDate.now(zone).minusDays(6).atStartOfDay(zone).toInstant().toEpochMilli()),
        UsageWindow("30 days", LocalDate.now(zone).minusDays(29).atStartOfDay(zone).toInstant().toEpochMilli())
    )
    val notes = LinkedHashSet<String>()
    val rows = periods.map { window ->
        fun read(type: Int, what: String): Long? = try {
            queryNetwork(nsm, type, uid, window.start, now)
        } catch (e: Exception) {
            notes.add("$what: ${errText(e)}")
            null
        }
        UsageRow(window.label, read(ConnectivityManager.TYPE_MOBILE, "Mobile"), read(ConnectivityManager.TYPE_WIFI, "Wi-Fi"))
    }
    return UsageResult(rows, notes.joinToString(" ").takeIf { it.isNotBlank() })
}

@Composable
fun DataTab(pkg: String, resumeTick: Int) {
    val cx = LocalContext.current

    var allowed by remember { mutableStateOf<Boolean?>(null) }
    var loading by remember { mutableStateOf(false) }
    var rows by remember { mutableStateOf<List<UsageRow>>(emptyList()) }
    var usageError by remember { mutableStateOf<String?>(null) }
    var usageNote by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }

    LaunchedEffect(pkg, resumeTick, refresh) {
        loading = true
        usageError = null
        usageNote = null
        try {
            val ok = withContext(Dispatchers.IO) { hasUsageAccess(cx) }
            allowed = ok
            if (ok) {
                val result = withContext(Dispatchers.IO) { queryUsage(cx, pkg) }
                rows = result.rows
                usageNote = result.note
            } else {
                rows = emptyList()
            }
        } catch (e: Exception) {
            usageError = errText(e)
            rows = emptyList()
        } finally {
            loading = false
        }
    }

    val guardRunning by GuardState.running.collectAsState()
    val guardPackage by GuardState.activePackage.collectAsState()
    val guardMessage by GuardState.message.collectAsState()

    fun startGuardNow() {
        GuardState.message.value = "Starting guard for $pkg…"
        val intent = Intent(cx, GuardService::class.java)
            .setAction(GuardService.ACTION_START)
            .putExtra(GuardService.EXTRA_PACKAGE, pkg)
        try {
            cx.startForegroundService(intent)
        } catch (e: Exception) {
            GuardState.message.value = "Could not start guard: ${errText(e)}"
        }
    }

    val vpnConsent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            startGuardNow()
        } else {
            GuardState.message.value = "Android VPN permission was not granted."
        }
    }

    fun requestVpnConsent() {
        try {
            val intent = VpnService.prepare(cx)
            if (intent == null) startGuardNow() else vpnConsent.launch(intent)
        } catch (e: Exception) {
            GuardState.message.value = "VPN setup failed: ${errText(e)}"
        }
    }

    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        // The guard is still useful without a visible notification; Android decides whether
        // to draw the required foreground-service notification.
        requestVpnConsent()
    }

    fun enableGuard() {
        if (Build.VERSION.SDK_INT >= 33 &&
            cx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            requestVpnConsent()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Data", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Selected package: $pkg",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace
        )

        Card {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Per-app traffic", style = MaterialTheme.typography.titleMedium)
                    if (allowed == true) {
                        TextButton(onClick = { refresh++ }) { Text("Refresh") }
                    }
                }

                if (allowed == null) {
                    Text("Checking usage-access permission…", style = MaterialTheme.typography.bodySmall)
                } else if (allowed == false) {
                    Text(
                        "Usage access is not granted. Android requires this special permission for NetworkStatsManager to report another app's bytes.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            try {
                                cx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                            } catch (e: Exception) {
                                usageError = "Could not open Settings: ${errText(e)}"
                            }
                        }) { Text("Open usage access") }
                        OutlinedButton(onClick = { refresh++ }) { Text("Check again") }
                    }
                } else {
                    Text("Usage access granted.", style = MaterialTheme.typography.bodySmall)
                    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Period", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                        Text("Mobile", Modifier.weight(1.1f), style = MaterialTheme.typography.labelLarge)
                        Text("Wi-Fi", Modifier.weight(1.1f), style = MaterialTheme.typography.labelLarge)
                    }
                    for (row in rows) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(row.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            Text(
                                row.mobile?.let(::fmtBytes) ?: "—",
                                Modifier.weight(1.1f),
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                row.wifi?.let(::fmtBytes) ?: "—",
                                Modifier.weight(1.1f),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                    if (rows.isEmpty() && !loading) {
                        Text("No usage buckets returned.", style = MaterialTheme.typography.bodySmall)
                    }
                    Text(
                        "Counts are mobile + Wi-Fi bytes used by the selected package, including both received and sent traffic. Device/OEM support for per-UID buckets varies.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(onClick = { refresh++ }) { Text("Reload stats") }
                }

                usageNote?.let {
                    Text("Some buckets unavailable. $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                usageError?.let {
                    Text("Usage error: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        }

        Card {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Mobile-data guard", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (guardRunning) "Blocks ${guardPackage ?: pkg} only" else "Off",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Switch(
                        checked = guardRunning,
                        onCheckedChange = { on ->
                            if (on) {
                                enableGuard()
                            } else {
                                GuardState.message.value = "Stopping guard…"
                                cx.stopService(Intent(cx, GuardService::class.java))
                            }
                        }
                    )
                }
                if (guardRunning && guardPackage != null && guardPackage != pkg) {
                    Text(
                        "The running guard still blocks $guardPackage. Turn it off and on to apply the newly selected package.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Text(guardMessage, style = MaterialTheme.typography.bodySmall)
                Text(
                    "A local VpnService captures only this package's traffic, using addAllowedApplication, and holds the tunnel without reading or forwarding any packet. Android must show a persistent notification. The guard stops itself when Wi-Fi is available. It does not reduce traffic on servers' side and works only on mobile data.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "Privacy: no packet is inspected, logged, modified, or forwarded by this app.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        if (Build.VERSION.SDK_INT >= 33) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Text(
                    "Android 13+ needs notification permission for the persistent guard notice. If denied, the service can still run but the system may hide its notice.",
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

object GuardState {
    val running = MutableStateFlow(false)
    val activePackage = MutableStateFlow<String?>(null)
    val message = MutableStateFlow("Guard is off. Nothing is being blocked.")
}

class GuardService : VpnService() {
    companion object {
        const val ACTION_START = "app.gic.guard.START"
        const val ACTION_STOP = "app.gic.guard.STOP"
        const val EXTRA_PACKAGE = "app.gic.guard.PACKAGE"
        private const val CHANNEL_ID = "mobile_guard"
        private const val NOTIFICATION_ID = 731
    }

    private var tun: ParcelFileDescriptor? = null
    private var wifiCallback: ConnectivityManager.NetworkCallback? = null
    private var stopping = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopGuard("Guard stopped.")
            return START_NOT_STICKY
        }

        stopping = false
        createChannel()
        val pkg = intent?.getStringExtra(EXTRA_PACKAGE)
        startForeground(NOTIFICATION_ID, notification(pkg))
        if (pkg.isNullOrBlank()) {
            stopGuard("Guard could not start: no package selected.")
            return START_NOT_STICKY
        }
        if (wifiConnected()) {
            stopGuard("Guard stopped: Wi-Fi is active.")
            return START_NOT_STICKY
        }
        GuardState.message.value = "Establishing local tunnel…"
        closeTunnel()

        val builder = Builder()
            .setSession("GIC mobile-data guard")
            .setMtu(1500)
            .addAddress("10.66.0.2", 32)
            .addRoute("0.0.0.0", 0)
        // Also cover IPv6 so the selected app cannot bypass the guard through IPv6.
        runCatching {
            builder.addAddress("fd00:66::2", 128)
            builder.addRoute("::", 0)
        }
        try {
            builder.addAllowedApplication(pkg)
        } catch (e: Exception) {
            stopGuard("Guard could not find $pkg: ${errText(e)}")
            return START_NOT_STICKY
        }

        val fd = try {
            builder.establish()
        } catch (e: Exception) {
            stopGuard("Guard could not establish the tunnel: ${errText(e)}")
            return START_NOT_STICKY
        }
        if (fd == null) {
            stopGuard("Android refused the VPN tunnel. Check VPN permission and any existing VPN.")
            return START_NOT_STICKY
        }

        // Deliberately never read from or write to `fd`. Packets routed into this tunnel are dropped
        // by the kernel; nothing is inspected, logged, modified or forwarded.
        tun = fd
        GuardState.running.value = true
        GuardState.activePackage.value = pkg
        GuardState.message.value = "Blocking mobile data for $pkg. The guard auto-stops on Wi-Fi."
        registerWifiWatcher()
        return START_NOT_STICKY
    }

    private fun notification(pkg: String?): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, GuardService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_guard)
            .setContentTitle(getString(R.string.guard_title))
            .setContentText(if (pkg != null) "Blocking $pkg on mobile data only" else "Starting…")
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_guard),
                    "Stop",
                    stop
                ).build()
            )
            .build()
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.guard_channel),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.guard_channel_desc)
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    private fun wifiConnected(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    private fun registerWifiWatcher() {
        if (wifiCallback != null) return
        val cm = getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                stopGuard("Guard stopped: Wi-Fi is active.")
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    stopGuard("Guard stopped: Wi-Fi is active.")
                }
            }
        }
        try {
            cm.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .build(),
                callback
            )
            wifiCallback = callback
        } catch (e: Exception) {
            // The start-of-tunnel check still applies; failure to monitor a later Wi-Fi
            // transition is surfaced in the persistent message instead of crashing.
            GuardState.message.value = "Guard active, but Wi-Fi auto-stop could not be registered: ${errText(e)}"
        }
    }

    private fun closeTunnel() {
        runCatching { tun?.close() }
        tun = null
    }

    private fun stopGuard(reason: String) {
        if (stopping) return
        stopping = true
        wifiCallback?.let { runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } }
        wifiCallback = null
        closeTunnel()
        GuardState.running.value = false
        GuardState.activePackage.value = null
        GuardState.message.value = reason
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        wifiCallback?.let { runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } }
        wifiCallback = null
        closeTunnel()
        GuardState.running.value = false
        GuardState.activePackage.value = null
        super.onDestroy()
    }

    override fun onRevoke() {
        stopGuard("Guard stopped: VPN permission was revoked.")
        super.onRevoke()
    }
}
