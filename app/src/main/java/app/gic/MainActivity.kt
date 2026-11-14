package app.gic

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

private val GicColors = darkColorScheme(
    primary = Color(0xFFD8B26A),
    onPrimary = Color(0xFF241C08),
    primaryContainer = Color(0xFF4A3A18),
    onPrimaryContainer = Color(0xFFFFE3A6),
    secondary = Color(0xFF91B4C9),
    onSecondary = Color(0xFF0B1E28),
    background = Color(0xFF0E1116),
    onBackground = Color(0xFFE6E9EE),
    surface = Color(0xFF141A21),
    onSurface = Color(0xFFE6E9EE),
    surfaceVariant = Color(0xFF222A33),
    onSurfaceVariant = Color(0xFFB9C3CF),
    outline = Color(0xFF7E8995),
    error = Color(0xFFFFB4AB),
    errorContainer = Color(0xFF5C1A16),
    onErrorContainer = Color(0xFFFFDAD6)
)

private val GamePackages = listOf(
    "com.miHoYo.GenshinImpact",
    "com.miHoYo.Yuanshen",
    "com.miHoYo.ys.bilibili"
)

class MainActivity : ComponentActivity() {
    private val resumeTick = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = GicColors) {
                Surface(Modifier.fillMaxSize()) {
                    App(resumeTick.intValue)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumeTick.intValue++
    }
}

@androidx.compose.runtime.Composable
private fun App(resumeTick: Int) {
    var pkg by rememberSaveable { androidx.compose.runtime.mutableStateOf(GamePackages[0]) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val tabs = listOf("Storage", "Data", "Unity")

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text(
                "Genshin Data Compressor",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                "Local storage, per-app traffic and UnityFS tools",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                GamePackages.forEach { candidate ->
                    FilterChip(
                        selected = pkg == candidate,
                        onClick = { pkg = candidate },
                        label = {
                            Text(candidate, fontFamily = FontFamily.Monospace)
                        }
                    )
                }
            }
        }

        TabRow(selectedTabIndex = tab) {
            tabs.forEachIndexed { index, name ->
                Tab(
                    selected = tab == index,
                    onClick = { tab = index },
                    text = { Text(name) }
                )
            }
        }

        Box(Modifier.weight(1f)) {
            when (tab) {
                0 -> StorageTab(pkg)
                1 -> DataTab(pkg, resumeTick)
                else -> UnityTab()
            }
        }

        Text(
            "Warning: Genshin assets are already compressed and partly encrypted, so zip savings are usually small. Deleting game files means the game must re-download them. Encrypted/custom containers are not unlocked by this app.",
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
