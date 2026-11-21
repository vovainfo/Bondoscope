package ru.bondmobile

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import java.math.BigDecimal
import kotlin.math.roundToInt

@Composable
internal fun PageList(content: LazyListScope.() -> Unit) {
    LazyColumn(
        Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 18.dp, top = 18.dp, end = 18.dp, bottom = 22.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp), content = content
    )
}

@Composable
fun PortfolioPage(data: Dashboard, busy: Boolean, refresh: () -> Unit, add: () -> Unit,
                  openBond: (String) -> Unit, openPayments: () -> Unit, scopePicker: @Composable () -> Unit) {
    val total = data.overview
    val now = total.points.lastOrNull()
    val holdings = total.holdings
    val unavailable = holdings.filter { it.issue != null }
    val estimatedCount = holdings.count { it.estimatedAccrued != null }
    var showValuationDetails by rememberSaveable(data.selectedAccount?.id) { mutableStateOf(false) }
    val trackedBonds = data.portfolio.trades.map { it.isin }.toSet()
    val closedBonds = data.portfolio.bonds.filter { it.isin in trackedBonds && holdings.none { h -> h.bond.isin == it.isin } }
    val calendarDate = data.portfolio.bonds.filter { it.isin in trackedBonds }.minOfOrNull { it.calendarUpdatedAt } ?: 0
    PageList {
        item {
            PageHeader("Портфель", "Облигации Московской биржи", busy,
                if (data.portfolio.trades.isNotEmpty()) refresh else null)
        }
        item { scopePicker() }
        item {
            Column(Modifier.fillMaxWidth().background(Forest, RoundedCornerShape(25.dp)).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Рыночная стоимость", color = Mint, style = MaterialTheme.typography.titleMedium)
                val amount = if (total.valueIsPartial) total.partialValue.takeIf { holdings.any { h -> h.value != null } }
                else now?.value ?: BigDecimal.ZERO
                Text(rubles(amount), color = Color.White, fontSize = 35.sp, lineHeight = 42.sp, fontWeight = FontWeight.Bold)
                if (total.valueIsPartial) {
                    Text("Оценка неполная · ${unavailable.size} из ${holdings.size} позиций без оценки", color = Mint)
                }
                if (estimatedCount > 0) {
                    Text("Позиции с расчётным НКД · $estimatedCount", color = Mint)
                }
                if (total.valueIsPartial || estimatedCount > 0) {
                    TextButton(onClick = { showValuationDetails = true }) {
                        Text("Подробности оценки", color = Color.White)
                        Glyph(Symbol.NEXT, color = Color.White)
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("Финансовый результат", color = Mint, style = MaterialTheme.typography.bodySmall)
                        Text(rubles(now?.result), color = Color.White, style = MaterialTheme.typography.titleMedium)
                        if (now?.resultIsPartial == true) {
                            Text("Частичный · ${now.issuesWithResult} из ${now.issuesTracked} выпусков",
                                color = Mint, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("Купоны по календарю", color = Mint, style = MaterialTheme.typography.bodySmall)
                        Text(rubles(total.receivedCoupons), color = Color.White, style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
        if (data.portfolio.trades.isEmpty()) item {
            EmptyCard(Symbol.PLUS, "Добавьте первую покупку", "Найдите облигацию по тикеру или ISIN и внесите данные сделки.",
                "Добавить покупку", add)
        } else {
            item { HistoryChart(total.points) }
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { SectionTitle("Позиции") }
                    TextButton(onClick = add) { Glyph(Symbol.PLUS); Text("Добавить") }
                }
            }
            if (holdings.isEmpty()) item { Sheet { Text("Открытых позиций нет", color = Muted) } }
            items(holdings, key = { it.bond.isin }) { h ->
                Sheet(Modifier.clickable { openBond(h.bond.isin) }) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(h.bond.name, style = MaterialTheme.typography.titleMedium)
                            Text("${h.bond.secid} · ${h.quantity} шт.", color = Muted, style = MaterialTheme.typography.bodySmall)
                        }
                        Text(rubles(h.value), fontWeight = FontWeight.SemiBold)
                        Glyph(Symbol.NEXT, color = Forest)
                    }
                    h.quoteDate?.let {
                        val label = if (h.estimatedAccrued != null) "Расчётная оценка по котировке от" else "Котировка от"
                        Text("$label ${dateLabel(it, short = true)}",
                            color = if (h.estimatedAccrued != null) Amber else Muted, style = MaterialTheme.typography.bodySmall)
                    }
                    h.issue?.let { Text(it.description(), color = Amber, style = MaterialTheme.typography.bodySmall) }
                }
            }
            if (closedBonds.isNotEmpty()) {
                item { SectionTitle("Закрытые выпуски") }
                items(closedBonds, key = { it.isin }) { b ->
                    Sheet(Modifier.clickable { openBond(b.isin) }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(b.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                            Glyph(Symbol.NEXT, color = Forest)
                        }
                    }
                }
            }
            item {
                val next = data.payments.filter { it.date > data.today.toString() }.minByOrNull { it.date }
                Sheet {
                    Text("Ближайшая выплата", style = MaterialTheme.typography.titleMedium)
                    Text(next?.let {
                        "${dateLabel(it.date)} · ${data.bondsByIsin[it.isin]?.name ?: it.isin} · ${it.kind.label.lowercase()}"
                    } ?: if (calendarDate == 0L) "Календарь ещё не загружен" else "В загруженном календаре ближайших выплат нет", color = Muted)
                    TextButton(onClick = openPayments) { Text("Открыть выплаты") }
                }
            }
        }
    }
    if (showValuationDetails) {
        ValuationDetailsDialog(holdings.filter { it.issue != null || it.estimatedAccrued != null },
            { showValuationDetails = false }) { isin ->
            showValuationDetails = false
            openBond(isin)
        }
    }
}

@Composable
private fun HistoryChart(points: List<PortfolioPoint>) {
    var showResult by rememberSaveable { mutableStateOf(false) }
    var range by rememberSaveable { mutableStateOf(90) }
    var selected by remember(points, range, showResult) { mutableStateOf<Int?>(null) }
    val visible = remember(points, range) { if (range == 0) points else points.takeLast(range) }
    val nums = remember(visible, showResult) { visible.map { if (showResult) it.result else it.value } }
    val approximate = remember(visible, showResult) {
        visible.map { if (showResult) it.resultIsPartial || it.resultUsesEstimatedValuation else it.usesEstimatedValuation }
    }
    val known = remember(nums) { nums.filterNotNull() }
    Sheet {
        Text("Динамика", style = MaterialTheme.typography.titleLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(!showResult, { showResult = false }, { Text("Стоимость") })
            FilterChip(showResult, { showResult = true }, { Text("Результат") })
        }
        if (known.isEmpty()) {
            Text("График появится, когда будет достаточно котировок и данных о сделках.", color = Muted)
        } else {
            val low = known.min().toDouble()
            val high = known.max().toDouble()
            val activeIndex = selected ?: nums.indexOfLast { it != null }
            val selectedPoint = visible.getOrNull(activeIndex)
            Text("${dateLabel(selectedPoint?.date?.toString(), short = true)}  ·  ${rubles(nums.getOrNull(activeIndex))}",
                style = MaterialTheme.typography.titleMedium)
            if (showResult && selectedPoint?.resultIsPartial == true) {
                Text("Частичный результат · ${selectedPoint.issuesWithResult} из ${selectedPoint.issuesTracked} выпусков",
                    color = Amber, style = MaterialTheme.typography.bodySmall)
            }
            val selectedUsesEstimate = if (showResult) selectedPoint?.resultUsesEstimatedValuation
                else selectedPoint?.usesEstimatedValuation
            if (selectedUsesEstimate == true && nums.getOrNull(activeIndex) != null) {
                Text("Точка включает расчётную стоимость", color = Amber, style = MaterialTheme.typography.bodySmall)
            }
            Canvas(Modifier.fillMaxWidth().height(136.dp).semantics {
                contentDescription = "График ${if (showResult) "финансового результата" else "стоимости портфеля"}"
            }.pointerInput(nums) {
                detectTapGestures { tap ->
                    selected = ((tap.x / size.width) * nums.lastIndex).roundToInt().coerceIn(0, nums.lastIndex)
                }
            }) {
                val pad = 6.dp.toPx()
                fun xy(index: Int, number: BigDecimal): Offset {
                    val x = if (nums.size == 1) size.width / 2 else pad + index.toFloat() / nums.lastIndex * (size.width - 2 * pad)
                    val ratio = if (low == high) 0.5 else (number.toDouble() - low) / (high - low)
                    return Offset(x, pad + ((1 - ratio) * (size.height - 2 * pad)).toFloat())
                }
                repeat(4) { index ->
                    val y = pad + (size.height - 2 * pad) * index / 3f
                    drawLine(Divider, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
                }
                var previous: Offset? = null
                nums.forEachIndexed { index, number ->
                    val here = number?.let { xy(index, it) }
                    if (here != null) {
                        previous?.let { drawLine(Forest, it, here, 2.dp.toPx(), StrokeCap.Round) }
                            ?: drawCircle(Forest, 2.dp.toPx(), here)
                        if (approximate[index]) drawCircle(Amber, 2.dp.toPx(), here)
                    }
                    previous = here
                }
                nums.getOrNull(activeIndex)?.let { number ->
                    val point = xy(activeIndex, number)
                    drawLine(Divider, Offset(point.x, 0f), Offset(point.x, size.height), 1.dp.toPx())
                    drawCircle(if (approximate[activeIndex]) Amber else Forest, 4.dp.toPx(), point)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(dateLabel(visible.firstOrNull()?.date?.toString(), short = true), color = Muted)
                Text(dateLabel(visible.lastOrNull()?.date?.toString(), short = true), color = Muted)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(30 to "1 мес", 90 to "3 мес", 365 to "1 год", 0 to "Всё").forEach { (days, label) ->
                FilterChip(range == days, { range = days }, { Text(label, maxLines = 1) })
            }
        }
    }
}

@Composable
fun TradesPage(data: Dashboard, add: () -> Unit, edit: (Trade) -> Unit, scopePicker: @Composable () -> Unit) {
    PageList {
        item { PageHeader("Сделки", "История покупок и продаж") }
        item { scopePicker() }
        item { Button(onClick = add) { Glyph(Symbol.PLUS); Text("Добавить сделку") } }
        if (data.portfolio.trades.isEmpty()) item {
            EmptyCard(Symbol.LIST, "Сделок пока нет", "Каждая покупка и продажа появится в журнале.")
        }
        items(data.portfolio.trades.sortedByDescending { it.date }, key = { it.id }) { trade ->
            TradeRow(data, trade, { edit(trade) })
        }
    }
}

@Composable
private fun TradeRow(data: Dashboard, trade: Trade, edit: () -> Unit) {
    val title = if (trade.side == TradeSide.BUY) "Покупка" else "Продажа"
    Sheet(Modifier.clickable(onClick = edit)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(data.bondsByIsin[trade.isin]?.name ?: trade.isin, style = MaterialTheme.typography.titleMedium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("$title · ${trade.quantity} шт. · ${dateLabel(trade.date, true)}", color = Muted)
                if (data.selectedAccount == null && data.accountsById.size > 1) {
                    data.accountsById[trade.portfolioId]?.let { Text(it.name, color = Muted, style = MaterialTheme.typography.bodySmall) }
                }
            }
            Text(rubles(data.math.tradeValue(trade)), fontWeight = FontWeight.SemiBold)
            Glyph(Symbol.NEXT, color = Forest)
        }
        if (data.math.tradeValue(trade) == null) {
            Text("Для суммы сделки нужны НКД или номинал", color = Amber, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private enum class PayoutTab(val label: String) { EXPECTED("Ожидаются"), RECEIVED("Получены") }

@Composable
fun PaymentsPage(data: Dashboard, scopePicker: @Composable () -> Unit) {
    var tab by rememberSaveable { mutableStateOf(PayoutTab.EXPECTED) }
    val today = data.today.toString()
    val received = tab == PayoutTab.RECEIVED
    val selected = if (received) data.payments.filter { it.date <= today }.sortedByDescending { it.date }
    else data.payments.filter { it.date > today }.sortedBy { it.date }
    val isMissing = data.portfolio.bonds.any { bond ->
        bond.calendarUpdatedAt == 0L && data.portfolio.trades.any { it.isin == bond.isin }
    }
    PageList {
        item { PageHeader("Выплаты", "Купоны, амортизация и погашение") }
        item { scopePicker() }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                PayoutTab.entries.forEach { option -> FilterChip(tab == option, { tab = option }, { Text(option.label, maxLines = 1) }) }
            }
        }
        if (received) item {
            Sheet {
                Text("По календарю Мосбиржи", color = Muted)
                InfoRow("Получено купонами", rubles(data.overview.receivedCoupons))
                InfoRow("Возвращено номинала", rubles(data.overview.receivedPrincipal))
                if (data.overview.payoutsIncomplete) Text("Итоги по выплатам неполные", color = Amber)
            }
        }
        if (received && data.monthlyReceipts.isNotEmpty()) {
            item(key = "received-plot") { ReceivedPaymentsChart(data.monthlyReceipts, data.today) }
        }
        if (selected.isEmpty()) item {
            EmptyCard(Symbol.CALENDAR, if (isMissing) "Календарь загружен не полностью" else "Выплат в этом списке пока нет",
                if (isMissing) "Обновите данные в портфеле, чтобы загрузить календарь с Мосбиржи."
                else "Купоны и погашения появятся по выпускам из вашего портфеля.")
        }
        items(selected, key = { it.id }) { payment -> PaymentRow(data, payment) }
    }
}

@Composable
private fun PaymentRow(data: Dashboard, payment: Payment) {
    val estimate = data.math.estimate(payment)
    Sheet {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(payment.kind.label, style = MaterialTheme.typography.titleMedium)
                Text(data.bondsByIsin[payment.isin]?.name ?: payment.isin, color = Muted)
            }
            Text(rubles(estimate.amount), fontWeight = FontWeight.SemiBold)
        }
        Text(dateLabel(payment.date) +
            (payment.amountPerBond?.toBigDecimal()?.let { " · ${rubles(it)} / шт." } ?: " · сумма на бумагу пока неизвестна"),
            color = Muted)
        if (estimate.units == null) Text("Недостаточно данных об остатке бумаг", color = Amber)
        else if (payment.recordDate == null) {
            Text("${estimate.units} шт. по остатку на дату выплаты", color = Muted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun BondDetails(data: Dashboard, bond: Bond, busy: Boolean, back: () -> Unit, refresh: () -> Unit,
                add: () -> Unit, edit: (Trade) -> Unit) {
    val context = LocalContext.current
    val holding = data.overview.holdings.find { it.bond.isin == bond.isin }
    val recent = data.portfolio.quotes.filter { it.isin == bond.isin }.maxByOrNull { it.date }
    val trades = data.portfolio.trades.filter { it.isin == bond.isin }.sortedByDescending { it.date }
    val payouts = data.payments.filter { it.isin == bond.isin }.sortedByDescending { it.date }
    PageList {
        item { PageHeader(bond.name, "${bond.secid} · ${bond.isin}", busy, refresh, back) }
        item {
            Sheet {
                InfoRow("В портфеле", "${holding?.quantity ?: 0} шт.")
                InfoRow(if (holding?.estimatedAccrued != null) "Расчётная стоимость" else "Рыночная стоимость",
                    rubles(holding?.value))
                holding?.issue?.let { Text(it.description(), color = Amber) }
                holding?.estimatedAccrued?.let { InfoRow("НКД по календарю", rubles(it)) }
                InfoRow("Цена последней котировки", recent?.cleanPercent?.let { "${decimalLabel(it)} %" } ?: "—")
                InfoRow("Непогашенный номинал", rubles(bond.nominal?.toBigDecimalOrNull() ?: recent?.nominal?.toBigDecimalOrNull()))
                InfoRow("НКД по последней котировке", rubles(recent?.accrued?.toBigDecimalOrNull()))
                InfoRow("Дата последней котировки", dateLabel(recent?.date, true))
                InfoRow("Погашение по данным биржи", dateLabel(bond.maturity, true))
                InfoRow("Календарь обновлён", if (bond.calendarUpdatedAt > 0) instantLabel(bond.calendarUpdatedAt) else "Ещё не загружен")
                TextButton(onClick = {
                    val ticker = Uri.encode(bond.secid.lowercase())
                    context.startActivity(Intent(Intent.ACTION_VIEW, "https://www.moex.com/ru/bonds/$ticker".toUri()))
                }) { Text("Карточка на Мосбирже") }
            }
        }
        item { Button(onClick = add) { Glyph(Symbol.PLUS); Text("Добавить сделку") } }
        item { SectionTitle("Сделки") }
        items(trades, key = { it.id }) { item -> TradeRow(data, item) { edit(item) } }
        item { SectionTitle("Выплаты по выпуску") }
        if (payouts.isEmpty()) item { Sheet { Text("Данные о выплатах пока отсутствуют", color = Muted) } }
        items(payouts, key = { it.id }) { item -> PaymentRow(data, item) }
    }
}

@Composable
fun BackupPage(data: Portfolio, save: () -> Unit, open: () -> Unit, share: () -> Unit) {
    PageList {
        item { PageHeader("Резервная копия", "Перенос портфеля между устройствами") }
        item {
            Sheet {
                Text("Google Drive", style = MaterialTheme.typography.headlineMedium)
                Text("Выберите Google Drive в системном окне. Если Диск там недоступен, воспользуйтесь кнопкой «Поделиться копией» или скачайте файл из Диска перед восстановлением.", color = Muted)
                Button(onClick = save, modifier = Modifier.fillMaxWidth()) { Text("Сохранить копию") }
                OutlinedButton(onClick = open, modifier = Modifier.fillMaxWidth()) { Text("Открыть копию") }
                TextButton(onClick = share, modifier = Modifier.fillMaxWidth()) { Text("Поделиться копией") }
            }
        }
        item {
            Sheet {
                Text("В копию входят", style = MaterialTheme.typography.titleLarge)
                InfoRow("Портфели", data.accounts.size.toString())
                InfoRow("Сделки", data.trades.size.toString())
                InfoRow("События календаря", data.payments.count { it.isScheduled }.toString())
                InfoRow("Сохранённые котировки", data.quotes.size.toString())
            }
        }
    }
}

@Composable
fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), color = Muted)
        Text(value, fontWeight = FontWeight.SemiBold)
    }
}
