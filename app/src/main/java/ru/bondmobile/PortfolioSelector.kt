package ru.bondmobile

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun PortfolioSelector(
    accounts: List<NamedPortfolio>,
    selectedId: String?,
    onSelect: (String?) -> Unit,
    allowAll: Boolean = true,
    onCreate: (() -> Unit)? = null,
    onRename: ((NamedPortfolio) -> Unit)? = null
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = accounts.find { it.id == selectedId }
    val label = selected?.name ?: if (allowAll) "Все портфели" else "Выбрать портфель"
    Box(Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Glyph(Symbol.FOLDER)
            Spacer(Modifier.width(8.dp))
            Text(label, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("▾")
        }
        DropdownMenu(expanded, { expanded = false }) {
            if (allowAll) DropdownMenuItem(
                text = { Text("Все портфели") },
                onClick = { expanded = false; onSelect(null) },
                trailingIcon = if (selected == null) ({ Glyph(Symbol.CHECK) }) else null
            )
            accounts.forEach { account ->
                DropdownMenuItem(text = { Text(account.name) },
                    onClick = { expanded = false; onSelect(account.id) },
                    trailingIcon = if (account == selected) ({ Glyph(Symbol.CHECK) }) else null)
            }
            if (onCreate != null) {
                HorizontalDivider()
                DropdownMenuItem(text = { Text("Новый портфель") }, leadingIcon = { Glyph(Symbol.PLUS) },
                    onClick = { expanded = false; onCreate() })
            }
            if (selected != null && onRename != null) {
                DropdownMenuItem(text = { Text("Переименовать портфель") },
                    onClick = { expanded = false; onRename(selected) })
            }
        }
    }
}

@Composable
fun PortfolioNameDialog(
    account: NamedPortfolio?,
    close: () -> Unit,
    save: (String, (String?) -> Unit) -> Unit
) {
    var name by rememberSaveable { mutableStateOf(account?.name.orEmpty()) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!saving) close() },
        title = { Text(if (account == null) "Новый портфель" else "Переименовать портфель") },
        text = {
            OutlinedTextField(name, { name = it.take(MAX_PORTFOLIO_NAME_LENGTH); error = null },
                modifier = Modifier.fillMaxWidth(), label = { Text("Название портфеля") }, singleLine = true,
                isError = error != null, supportingText = error?.let { message -> { Text(message) } })
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank() && !saving, onClick = {
                saving = true
                save(name) { failure ->
                    saving = false
                    if (failure == null) close() else error = failure
                }
            }) { Text("Сохранить") }
        },
        dismissButton = { TextButton(enabled = !saving, onClick = close) { Text("Отмена") } }
    )
}
