package com.cereveil.guardian.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class GuardianUsageDatum(
  val packageName: String,
  val label: String,
  val millis: Long,
)

private val ChartColors =
  listOf(
    Color(0xFF3268C7),
    Color(0xFF55C1B3),
    Color(0xFF7C55C7),
    Color(0xFFFFB04A),
    Color(0xFF79BE7D),
  )

@Composable
fun GuardianAppIcon(
  packageName: String,
  label: String,
  modifier: Modifier = Modifier,
) {
  val packageManager = LocalContext.current.packageManager
  val bitmap by produceState<ImageBitmap?>(initialValue = null, packageName, packageManager) {
    value = withContext(Dispatchers.IO) {
      runCatching {
        packageManager.getApplicationIcon(packageName).toBitmap(width = 96, height = 96).asImageBitmap()
      }.getOrNull()
    }
  }
  val loadedBitmap = bitmap
  if (loadedBitmap != null) {
    Image(
      bitmap = loadedBitmap,
      contentDescription = "$label app icon",
      modifier = modifier.size(44.dp).clip(RoundedCornerShape(12.dp)),
    )
  } else {
    val tileColor = remember(packageName, label) { appTileColor(packageName, label) }
    Box(
      modifier =
        modifier.size(44.dp).clip(RoundedCornerShape(12.dp))
          .background(tileColor)
          .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(12.dp)),
      contentAlignment = Alignment.Center,
    ) {
      Text(
        text = label.trim().firstOrNull()?.uppercase() ?: "?",
        color = Color.White,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
      )
    }
  }
}

@Composable
fun GuardianDonutOverview(
  usage: List<GuardianUsageDatum>,
  modifier: Modifier = Modifier,
) {
  val top = usage.filter { it.millis > 0 }.sortedByDescending(GuardianUsageDatum::millis).take(3)
  val total = usage.sumOf(GuardianUsageDatum::millis)
  GuardianCard(modifier) {
    Row(
      Modifier.fillMaxWidth().padding(vertical = 4.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
      Box(Modifier.size(132.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
          val stroke = 21.dp.toPx()
          drawArc(
            color = Color.Black.copy(alpha = 0.08f),
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            style = Stroke(stroke + 3.dp.toPx()),
          )
          if (top.isEmpty() || total == 0L) {
            drawArc(
              color = Color.Gray.copy(alpha = 0.3f),
              startAngle = -90f,
              sweepAngle = 360f,
              useCenter = false,
              style = Stroke(stroke, cap = StrokeCap.Round),
            )
          } else {
            val topTotal = top.sumOf(GuardianUsageDatum::millis).toFloat()
            var start = -90f
            top.forEachIndexed { index, item ->
              val rawSweep = item.millis / topTotal * 360f
              drawArc(
                color = ChartColors[index],
                startAngle = start,
                sweepAngle = (rawSweep - 3f).coerceAtLeast(2f),
                useCenter = false,
                style = Stroke(stroke, cap = StrokeCap.Round),
              )
              start += rawSweep
            }
          }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
          Text(formatUsageDuration(total), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
          Text("TOTAL", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
      }
      Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (top.isEmpty()) {
          Text("No usage yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
          top.forEachIndexed { index, item ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
              Box(Modifier.size(9.dp).background(ChartColors[index], RoundedCornerShape(3.dp)))
              Spacer(Modifier.width(8.dp))
              Text(item.label, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
              Text(formatUsageDuration(item.millis), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
          }
        }
      }
    }
  }
}

@Composable
fun GuardianScreenTimeChart(
  usage: List<GuardianUsageDatum>,
  modifier: Modifier = Modifier,
) {
  val top = usage.filter { it.millis > 0 }.sortedByDescending(GuardianUsageDatum::millis).take(5)
  val max = top.maxOfOrNull(GuardianUsageDatum::millis)?.coerceAtLeast(1L) ?: 1L
  GuardianCard(modifier) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      Icon(Icons.AutoMirrored.Filled.ShowChart, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
      Spacer(Modifier.width(8.dp))
      Text("Screen Time Today", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
      Text(formatUsageDuration(usage.sumOf(GuardianUsageDatum::millis)), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    }
    if (top.isEmpty()) {
      Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
        Text("No app usage reported yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
    } else {
      Row(
        Modifier.fillMaxWidth().height(230.dp).padding(top = 12.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        top.forEach { item ->
          Column(
            Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Bottom,
          ) {
            Text(formatUsageDuration(item.millis), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            Box(
              Modifier.fillMaxWidth(0.68f)
                .height((170f * (item.millis.toFloat() / max)).coerceAtLeast(16f).dp)
                .clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp))
                .background(MaterialTheme.colorScheme.primary),
            )
            Spacer(Modifier.height(8.dp))
            Text(
              item.label,
              modifier = Modifier.fillMaxWidth(),
              style = MaterialTheme.typography.labelSmall,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
              textAlign = TextAlign.Center,
            )
          }
        }
      }
    }
  }
}

@Composable
fun GuardianUsageRow(
  usage: GuardianUsageDatum,
  rank: Int,
  modifier: Modifier = Modifier,
) {
  GuardianCard(modifier) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      Text("#$rank", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(34.dp))
      GuardianAppIcon(usage.packageName, usage.label)
      Spacer(Modifier.width(12.dp))
      Column(Modifier.weight(1f)) {
        Text(usage.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
      }
      Spacer(Modifier.width(12.dp))
      Text(formatUsageDuration(usage.millis), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
    }
  }
}

fun formatUsageDuration(ms: Long): String {
  val minutes = ms / 60_000
  return if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else "${minutes}m"
}

private fun appTileColor(packageName: String, label: String): Color {
  val identity = "$packageName $label".lowercase()
  return when {
    "youtube" in identity -> Color(0xFFFF0033)
    "reddit" in identity -> Color(0xFFFF5700)
    "discord" in identity -> Color(0xFF5865F2)
    "spotify" in identity -> Color(0xFF1DB954)
    "chrome" in identity -> Color(0xFF4285F4)
    "instagram" in identity -> Color(0xFFC13584)
    "whatsapp" in identity -> Color(0xFF25D366)
    else -> ChartColors[(identity.hashCode() and Int.MAX_VALUE) % ChartColors.size]
  }
}
