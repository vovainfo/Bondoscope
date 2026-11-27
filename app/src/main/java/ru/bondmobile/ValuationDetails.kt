package ru.bondmobile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.math.RoundingMode
import java.time.LocalDate

internal fun ValuationIssue.description(): String = when (this) {
    ValuationIssue.NoQuote -> "Котировка по выпуску отсутствует"
    ValuationIssue.OldQuote -> "Последняя котировка старше $MAX_QUOTE_AGE_DAYS календарных дней"
    ValuationIssue.UnsupportedCurrency -> "Оценка в валюте этого выпуска недоступна"
    ValuationIssue.Maturity -> "Наступила дата погашения · исполнение не подтверждено"
    ValuationIssue.NominalChanged -> "Номинал изменился после последней котировки"
    is ValuationIssue.PrincipalUnverified -> "Исполнение выплаты от ${dateLabel(date, true)} не подтверждено · ${kind.label}"
    is ValuationIssue.AccruedAfterPrincipal -> "НКД после выплаты номинала от ${dateLabel(date, true)} неизвестен"
    is ValuationIssue.CouponUnverified -> "Исполнение купона от ${dateLabel(date, true)} не подтверждено · нет актуальной котировки"
    is ValuationIssue.CouponAccrualUnknown -> "Недостаточно данных для расчёта НКД после купона ${dateLabel(date, true)}"
    is ValuationIssue.MissingFields -> fields.joinToString(" · ") {
        when (it) {
            QuoteField.PRICE -> "В данных на дату котировки нет цены"
            QuoteField.NOMINAL -> "Остаток номинала неизвестен"
            QuoteField.ACCRUED -> "В данных на дату котировки нет НКД"
        }
    }
}

@Composable
internal fun ValuationDetailsDialog(
    holdings: List<Holding>?, day: LocalDate?, close: () -> Unit, openBond: (String) -> Unit, withoutAccrued: Boolean
) {
    val rows = holdings?.mapNotNull { holding ->
        val messages = buildList {
            holding.cleanIssue?.let { add(it.description()) }
            if (holding.priceIsEstimated) add("Расчётная цена · к остатку номинала")
            if (!withoutAccrued) {
                holding.accruedIssue?.let { add(it.description()) }
                holding.estimatedAccrued?.let { add("Расчётный НКД · ${rubles(it)} / шт.") }
                if (holding.accruedIssue != null) {
                    holding.cleanValue?.let { add("Учтена чистая стоимость · ${rubles(it)}") }
                }
                if (holding.cleanIssue != null) {
                    holding.accruedValue?.let { add("Учтён НКД · ${rubles(it)}") }
                }
            }
        }
        if (messages.isEmpty()) null else DetailRow(holding.bond, holding.quoteDate, messages)
    }
    val title = if (withoutAccrued) "Оценка без НКД" else "Подробности оценки"
    BreakdownDialog(title, day, rows, "Данные для всех позиций уже получены", close, openBond)
}

@Composable
internal fun ResultDetailsDialog(exclusions: List<ResultExclusion>?, day: LocalDate, close: () -> Unit, openBond: (String) -> Unit) {
    val rows = exclusions?.map { exclusion ->
        DetailRow(exclusion.bond, exclusion.holding?.quoteDate, buildList {
            exclusion.holding?.cleanIssue?.let { add(it.description()) }
            exclusion.holding?.accruedIssue?.let { add(it.description()) }
            exclusion.incompleteTrades.sortedByDescending { it.date }.forEach { trade ->
                val side = if (trade.side == TradeSide.BUY) "Покупка" else "Продажа"
                val missing = if (trade.unit == PriceUnit.PERCENT) "НКД или номинал" else "НКД"
                add("$side от ${dateLabel(trade.date, true)} · для суммы сделки нужны $missing")
            }
            exclusion.incompletePayments.sortedByDescending { it.date }.forEach { payment ->
                val reason = when {
                    exclusion.bond.requiresPaymentVerification(payment.date) -> "исполнение не подтверждено"
                    payment.amountPerBond == null -> "сумма на бумагу неизвестна"
                    else -> "остаток бумаг неизвестен"
                }
                add("${payment.kind.label} от ${dateLabel(payment.date, true)} · $reason")
            }
        })
    }
    BreakdownDialog("Подробности финансового результата", day, rows, "Все выпуски вошли в расчёт", close, openBond)
}

