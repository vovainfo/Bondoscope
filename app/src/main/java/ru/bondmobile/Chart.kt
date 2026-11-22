package ru.bondmobile

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.time.LocalDate
import java.util.Locale
import kotlin.math.roundToInt

internal val chartAxisInset = 10.dp
private val cent = BigDecimal("0.01")
private val tickMultipliers = listOf("1", "2", "2.5", "5", "10").map(::BigDecimal)
private val axisUnits = listOf(12 to "трлн ₽", 9 to "млрд ₽", 6 to "млн ₽", 3 to "тыс. ₽")

internal enum class ChartUnit { RUBLES, PERCENT }

internal class ChartScale(minimum: BigDecimal, maximum: BigDecimal, unit: ChartUnit = ChartUnit.RUBLES) {
    val ticks: List<BigDecimal>
    val labels: List<String>
    val maximumTick: BigDecimal get() = ticks.first()
    private val range: Double

    init {
        val margin = if (minimum.compareTo(maximum) == 0) maximum.abs().movePointLeft(2).max(cent)
            else BigDecimal.ZERO
        val low = (minimum - margin).let { if (minimum.signum() >= 0) it.max(BigDecimal.ZERO) else it }
        val high = maximum + margin
        val roughStep = (high - low).divide(BigDecimal(4)).max(cent)
        val magnitude = roughStep.precision() - roughStep.scale() - 1
        val step = tickMultipliers.first { it.scaleByPowerOfTen(magnitude) >= roughStep }.scaleByPowerOfTen(magnitude)
        val bottom = low.divide(step, 0, RoundingMode.FLOOR) * step
        val top = high.divide(step, 0, RoundingMode.CEILING) * step
        range = (top - bottom).toDouble()
        ticks = generateSequence(top) { it - step }.takeWhile { it >= bottom }.toList()

        val largest = top.abs().max(bottom.abs())
        val (exponent, suffix) = when (unit) {
            ChartUnit.PERCENT -> 0 to "%"
            ChartUnit.RUBLES -> axisUnits.firstOrNull { (power, _) ->
                largest >= BigDecimal.ONE.scaleByPowerOfTen(power) && step >= BigDecimal.ONE.scaleByPowerOfTen(power - 2)
            } ?: (0 to "₽")
        }
        val formatter = NumberFormat.getNumberInstance(Locale.forLanguageTag("ru-RU"))
        labels = ticks.map { tick ->
            val scaled = tick.movePointLeft(exponent).stripTrailingZeros()
            formatter.maximumFractionDigits = scaled.scale().coerceAtLeast(0)
            formatter.format(scaled).replace('-', '−') + "\u00a0" + suffix
        }
    }

    fun y(value: BigDecimal, height: Float, inset: Float): Float =
        inset + ((maximumTick - value).toDouble() / range * (height - 2f * inset)).toFloat()
}

@Composable
internal fun AxisChart(scale: ChartScale, height: Dp, plot: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val axisWidth = maxWidth * 0.44f
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Layout(content = {
                scale.labels.forEach { label ->
                    Text(label, color = Muted, style = MaterialTheme.typography.bodySmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }, modifier = Modifier.widthIn(max = axisWidth).height(height)) { measurables, constraints ->
                val labels = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
                val width = labels.maxOf { it.width }
                val chartHeight = constraints.maxHeight
                val inset = chartAxisInset.toPx()
                layout(width, chartHeight) {
                    labels.forEachIndexed { index, label ->
                        val center = scale.y(scale.ticks[index], chartHeight.toFloat(), inset).roundToInt()
                        label.placeRelative(width - label.width, center - label.height / 2)
                    }
                }
            }
            Column(Modifier.weight(1f)) { plot() }
        }
    }
}

internal fun DrawScope.drawChartGrid(scale: ChartScale) {
    val inset = chartAxisInset.toPx()
    scale.ticks.forEach { tick ->
        val y = scale.y(tick, size.height, inset)
        drawLine(Divider, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
    }
}

@Composable
internal fun ChartPeriodPicker(days: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(7 to "Неделя", 30 to "1 мес", 90 to "3 мес", 365 to "1 год", 0 to "Всё").forEach { (range, label) ->
            FilterChip(days == range, { onSelect(range) }, { Text(label, maxLines = 1) })
        }
    }
}

internal data class LinePoint(
    val date: LocalDate,
    val value: BigDecimal?,
    val approximate: Boolean = false,
    val partial: Boolean = false
)

@Composable
internal fun LineChart(points: List<LinePoint>, activeIndex: Int, unit: ChartUnit, description: String, select: (Int) -> Unit) {
    val scale = remember(points, unit) {
        val known = points.mapNotNull { it.value }
        ChartScale(known.min(), known.max(), unit)
    }
    val start = points.first().date.toEpochDay()
    val offsets = remember(points, start) { points.map { (it.date.toEpochDay() - start).toFloat() } }
    val span = offsets.last()
    val plotHeight = 150.dp
    val horizontalInset = 6.dp
    AxisChart(scale, plotHeight) {
        Canvas(Modifier.fillMaxWidth().height(plotHeight).semantics { contentDescription = description }
            .pointerInput(offsets, select) {
                detectTapGestures { tap ->
                    val inset = horizontalInset.toPx()
                    val width = (size.width - 2 * inset).coerceAtLeast(1f)
                    val target = ((tap.x - inset) / width).coerceIn(0f, 1f) * span
                    select(offsets.indices.minBy { kotlin.math.abs(offsets[it] - target) })
                }
            }) {
            val pad = horizontalInset.toPx()
            val inset = chartAxisInset.toPx()
            fun xy(index: Int, number: BigDecimal): Offset {
                val x = if (span == 0f) size.width / 2
                    else pad + offsets[index] / span * (size.width - 2 * pad)
                return Offset(x, scale.y(number, size.height, inset))
            }
            drawChartGrid(scale)
            var previous: Offset? = null
            points.forEachIndexed { index, point ->
                val here = point.value?.let { xy(index, it) }
                if (here != null) {
                    previous?.let {
                        val partial = point.partial || points[index - 1].partial
                        drawLine(if (partial) Amber else Forest, it, here, 2.dp.toPx(), StrokeCap.Round)
                    } ?: drawCircle(Forest, 2.dp.toPx(), here)
                    if (point.approximate) drawCircle(Amber, 2.dp.toPx(), here)
                }
                previous = here
            }
            points.getOrNull(activeIndex)?.let { point ->
                point.value?.let { value ->
                    val position = xy(activeIndex, value)
                    drawLine(Divider, Offset(position.x, 0f), Offset(position.x, size.height), 1.dp.toPx())
                    drawCircle(if (point.approximate) Amber else Forest, 4.dp.toPx(), position)
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(dateLabel(points.first().date.toString(), short = true), color = Muted)
            if (span != 0f) Text(dateLabel(points.last().date.toString(), short = true), color = Muted)
        }
    }
}
