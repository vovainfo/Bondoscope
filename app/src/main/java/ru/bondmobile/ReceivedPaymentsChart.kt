package ru.bondmobile

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

private enum class ReceiptPeriod(val months: Long?, val label: String) {
    SIX(6, "6 мес"), TWELVE(12, "12 мес"), ALL(null, "Весь период")
}

private val receiptLocale = Locale.forLanguageTag("ru-RU")
private val monthTitle = DateTimeFormatter.ofPattern("LLLL yyyy", receiptLocale)
private val monthTick = DateTimeFormatter.ofPattern("LLL", receiptLocale)
private val principalColor = Color(0xFF527C98)
private val plotHeight = 128.dp
private val chartHeight = plotHeight + chartAxisInset * 2

@Composable
fun ReceivedPaymentsChart(receipts: Map<YearMonth, MonthlyReceipt>, today: LocalDate) {
    if (receipts.isEmpty()) return
    val latest = YearMonth.from(today)
    var period by rememberSaveable {
        val lastReceipt = receipts.keys.max()
        mutableStateOf(when {
            lastReceipt >= latest.minusMonths(5) -> ReceiptPeriod.SIX
            lastReceipt >= latest.minusMonths(11) -> ReceiptPeriod.TWELVE
            else -> ReceiptPeriod.ALL
        })
    }
    var selectedKey by rememberSaveable { mutableStateOf<String?>(null) }
    val first = period.months?.let { latest.minusMonths(it - 1) } ?: receipts.keys.min()
    val months = remember(first, latest) {
        generateSequence(first) { it.plusMonths(1) }.takeWhile { it <= latest }.toList()
    }
    val selected = months.find { it.toString() == selectedKey }
        ?: months.findLast { it in receipts } ?: latest
    val chosen = receipts[selected] ?: MonthlyReceipt()
    val upper = months.maxOf { receipts[it]?.total ?: BigDecimal.ZERO }
    val scale = remember(upper) { MoneyScale(BigDecimal.ZERO, upper) }

    Sheet {
        Text("История выплат", style = MaterialTheme.typography.titleLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ReceiptPeriod.entries.forEach { range ->
                FilterChip(range == period, { period = range }, { Text(range.label, maxLines = 1) })
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Text(selected.displayMonth(), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Text(rubles(chosen.total), color = Forest, fontWeight = FontWeight.SemiBold)
        }
        MoneyChart(scale, chartHeight) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val columnWidth = (maxWidth / 6).coerceAtLeast(40.dp)
                Canvas(Modifier.fillMaxWidth().height(chartHeight)) {
                    drawMoneyGrid(scale)
                }
                key(period, first, latest) {
                    val visibleColumns = (maxWidth / columnWidth).toInt().coerceAtLeast(1)
                    val firstIndex = months.indexOf(selected).coerceAtLeast(0)
                        .coerceAtMost((months.size - visibleColumns).coerceAtLeast(0))
                    val scroll = rememberLazyListState(initialFirstVisibleItemIndex = firstIndex)
                    LazyRow(state = scroll) {
                        items(months, key = YearMonth::toString) { month ->
                            val amounts = receipts[month] ?: MonthlyReceipt()
                            ReceiptColumn(month, amounts, scale.maximumTick, columnWidth, month == selected) {
                                selectedKey = month.toString()
                            }
                        }
                    }
                }
            }
        }
        HorizontalDivider(color = Divider)
        ReceiptLegend("Купоны", chosen.coupons, Forest)
        ReceiptLegend("Амортизация и погашения", chosen.principal, principalColor)
        if (chosen.incomplete) Text("По части выплат за месяц сумма недоступна", color = Amber)
    }
}

@Composable
private fun ReceiptColumn(month: YearMonth, amounts: MonthlyReceipt, scale: BigDecimal, width: Dp,
                          selected: Boolean, select: () -> Unit) {
    val couponHeight = plotHeight * ratio(amounts.coupons, scale)
    val principalHeight = plotHeight * ratio(amounts.principal, scale)
    val description = "${month.displayMonth()}. Купоны ${rubles(amounts.coupons)}. " +
        "Амортизация и погашения ${rubles(amounts.principal)}." +
        if (amounts.incomplete) " Суммы неполные." else ""
    Column(
        Modifier.width(width)
            .background(if (selected) Mint.copy(alpha = 0.5f) else Color.Transparent, RoundedCornerShape(8.dp))
            .selectable(selected, role = Role.Button, onClick = select)
            .semantics { contentDescription = description },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(Modifier.fillMaxWidth().height(chartHeight), contentAlignment = Alignment.BottomCenter) {
            Column(Modifier.padding(bottom = chartAxisInset).width(19.dp)) {
                if (principalHeight > 0.dp) Box(Modifier.fillMaxWidth().height(principalHeight).background(principalColor))
                if (couponHeight > 0.dp) Box(Modifier.fillMaxWidth().height(couponHeight).background(Forest))
                if (couponHeight == 0.dp && principalHeight == 0.dp) {
                    Box(Modifier.fillMaxWidth().height(2.dp).background(Divider))
                }
            }
        }
        Box(Modifier.height(29.dp), contentAlignment = Alignment.Center) {
            Text(month.format(monthTick), color = if (selected) Forest else Muted,
                style = MaterialTheme.typography.bodySmall, maxLines = 1)
        }
    }
}

@Composable
private fun ReceiptLegend(label: String, value: BigDecimal, color: Color) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(9.dp).background(color, CircleShape))
        Text(label, Modifier.weight(1f), color = Muted)
        Text(rubles(value), fontWeight = FontWeight.SemiBold)
    }
}

private fun ratio(value: BigDecimal, scale: BigDecimal): Float = if (scale.signum() == 0) 0f
else (value.toDouble() / scale.toDouble()).toFloat()

private fun YearMonth.displayMonth() = format(monthTitle).replaceFirstChar { it.titlecase(receiptLocale) }
