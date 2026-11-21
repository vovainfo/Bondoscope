package ru.bondmobile

import android.content.ClipData
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {
    private val portfolioModel by viewModels<PortfolioViewModel>()
    private val updateModel by viewModels<UpdateViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { BondTheme { BondApp(portfolioModel, updateModel) } }
    }
}

private enum class Page(val label: String, val icon: Symbol) {
    PORTFOLIO("Портфель", Symbol.CHART), TRADES("Сделки", Symbol.LIST),
    PAYMENTS("Выплаты", Symbol.CALENDAR), BACKUP("Копия", Symbol.FOLDER),
    VERSION("Версия", Symbol.REFRESH)
}

@Composable
private fun BondApp(model: PortfolioViewModel, updates: UpdateViewModel) {
    val context = LocalContext.current
    val data by model.dashboard.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    val message by model.message.collectAsStateWithLifecycle()
    val preview by model.preview.collectAsStateWithLifecycle()
    val snackbars = remember { SnackbarHostState() }
    var page by rememberSaveable { mutableStateOf(Page.PORTFOLIO) }
    var details by rememberSaveable { mutableStateOf<String?>(null) }
    var editor by remember { mutableStateOf<TradeDraft?>(null) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) {
        it?.let(model::export)
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(model::read) }

    LaunchedEffect(message) {
        message?.let {
            snackbars.showSnackbar(it, withDismissAction = true)
            model.dismissMessage()
        }
    }

    BackHandler(details != null) { details = null }
    val returnToPortfolio = { page = Page.PORTFOLIO; details = null }
    Scaffold(
        containerColor = Paper,
        snackbarHost = { SnackbarHost(snackbars) },
        bottomBar = {
            if (details == null) {
                NavigationBar(containerColor = CardWhite) {
                    Page.entries.forEach { option ->
                        NavigationBarItem(
                            selected = page == option,
                            onClick = { page = option },
                            icon = { Glyph(option.icon) },
                            label = { Text(option.label) },
                            colors = NavigationBarItemDefaults.colors(indicatorColor = Mint, selectedIconColor = Forest)
                        )
                    }
                }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            val bond = data.bondsByIsin[details]
            if (bond != null) {
                BondDetails(data, bond, busy, { details = null }, { model.refresh(bond.isin) },
                    { editor = TradeDraft(bond, null) }, { editor = TradeDraft(bond, it) })
            } else when (page) {
                Page.PORTFOLIO -> PortfolioPage(data, busy, { model.refresh() },
                    { editor = TradeDraft() }, { details = it }, { page = Page.PAYMENTS })
                Page.TRADES -> TradesPage(data, { editor = TradeDraft() }, { trade ->
                    editor = TradeDraft(data.bondsByIsin[trade.isin], trade)
                })
                Page.PAYMENTS -> PaymentsPage(data)
                Page.VERSION -> UpdatePage(updates)
                Page.BACKUP -> BackupPage(data, { export.launch("bond-portfolio-${marketToday()}.json") },
                    { import.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
                    {
                        model.exportForSharing { file ->
                            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "application/json"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                clipData = ClipData.newRawUri(file.name, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(send, "Сохранить копию"))
                        }
                    })
            }
        }
    }
    editor?.let { draft -> TradeEditor(model, draft, data.portfolio.bonds, onClose = { editor = null }) }
    preview?.let { snapshot ->
        AlertDialog(
            onDismissRequest = model::dismissPreview,
            title = { Text("Восстановить портфель?") },
            text = {
                Text("Выпуски — ${snapshot.bonds.size}\nСделки — ${snapshot.trades.size}\n" +
                    "События календаря — ${snapshot.payments.count { it.isScheduled }}\n\n" +
                    "Записи будут добавлены к текущему портфелю. При расхождениях импорт остановится.")
            },
            confirmButton = {
                TextButton(onClick = { model.import(); returnToPortfolio() }) { Text("Восстановить") }
            },
            dismissButton = { TextButton(onClick = model::dismissPreview) { Text("Отмена") } }
        )
    }
}
