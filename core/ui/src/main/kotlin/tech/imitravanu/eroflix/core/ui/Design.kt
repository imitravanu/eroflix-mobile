package tech.imitravanu.eroflix.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private val colors = darkColorScheme(
    primary = Color(0xFFE11D48),
    onPrimary = Color(0xFFFFFFFF),
    background = Color(0xFF0A0A0C),
    surface = Color(0xFF141418),
    surfaceVariant = Color(0xFF1E1E24),
    onBackground = Color(0xFFF4F4F5),
    onSurface = Color(0xFFF4F4F5),
    onSurfaceVariant = Color(0xFFA1A1AA),
    secondary = Color(0xFFF59E0B),
    onSecondary = Color(0xFF000000)
)

@Composable fun EroflixTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, content = content)
}

fun formatDuration(ms: Long?): String {
    if (ms == null || ms <= 0) return "Duration unknown"
    val minutes = ms / 60_000
    val hours = minutes / 60
    val remMinutes = minutes % 60
    return if (hours > 0) "${hours}h ${remMinutes}m" else "${minutes}m"
}

fun formatTime(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val seconds = totalSeconds % 60
    val minutes = (totalSeconds / 60) % 60
    val hours = totalSeconds / 3600
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}

@Composable fun PosterPlaceholder(title: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.background(
            Brush.verticalGradient(
                listOf(Color(0xFF261219), Color(0xFF0F1116), Color(0xFF07080A))
            )
        ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(8.dp)
        ) {
            Text(
                text = "▶",
                style = MaterialTheme.typography.headlineMedium,
                color = Color(0xFFE11D48).copy(alpha = 0.6f)
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFFA1A1AA),
                maxLines = 2,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable fun MessageState(title: String, message: String, modifier: Modifier = Modifier, retry: (() -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (retry != null) TextButton(onClick = retry) { Text("Retry") }
    }
}

