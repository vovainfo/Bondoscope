package ru.bondmobile

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.math.BigDecimal
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

val Forest = Color(0xFF17463C)
val Paper = Color(0xFFF5F7F4)
val CardWhite = Color(0xFFFFFFFF)
val Ink = Color(0xFF182923)
val Muted = Color(0xFF506760)
val Mint = Color(0xFFDDEFE2)
val Divider = Color(0xFFDDE5DF)
val Rust = Color(0xFFA23630)
val Amber = Color(0xFF795511)

@Composable
fun BondTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Forest, onPrimary = CardWhite, primaryContainer = Mint, onPrimaryContainer = Forest,
            background = Paper, onBackground = Ink, surface = CardWhite, onSurface = Ink,
            surfaceVariant = Paper, onSurfaceVariant = Muted, outline = Color(0xFF778A80),
            outlineVariant = Divider, error = Rust
        ),
        typography = Typography(
            headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 34.sp,
                lineHeight = 40.sp, letterSpacing = (-0.8).sp, fontWeight = FontWeight.Bold),
            headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 26.sp,
                lineHeight = 32.sp, fontWeight = FontWeight.SemiBold),
            titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 20.sp,
                lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
            titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 16.sp,
                lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
            bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 20.sp),
            bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 12.sp, lineHeight = 17.sp)
        ), content = content
    )
}

enum class Symbol { CHART, LIST, CALENDAR, FOLDER, REFRESH, PLUS, BACK, NEXT, CHECK }

@Composable
fun Glyph(symbol: Symbol, modifier: Modifier = Modifier, color: Color = androidx.compose.material3.LocalContentColor.current) {
    Canvas(modifier.size(22.dp)) {
        val scale = size.width / 24f
        fun point(x: Float, y: Float) = Offset(x * scale, y * scale)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
            drawLine(color, point(x1, y1), point(x2, y2), 1.8f * scale, StrokeCap.Round)
        fun shape(vararg vertices: Pair<Float, Float>) {
            val path = Path()
            vertices.forEachIndexed { i, (x, y) ->
                if (i == 0) path.moveTo(x * scale, y * scale) else path.lineTo(x * scale, y * scale)
            }
            drawPath(path, color, style = Stroke(1.8f * scale, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        fun calendarBox() = drawRoundRect(color, point(4f, 5f), Size(16f * scale, 15f * scale),
            CornerRadius(2f * scale), style = Stroke(1.8f * scale))
        when (symbol) {
            Symbol.CHART -> {
                shape(4f to 4f, 4f to 19f, 21f to 19f)
                shape(7f to 15f, 11f to 11f, 14f to 13f, 20f to 7f)
            }
            Symbol.LIST -> {
                listOf(6f, 12f, 18f).forEach { y ->
                    drawCircle(color, scale, point(5f, y)); line(9f, y, 20f, y)
                }
            }
            Symbol.CALENDAR -> {
                calendarBox(); line(4f, 10f, 20f, 10f); line(8f, 3f, 8f, 7f); line(16f, 3f, 16f, 7f)
                line(9f, 15f, 15f, 15f)
            }
            Symbol.FOLDER -> shape(3f to 19f, 3f to 6f, 10f to 6f, 12f to 9f, 21f to 9f, 21f to 19f, 3f to 19f)
            Symbol.REFRESH -> {
                drawArc(color, 38f, 295f, false, point(5f, 5f), Size(14f * scale, 14f * scale),
                    style = Stroke(1.8f * scale, cap = StrokeCap.Round))
                shape(19f to 5f, 19f to 10f, 14.5f to 10f)
            }
            Symbol.PLUS -> { line(5f, 12f, 19f, 12f); line(12f, 5f, 12f, 19f) }
            Symbol.BACK -> { shape(11f to 5f, 4f to 12f, 11f to 19f); line(4f, 12f, 20f, 12f) }
            Symbol.NEXT -> shape(9f to 6f, 15f to 12f, 9f to 18f)
            Symbol.CHECK -> shape(5f to 12f, 10f to 17f, 19f to 7f)
        }
    }
}

@Composable
fun PageHeader(title: String, subtitle: String, busy: Boolean = false, refresh: (() -> Unit)? = null,
               back: (() -> Unit)? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (back != null) {
            IconButton(onClick = back, modifier = Modifier.semantics { contentDescription = "Назад" }) {
                Glyph(Symbol.BACK, color = Forest)
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.headlineLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(subtitle, color = Muted, style = MaterialTheme.typography.bodyMedium)
            }
            if (refresh != null) {
                IconButton(onClick = refresh, enabled = !busy, modifier = Modifier.background(Mint, CircleShape)
                    .semantics { contentDescription = "Обновить данные" }) { Glyph(Symbol.REFRESH, color = Forest) }
            }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 5.dp))
    }
}

@Composable
fun Sheet(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = CardWhite)) {
        Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

@Composable
fun EmptyCard(symbol: Symbol, title: String, note: String, action: String? = null, perform: (() -> Unit)? = null) {
    Sheet {
        Box(Modifier.size(40.dp).background(Mint, RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) {
            Glyph(symbol, color = Forest)
        }
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(note, color = Muted)
        if (action != null && perform != null) Button(onClick = perform) { Text(action) }
    }
}

@Composable
fun SectionTitle(title: String) {
    Spacer(Modifier.height(2.dp))
    Text(title, style = MaterialTheme.typography.titleLarge)
}

private val russian = Locale.forLanguageTag("ru-RU")
private val shortDate = DateTimeFormatter.ofPattern("dd.MM.yy", russian)
private val longDate = DateTimeFormatter.ofPattern("dd MMM yyyy", russian)

fun rubles(number: BigDecimal?): String = number?.let {
    NumberFormat.getCurrencyInstance(russian).format(it)
} ?: "—"

fun decimalLabel(number: String?): String = number?.toBigDecimalOrNull()?.stripTrailingZeros()?.toPlainString()
    ?.replace('.', ',') ?: "—"

fun dateLabel(date: String?, short: Boolean = false): String = date?.let {
    LocalDate.parse(it).format(if (short) shortDate else longDate)
} ?: "—"

fun instantLabel(epoch: Long): String = Instant.ofEpochMilli(epoch).atZone(ZoneId.of("Europe/Moscow"))
    .format(DateTimeFormatter.ofPattern("dd.MM.yy HH:mm", russian)) + " МСК"
