package ru.bondmobile

import android.app.DatePickerDialog
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID

data class TradeDraft(val bond: Bond? = null, val trade: Trade? = null)

@Composable
fun TradeEditor(model: PortfolioViewModel, draft: TradeDraft, local: List<Bond>, onClose: () -> Unit) {
    val existing = draft.trade
    val today = marketToday()
    val search by model.searchState.collectAsStateWithLifecycle()
    val focus = LocalFocusManager.current
    var selected by remember { mutableStateOf(draft.bond) }
    var query by rememberSaveable { mutableStateOf("") }
    var side by rememberSaveable { mutableStateOf(existing?.side ?: TradeSide.BUY) }
    var quantity by rememberSaveable { mutableStateOf(existing?.quantity?.toString().orEmpty()) }
    var day by rememberSaveable { mutableStateOf(existing?.date ?: today.toString()) }
    var settlement by rememberSaveable { mutableStateOf(existing?.settlement) }
    var unit by rememberSaveable { mutableStateOf(existing?.unit ?: PriceUnit.PERCENT) }
    var price by rememberSaveable { mutableStateOf(existing?.price.orEmpty()) }
    var nominal by rememberSaveable { mutableStateOf(existing?.nominal.orEmpty()) }
    var accrued by rememberSaveable { mutableStateOf(existing?.accrued.orEmpty()) }
    var fee by rememberSaveable { mutableStateOf(existing?.fee.orEmpty()) }
    var expanded by rememberSaveable { mutableStateOf(existing?.nominal != null || existing?.accrued != null ||
        existing?.settlement != null || existing?.fee?.toBigDecimalOrNull()?.signum() == 1) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var delete by remember { mutableStateOf(false) }
    val id = rememberSaveable { existing?.id ?: UUID.randomUUID().toString() }
    val close = { if (!saving) { model.clearSearch(); onClose() } }

    AlertDialog(
        onDismissRequest = close,
        title = { Text(if (existing == null) "Добавить сделку" else "Изменить сделку") },
        text = {
            EditorFields {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(side == TradeSide.BUY, { side = TradeSide.BUY }, { Text("Покупка") })
                    FilterChip(side == TradeSide.SELL, { side = TradeSide.SELL }, { Text("Продажа") })
                }
                val bond = selected
                if (bond == null) {
                    OutlinedTextField(query, {
                        query = it.take(45)
                        model.clearSearch()
                    }, label = { Text("Тикер или ISIN") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedButton(onClick = {
                        focus.clearFocus()
                        model.search(query.trim())
                    }, enabled = query.trim().length >= 2 && search != SearchState.Loading) { Text("Найти выпуск") }
                    if (search == SearchState.Loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    val result = (search as? SearchState.Done)?.result
                    val matches = result?.matches ?: local.filter { b ->
                        b.isin.contains(query, true) || b.secid.contains(query, true) || b.name.contains(query, true)
                    }
                    matches.take(8).forEach { b ->
                        BondChoice(b) { selected = b; focus.clearFocus(); model.clearSearch() }
                    }
                    if (result?.offline == true) Text("Поиск Мосбиржи недоступен. Можно выбрать сохранённый выпуск или ввести ISIN вручную.", color = Muted)
                    else if (result?.matches?.isEmpty() == true) Text("Выпуски не найдены", color = Muted)
                    val manual = query.trim().uppercase().takeIf(isinPattern::matches)
                    if (manual != null && matches.none { it.isin == manual } && side == TradeSide.BUY) {
                        TextButton(onClick = {
                            selected = Bond(manual, manual, manual)
                            focus.clearFocus()
                            model.clearSearch()
                        }) { Text("Ввести ISIN вручную") }
                    }
                } else {
                    InfoRow("Выпуск", bond.name)
                    if (existing == null && draft.bond == null) TextButton(onClick = { selected = null }) { Text("Другой выпуск") }
                }
                Field(quantity, { quantity = it }, "Количество, шт.", KeyboardType.Number)
                DateField("Дата сделки", day, earliestDate, today, { value ->
                    day = value
                    if (settlement != null && settlement!! < day) settlement = null
                })
                Text("Цена за одну облигацию", color = Muted)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(unit == PriceUnit.PERCENT, { unit = PriceUnit.PERCENT }, { Text("% номинала") })
                    FilterChip(unit != PriceUnit.PERCENT, { if (unit == PriceUnit.PERCENT) unit = PriceUnit.RUB_CLEAN }, { Text("В рублях") })
                }
                if (unit != PriceUnit.PERCENT) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(unit == PriceUnit.RUB_CLEAN, { unit = PriceUnit.RUB_CLEAN }, { Text("Без НКД") })
                        FilterChip(unit == PriceUnit.RUB_WITH_ACCRUED, { unit = PriceUnit.RUB_WITH_ACCRUED }, { Text("С НКД") })
                    }
                }
                Field(price, { price = it }, if (unit == PriceUnit.PERCENT) "Цена, %" else "Цена, ₽")
                if (expanded) {
                    if (unit == PriceUnit.PERCENT) Field(nominal, { nominal = it }, "Номинал на дату сделки, ₽ / шт.")
                    if (unit != PriceUnit.RUB_WITH_ACCRUED) Field(accrued, { accrued = it }, "НКД по отчёту, ₽ / шт.")
                    Field(fee, { fee = it }, "Комиссия за сделку, ₽")
                    DateField("Дата расчётов (по отчёту)", settlement, LocalDate.parse(day), today, { settlement = it },
                        if (settlement == null) null else ({ settlement = null }))
                    Text("Пустые НКД и номинал подставляются из биржевых данных, когда они есть. Пустая комиссия считается нулевой.",
                        color = Muted, style = MaterialTheme.typography.bodySmall)
                } else TextButton(onClick = { expanded = true }) { Text("НКД, комиссия и дата расчётов") }
                error?.let { Text(it, color = Rust) }
                if (existing != null) TextButton(enabled = !saving, onClick = { delete = true }) {
                    Text("Удалить сделку", color = Rust)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !saving, onClick = {
                val bond = selected
                val units = quantity.trim().toIntOrNull()
                val enteredPrice = price.decimalOrNull()
                val enteredNominal = nominal.decimalOrNull()
                val enteredAccrued = accrued.decimalOrNull()
                val enteredFee = fee.decimalOrNull()
                error = when {
                    bond == null -> "Выберите выпуск"
                    units == null || units !in 1..MAX_QUANTITY -> "Укажите положительное целое количество"
                    enteredPrice == null || enteredPrice.signum() <= 0 -> "Укажите положительную цену"
                    unit == PriceUnit.PERCENT && nominal.isNotBlank() && (enteredNominal == null || enteredNominal.signum() <= 0) -> "Проверьте номинал"
                    unit != PriceUnit.RUB_WITH_ACCRUED && accrued.isNotBlank() && enteredAccrued == null -> "Проверьте НКД"
                    fee.isNotBlank() && enteredFee == null -> "Проверьте комиссию"
                    else -> null
                }
                if (error == null && bond != null && units != null && enteredPrice != null) {
                    saving = true
                    val trade = Trade(id, bond.isin, side, units, day, settlement, unit, enteredPrice.toPlainString(),
                        enteredNominal?.toPlainString()?.takeIf { unit == PriceUnit.PERCENT },
                        enteredAccrued?.toPlainString()?.takeUnless { unit == PriceUnit.RUB_WITH_ACCRUED },
                        enteredFee?.toPlainString() ?: "0")
                    model.saveTrade(bond, trade) { reason ->
                        saving = false
                        if (reason == null) close() else error = reason
                    }
                }
            }) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = close, enabled = !saving) { Text("Отмена") } }
    )
    if (delete && existing != null) {
        AlertDialog(onDismissRequest = { delete = false }, title = { Text("Удалить сделку?") },
            text = { Text("Стоимость и история портфеля будут пересчитаны.") },
            confirmButton = {
                TextButton(onClick = {
                    delete = false
                    saving = true
                    model.deleteTrade(existing.id) { reason ->
                        saving = false
                        if (reason == null) close() else error = reason
                    }
                }) { Text("Удалить", color = Rust) }
            }, dismissButton = { TextButton(onClick = { delete = false }) { Text("Отмена") } })
    }
}

