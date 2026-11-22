package ru.bondmobile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
    is ValuationIssue.AfterPayment -> "После последней котировки наступила дата выплаты по календарю · ${kind.label} ${dateLabel(date, true)}"
    is ValuationIssue.CouponUnverified -> "Исполнение купона от ${dateLabel(date, true)} не подтверждено · нет актуальной котировки"
    is ValuationIssue.CouponAccrualUnknown -> "Недостаточно данных для расчёта НКД после купона ${dateLabel(date, true)}"
    is ValuationIssue.MissingFields -> "В данных на дату котировки нет " + fields.joinToString(", ") {
        when (it) {
            QuoteField.PRICE -> "цены"
            QuoteField.NOMINAL -> "номинала"
            QuoteField.ACCRUED -> "НКД"
        }
    }
}

@Composable
internal fun ValuationDetailsDialog(holdings: List<Holding>?, day: LocalDate?, close: () -> Unit, openBond: (String) -> Unit) {
    val rows = holdings?.map { holding ->
        DetailRow(holding.bond, holding.quoteDate, buildList {
            holding.issue?.let { add(it.description()) }
            holding.estimatedAccrued?.let { add("Расчётный НКД · ${rubles(it)} / шт.") }
        })
    }
    BreakdownDialog("Подробности оценки", day, rows, "Данные для всех позиций уже получены", close, openBond)
}

@Composable
internal fun ResultDetailsDialog(exclusions: List<ResultExclusion>?, day: LocalDate, close: () -> Unit, openBond: (String) -> Unit) {
    val rows = exclusions?.map { exclusion ->
        DetailRow(exclusion.bond, exclusion.holding?.quoteDate, buildList {
            exclusion.holding?.issue?.let { add(it.description()) }
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
internal fun PremiumChangeDialog(change: PremiumChange, close: () -> Unit, openBond: (String) -> Unit) {
    AlertDialog(
        onDismissRequest = close,
        title = { Text("Изменение по выпускам") },
        text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                item {
                    Text("${dateLabel(change.previousDate.toString(), true)} → ${dateLabel(change.date.toString(), true)}",
                        color = Muted)
                }
                items(change.issues, key = { it.bond.isin }) { issue ->
                    IssuePriceChangeRow(issue) { openBond(issue.bond.isin) }
                    HorizontalDivider(color = Divider)
                }
            }
        },
        confirmButton = { TextButton(onClick = close) { Text("Закрыть") } }
    )
}

@Composable
private fun IssuePriceChangeRow(issue: IssuePriceChange, openBond: () -> Unit) {
    val percent = issue.percent?.setScale(4, RoundingMode.HALF_UP)
    val before = issue.previousPrice?.let { "${decimalLabel(it.toPlainString())} %" } ?: "—"
    val after = issue.price?.let { "${decimalLabel(it.toPlainString())} %" } ?: "—"
    Column(Modifier.fillMaxWidth().clickable(onClick = openBond).padding(vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(issue.bond.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Text(percent?.let { "${signedLabel(it) { value -> decimalLabel(value.toPlainString()) }} %" } ?: "—",
                color = percent?.let { changeColor(it) } ?: Muted, fontWeight = FontWeight.SemiBold)
            Glyph(Symbol.NEXT, color = Forest)
        }
        val prices = if (issue.percent == null && issue.previousPrice?.signum() == 1 && issue.price != null)
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