@Composable
internal fun PriceChangeDialog(
    change: PortfolioPriceChange?, forPeriod: Boolean, close: () -> Unit, openBond: (String) -> Unit
) {
    var sort by rememberSaveable { mutableStateOf(PeriodChangeSort.PERCENTAGE_POINTS) }
    val issues = remember(change, forPeriod, sort) {
        if (forPeriod) change?.sortedIssues(sort).orEmpty() else change?.issues.orEmpty()
    }
    AlertDialog(
        onDismissRequest = close,
        title = { Text(if (forPeriod) "Изменение за период" else "Изменение по выпускам") },
        text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                if (change == null) item { Text("Загружаем подробности…", color = Muted) }
                else {
                    item {
                        Text("${dateLabel(change.previousDate.toString(), true)} → ${dateLabel(change.date.toString(), true)}",
                            color = Muted)
                    }
                    if (forPeriod) item {
                        val amount = change.amount?.setScale(2, RoundingMode.HALF_UP)
                        val label = amount?.let { signedLabel(it, ::rubles) } ?: "—"
                        Text("Изменение стоимости без НКД · $label",
                            color = amount?.let { changeColor(it) } ?: Muted, fontWeight = FontWeight.SemiBold)
                        if (change.comparablePositions < change.issues.size) {
                            Text("Сравнение по ${change.comparablePositions} из ${change.issues.size} позиций", color = Muted)
                        }
                    }
                    if (forPeriod && issues.isNotEmpty()) item {
                        Text("Сортировка по модулю", color = Muted, style = MaterialTheme.typography.bodySmall)
                        Row(Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            PeriodChangeSort.entries.forEach { option ->
                                FilterChip(sort == option, { sort = option }, { Text(option.label, maxLines = 1) })
                            }
                        }
                    }
                    if (issues.isEmpty()) item { Text("На эти даты нет открытых позиций", color = Muted) }
                    items(issues, key = { it.bond.isin }) { issue ->
                        IssuePriceChangeRow(issue, forPeriod) { openBond(issue.bond.isin) }
                        HorizontalDivider(color = Divider)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = close) { Text("Закрыть") } }
    )
}

@Composable
private fun IssuePriceChangeRow(issue: IssuePriceChange, forPeriod: Boolean, openBond: () -> Unit) {
    val change = if (forPeriod) issue.percentagePoints else issue.percent
    val displayedChange = change?.setScale(4, RoundingMode.HALF_UP)
    val unit = if (forPeriod) "п.п." else "%"
    val before = issue.previousPrice?.let { "${decimalLabel(it.toPlainString())} %" } ?: "—"
    val after = issue.price?.let { "${decimalLabel(it.toPlainString())} %" } ?: "—"
    Column(Modifier.fillMaxWidth().clickable(onClick = openBond).padding(vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(issue.bond.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Text(displayedChange?.let { "${signedLabel(it) { value -> decimalLabel(value.toPlainString()) }} $unit" } ?: "—",
                color = displayedChange?.let { changeColor(it) } ?: Muted, fontWeight = FontWeight.SemiBold)
            Glyph(Symbol.NEXT, color = Forest)
        }
        val prices = if (change == null && issue.previousPrice != null && issue.price != null &&
            (forPeriod || issue.previousPrice.signum() == 1))
            "Нет новой котировки для расчёта изменения цены" else "Чистая цена · $before → $after"
        Text(prices, color = Muted, style = MaterialTheme.typography.bodySmall)
        val amount = issue.amount?.setScale(2, RoundingMode.HALF_UP)
        Text(amount?.let { "Изменение суммы · ${signedLabel(it, ::rubles)}" } ?: "Недостаточно данных для суммы",
            color = Muted, style = MaterialTheme.typography.bodySmall)
        if (issue.previousQuantity != issue.quantity) {
            Text("Количество · ${issue.previousQuantity} → ${issue.quantity} шт.",
                color = Muted, style = MaterialTheme.typography.bodySmall)
        }
        if (issue.previousNominal != null && issue.nominal != null && issue.previousNominal.compareTo(issue.nominal) != 0) {
            Text("Номинал · ${rubles(issue.previousNominal)} → ${rubles(issue.nominal)} за бумагу", color = Amber)
        }
        issue.principalEvents.forEach { payment ->
            val status = if (issue.bond.requiresPaymentVerification(payment.date)) " · исполнение не подтверждено" else ""
            Text("${payment.kind.label} по календарю · ${dateLabel(payment.date, true)}$status", color = Amber)
        }
    }
}

private data class DetailRow(val bond: Bond, val quoteDate: String?, val messages: List<String>)

@Composable
private fun BreakdownDialog(
    title: String, day: LocalDate?, rows: List<DetailRow>?, emptyMessage: String, close: () -> Unit, openBond: (String) -> Unit
) {
    AlertDialog(
        onDismissRequest = close,
        title = { Text(title) },
        text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                if (day != null) item { Text(dateLabel(day.toString()), color = Muted) }
                if (rows == null) item { Text("Загружаем подробности…", color = Muted) }
                else if (rows.isEmpty()) item { Text(emptyMessage) }
                else items(rows, key = { it.bond.isin }) { row ->
                    Row(Modifier.fillMaxWidth().clickable { openBond(row.bond.isin) }.padding(vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(row.bond.name, style = MaterialTheme.typography.titleMedium)
                            Text(row.bond.isin, color = Muted, style = MaterialTheme.typography.bodySmall)
                            row.quoteDate?.let { Text("Последняя котировка ${dateLabel(it, true)}", color = Muted) }
                            row.messages.forEach { Text(it, color = Amber) }
                        }
                        Glyph(Symbol.NEXT, color = Forest)
                    }
                    HorizontalDivider(color = Divider)
                }
            }
        },
        confirmButton = { TextButton(onClick = close) { Text("Закрыть") } }
    )
}
