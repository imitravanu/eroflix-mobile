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

private val colors = darkColorScheme(primary = Color(0xFFD7B783), onPrimary = Color(0xFF241C0C),
    background = Color(0xFF0E1218), surface = Color(0xFF151C25), surfaceVariant = Color(0xFF253140),
    onBackground = Color(0xFFF1F1EF), onSurface = Color(0xFFF1F1EF), secondary = Color(0xFFB7C9D6))
@Composable fun EroflixTheme(content: @Composable () -> Unit) { MaterialTheme(colorScheme = colors, content = content) }
@Composable fun PosterPlaceholder(title: String, modifier: Modifier = Modifier) {
    Box(modifier.background(Brush.verticalGradient(listOf(Color(0xFF304356), Color(0xFF17212D)))), contentAlignment = Alignment.Center) {
        Text(title.take(1), style = MaterialTheme.typography.displayLarge, fontWeight = FontWeight.Light, color = Color(0xFF9CAFBF))
    }
}
@Composable fun MessageState(title: String, message: String, modifier: Modifier = Modifier, retry: (() -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (retry != null) TextButton(onClick = retry) { Text("Retry") }
    }
}
