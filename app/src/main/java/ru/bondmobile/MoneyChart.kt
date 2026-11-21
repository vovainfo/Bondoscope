package ru.bondmobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.roundToInt

internal val chartAxisInset = 10.dp
private val cent = BigDecimal("0.01")
private val tickMultipliers = listOf("1", "2", "2.5", "5", "10").map(::BigDecimal)
private val axisUnits = listOf(12 to "трлн ₽", 9 to "млрд ₽", 6 to "млн ₽", 3 to "тыс. ₽")

internal class MoneyScale(minimum: BigDecimal, maximum: BigDecimal) {
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
        val (exponent, suffix) = axisUnits.firstOrNull { (power, _) ->
            largest >= BigDecimal.ONE.scaleByPowerOfTen(power) && step >= BigDecimal.ONE.scaleByPowerOfTen(power - 2)
        } ?: (0 to "₽")
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
internal fun MoneyChart(scale: MoneyScale, height: Dp, plot: @Composable () -> Unit) {
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

internal fun DrawScope.drawMoneyGrid(scale: MoneyScale) {
    val inset = chartAxisInset.toPx()
    scale.ticks.forEach { tick ->
        val y = scale.y(tick, size.height, inset)
        drawLine(Divider, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
    }
}
