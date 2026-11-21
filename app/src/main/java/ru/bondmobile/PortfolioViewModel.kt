package ru.bondmobile

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Duration
import java.time.LocalDate
import java.time.ZonedDateTime

data class Dashboard(
    val allPortfolio: Portfolio,
    val today: LocalDate = marketToday(),
    val selectedId: String? = null
) {
    val selectedAccount = allPortfolio.accounts.find { it.id == selectedId }
    val portfolio = allPortfolio.scoped(selectedAccount?.id)
    val math = PortfolioMath(portfolio)
    val overview = math.overview(today)
    val payments = math.paymentCandidates()
    val monthlyReceipts = math.monthlyReceipts(today)
    val bondsByIsin = portfolio.bonds.associateBy { it.isin }
    val accountsById = allPortfolio.accounts.associateBy { it.id }
}

class PortfolioViewModel(app: Application) : AndroidViewModel(app) {
    private val repository = (app as BondApplication).repository
    private val resolver = app.contentResolver
    private val preferences = app.getSharedPreferences("portfolio-selection", Context.MODE_PRIVATE)
    private val selectedPortfolio = MutableStateFlow(preferences.getString("id", null))
    private val _busy = MutableStateFlow(false)
    private val _message = MutableStateFlow<String?>(null)
    private val _preview = MutableStateFlow<Portfolio?>(null)
    private val _searchState = MutableStateFlow<SearchState>(SearchState.Idle)
    private var searchJob: Job? = null
    private var refreshJob: Job? = null

    private val marketDates = flow {
        while (true) {
            val now = ZonedDateTime.now(marketZone)
            val date = now.toLocalDate()
            emit(date)
            val midnight = date.plusDays(1).atStartOfDay(marketZone)
            delay(Duration.between(now, midnight).toMillis().coerceAtLeast(1))
        }
    }

    val dashboard = combine(repository.portfolio, marketDates, selectedPortfolio, ::Dashboard).flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Dashboard(Portfolio()))
    val busy = _busy.asStateFlow()
    val message = _message.asStateFlow()
    val preview = _preview.asStateFlow()
    val searchState = _searchState.asStateFlow()

    fun selectPortfolio(id: String?) {
        selectedPortfolio.value = id
        preferences.edit().putString("id", id).apply()
    }

    fun savePortfolio(id: String?, name: String, finished: (String?) -> Unit) = action(finished) {
        val saved = repository.savePortfolio(id, name)
        selectPortfolio(saved.id)
    }

    fun refresh(isin: String? = null, quiet: Boolean = false) {
        if (refreshJob?.isActive == true) {
            if (quiet) refreshJob?.invokeOnCompletion { viewModelScope.launch { refresh(isin, quiet) } }
            return
        }
        refreshJob = viewModelScope.launch {
            _busy.value = true
            try {
                val errors = repository.refresh(isin)
                if (errors.isNotEmpty()) {
                    _message.value = "Часть данных Мосбиржи недоступна: ${errors.distinct().take(3).joinToString(", ")}."
                } else if (!quiet) {
                    _message.value = "Данные обновлены"
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                _message.value = "Обновление прервано. Доступны сохранённые данные."
            } finally {
                _busy.value = false
            }
        }
    }

    fun search(query: String) {
        searchJob?.cancel()
        _searchState.value = SearchState.Loading
        searchJob = viewModelScope.launch {
            try {
                _searchState.value = SearchState.Done(repository.search(query))
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                _searchState.value = SearchState.Done(BondSearch(emptyList(), true))
            }
        }
    }

    fun clearSearch() {
        searchJob?.cancel()
        _searchState.value = SearchState.Idle
    }

    suspend fun placementDate(bond: Bond): LocalDate? = repository.placementDate(bond)

    fun saveTrade(bond: Bond, trade: Trade, moveIssue: Boolean, finished: (String?) -> Unit) = action(finished) {
        repository.saveTrade(bond, trade, moveIssue)
        refresh(bond.isin, quiet = true)
    }

    fun deleteTrade(id: String, finished: (String?) -> Unit) = action(finished) { repository.deleteTrade(id) }

    fun dismissMessage() { _message.value = null }
    fun dismissPreview() { _preview.value = null }

    fun read(uri: Uri) = action({ error -> if (error != null) _message.value = error }) {
        _preview.value = withContext(Dispatchers.IO) {
            requireNotNull(resolver.openInputStream(uri)) { "Не удалось открыть файл" }.use { Backup.read(it) }
        }
    }

    fun import() {
        val data = _preview.value ?: return
        _preview.value = null
        action({ _message.value = it ?: "Резервная копия восстановлена" }) { repository.mergeBackup(data) }
    }

    fun export(uri: Uri) = action({ _message.value = it ?: "Резервная копия сохранена" }) {
        val data = repository.snapshot()
        withContext(Dispatchers.IO) {
            requireNotNull(resolver.openOutputStream(uri, "w")) { "Не удалось открыть файл для записи" }
                .use { Backup.write(data, it) }
        }
    }

    fun exportForSharing(onReady: (File) -> Unit) = action({ error -> if (error != null) _message.value = error }) {
        val data = repository.snapshot()
        val app = getApplication<Application>()
        val file = withContext(Dispatchers.IO) {
            val folder = File(app.cacheDir, "exports").apply { mkdirs() }
            val target = File(folder, "bond-portfolio-${marketToday()}.json")
            target.outputStream().use { Backup.write(data, it) }
            target
        }
        onReady(file)
    }

    private fun action(finished: (String?) -> Unit, work: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                work()
                finished(null)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val detail = error.message?.takeIf { it.any { ch -> ch in 'А'..'я' } }
                finished(detail ?: "Проверьте данные и повторите операцию")
            }
        }
    }
}

sealed interface SearchState {
    data object Idle : SearchState
    data object Loading : SearchState
    data class Done(val result: BondSearch) : SearchState
}
