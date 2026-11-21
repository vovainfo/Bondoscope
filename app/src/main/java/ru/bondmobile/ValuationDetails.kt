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
import androidx.compose.ui.unit.dp

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
internal fun ValuationDetailsDialog(holdings: List<Holding>, close: () -> Unit, openBond: (String) -> Unit) {
    AlertDialog(
        onDismissRequest = close,
        title = { Text("Подробности оценки") },
        text = {
            if (holdings.isEmpty()) Text("Данные для всех позиций уже получены")
            else LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                items(holdings, key = { it.bond.isin }) { holding ->
                    Row(Modifier.fillMaxWidth().clickable { openBond(holding.bond.isin) }.padding(vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(holding.bond.name, style = MaterialTheme.typography.titleMedium)
                            Text(holding.bond.isin, color = Muted, style = MaterialTheme.typography.bodySmall)
                            holding.quoteDate?.let { Text("Последняя котировка ${dateLabel(it, true)}", color = Muted) }
                            holding.issue?.let { Text(it.description(), color = Amber) }
                            holding.estimatedAccrued?.let { Text("Расчётный НКД · ${rubles(it)} / шт.", color = Amber) }
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