@Composable
private fun EditorFields(content: @Composable () -> Unit) {
    Column(Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(7.dp)) { content() }
}

@Composable
private fun BondChoice(bond: Bond, choose: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = choose), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(bond.name, color = Forest, style = MaterialTheme.typography.titleMedium)
        Text(if (bond.isin == bond.secid) bond.isin else "${bond.secid} · ${bond.isin}", color = Muted)
        HorizontalDivider()
    }
}

@Composable
private fun Field(value: String, update: (String) -> Unit, label: String, type: KeyboardType = KeyboardType.Decimal) {
    OutlinedTextField(value, { update(it.take(30)) }, label = { Text(label) }, modifier = Modifier.fillMaxWidth(),
        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = type, imeAction = ImeAction.Next))
}

@Composable
private fun DateField(label: String, date: String?, earliest: LocalDate, latest: LocalDate, change: (String) -> Unit,
                      remove: (() -> Unit)? = null) {
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    Column {
        Text(label, color = Muted, style = MaterialTheme.typography.bodySmall)
        Row {
            OutlinedButton(onClick = {
                focus.clearFocus()
                val current = (date?.let(LocalDate::parse) ?: latest).coerceIn(earliest, latest)
                val selection = DatePickerDialog(context, { _, year, month, day ->
                    change(LocalDate.of(year, month + 1, day).toString())
                }, current.year, current.monthValue - 1, current.dayOfMonth)
                val zone = ZoneId.systemDefault()
                selection.datePicker.minDate = earliest.atStartOfDay(zone).toInstant().toEpochMilli()
                selection.datePicker.maxDate = latest.atStartOfDay(zone).toInstant().toEpochMilli()
                selection.show()
            }) { Glyph(Symbol.CALENDAR); Text(date?.let { dateLabel(it, true) } ?: "Выбрать дату") }
            remove?.let { TextButton(onClick = it) { Text("Сбросить") } }
        }
    }
}
