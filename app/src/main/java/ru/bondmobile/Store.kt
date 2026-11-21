package ru.bondmobile

import android.app.Application
import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.TimeUnit

@Dao
interface PortfolioDao {
    @Query("SELECT * FROM portfolios ORDER BY name COLLATE NOCASE, id") suspend fun accounts(): List<NamedPortfolio>
    @Query("SELECT * FROM bonds") suspend fun bonds(): List<Bond>
    @Query("SELECT * FROM bonds WHERE isin = :isin") suspend fun bond(isin: String): Bond?
    @Query("SELECT * FROM trades") suspend fun trades(): List<Trade>
    @Query("SELECT * FROM payments") suspend fun payments(): List<Payment>
    @Query("SELECT * FROM payments WHERE isin = :isin") suspend fun payments(isin: String): List<Payment>
    @Query("SELECT * FROM quotes") suspend fun quotes(): List<Quote>
    @Query("SELECT * FROM quotes WHERE isin = :isin AND date >= :from")
    suspend fun recentQuotes(isin: String, from: String): List<Quote>

    @Upsert suspend fun writeBond(value: Bond)
    @Upsert suspend fun writeAccount(value: NamedPortfolio)
    @Upsert suspend fun writeTrades(values: List<Trade>)
    @Upsert suspend fun writePayments(values: List<Payment>)
    @Upsert suspend fun writeQuotes(values: List<Quote>)
    @Query("DELETE FROM trades WHERE id = :id") suspend fun deleteTrade(id: String)
    @Query("DELETE FROM payments WHERE id = :id") suspend fun deletePayment(id: String)
}

@Database(entities = [Bond::class, Trade::class, Payment::class, Quote::class, NamedPortfolio::class], version = 3)
abstract class BondDatabase : RoomDatabase() {
    abstract fun portfolio(): PortfolioDao
}

private fun seedDefaultPortfolio(db: SupportSQLiteDatabase) {
    db.execSQL("INSERT INTO portfolios (id, name) VALUES (?, ?)", arrayOf(defaultPortfolio.id, defaultPortfolio.name))
}

internal val migratePortfolios = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE portfolios (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL)")
        seedDefaultPortfolio(db)
        db.execSQL("""
            CREATE TABLE trades_new (
                id TEXT NOT NULL PRIMARY KEY, isin TEXT NOT NULL, side TEXT NOT NULL,
                quantity INTEGER NOT NULL, date TEXT NOT NULL, settlement TEXT, unit TEXT NOT NULL,
                price TEXT NOT NULL, nominal TEXT, accrued TEXT, fee TEXT NOT NULL, portfolioId TEXT NOT NULL,
                FOREIGN KEY(portfolioId) REFERENCES portfolios(id) ON UPDATE NO ACTION ON DELETE NO ACTION
            )
        """.trimIndent())
        db.execSQL("""
            INSERT INTO trades_new (id, isin, side, quantity, date, settlement, unit, price, nominal, accrued, fee, portfolioId)
            SELECT id, isin, side, quantity, date, settlement, unit, price, nominal, accrued, fee, ? FROM trades
        """.trimIndent(), arrayOf(DEFAULT_PORTFOLIO_ID))
        db.execSQL("DROP TABLE trades")
        db.execSQL("ALTER TABLE trades_new RENAME TO trades")
        db.execSQL("CREATE INDEX index_trades_isin ON trades (isin)")
        db.execSQL("CREATE INDEX index_trades_portfolioId_isin ON trades (portfolioId, isin)")
    }
}

internal val migrateDefaultStatus = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE bonds ADD COLUMN defaultStatus TEXT")
        db.execSQL("ALTER TABLE bonds ADD COLUMN paymentsUnverifiedThrough TEXT")
    }
}

class BondApplication : Application() {
    val repository by lazy {
        PortfolioRepository(Room.databaseBuilder(this, BondDatabase::class.java, "portfolio.db")
            .addMigrations(migratePortfolios, migrateDefaultStatus)
            .addCallback(object : RoomDatabase.Callback() {
                override fun onCreate(db: SupportSQLiteDatabase) = seedDefaultPortfolio(db)
            }).build())
    }

    override fun onCreate() {
        super.onCreate()
        val request = PeriodicWorkRequestBuilder<RefreshWorker>(6, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("moex-refresh", ExistingPeriodicWorkPolicy.KEEP, request)
    }
}

class RefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val errors = (applicationContext as BondApplication).repository.refresh()
        return if (errors.isNotEmpty() && runAttemptCount < 3) Result.retry() else Result.success()
    }
}

data class BondSearch(val matches: List<Bond>, val offline: Boolean)

class PortfolioRepository(private val database: BondDatabase, private val moex: Moex = Moex()) {
    private val dao = database.portfolio()
    private val refreshLock = Mutex()
    val portfolio = database.invalidationTracker.createFlow("bonds", "trades", "payments", "quotes", "portfolios")
        .map { snapshot() }

    suspend fun snapshot(): Portfolio = database.withTransaction {
        Portfolio(dao.bonds(), dao.trades(), dao.payments(), dao.quotes(), dao.accounts())
    }

    suspend fun savePortfolio(id: String?, name: String): NamedPortfolio = database.withTransaction {
        val trimmed = name.trim()
        require(trimmed.isNotBlank() && trimmed.length <= MAX_PORTFOLIO_NAME_LENGTH) { "Введите название портфеля" }
        val accounts = dao.accounts()
        require(id == null || accounts.any { it.id == id }) { "Портфель не найден" }
        require(accounts.none { it.id != id && it.name.equals(trimmed, ignoreCase = true) }) {
            "Портфель с этим названием уже существует"
        }
        NamedPortfolio(id ?: UUID.randomUUID().toString(), trimmed).also { dao.writeAccount(it) }
    }

    suspend fun search(query: String): BondSearch = withContext(Dispatchers.IO) {
        val local = dao.bonds().filter {
            it.isin.contains(query, true) || it.secid.contains(query, true) || it.name.contains(query, true)
        }
        val remote = attempt { moex.search(query) }
        val merged = (local + remote.getOrDefault(emptyList())).distinctBy { it.isin }
            .filter { it.currency == null || it.currency.isRubles() }
        BondSearch(merged, remote.isFailure)
    }

    suspend fun placementDate(bond: Bond): LocalDate? = withContext(Dispatchers.IO) { moex.placementDate(bond) }

    suspend fun saveTrade(bond: Bond, trade: Trade, moveIssue: Boolean = false) = database.withTransaction {
        require(bond.currency == null || bond.currency.isRubles()) { "Первая версия поддерживает рублёвые облигации" }
        require(dao.accounts().any { it.id == trade.portfolioId }) { "Выберите портфель" }
        val storedBond = dao.bond(bond.isin)
        if (trade.side == TradeSide.SELL) {
            require(storedBond != null) { "Сначала добавьте покупку этого выпуска" }
        }
        val current = dao.trades()
        val original = current.find { it.id == trade.id }
        val related = if (moveIssue && original != null && original.portfolioId != trade.portfolioId) {
            current.filter { it.id != trade.id && it.isin == original.isin && it.portfolioId == original.portfolioId }
        } else emptyList()
        val changes = (related.map { it.copy(portfolioId = trade.portfolioId) } + trade).associateBy { it.id }
        val allTrades = current.filterNot { it.id in changes } + changes.values
        require(validQuantityHistory(allTrades)) { "Проверьте положительный остаток и даты операций в каждом портфеле" }
        val redemption = dao.payments(bond.isin).redemptionDate(storedBond ?: bond)
        require(redemption == null || trade.date <= redemption) { "Дата сделки позже погашения по календарю" }
        if (storedBond == null) dao.writeBond(bond)
        dao.writeTrades(changes.values.toList())
    }

    suspend fun deleteTrade(id: String) = database.withTransaction {
        val remaining = dao.trades().filter { it.id != id }
        require(validQuantityHistory(remaining)) { "Сначала исправьте связанные продажи" }
        dao.deleteTrade(id)
    }

    suspend fun mergeBackup(imported: Portfolio) = database.withTransaction {
        val local = snapshot()
        val localTrades = local.trades.associateBy { it.id }
        val localPayments = local.payments.associateBy { it.id }
        require(imported.trades.none { incoming ->
            localTrades[incoming.id]?.let { old -> old.copy(portfolioId = incoming.portfolioId) != incoming } == true
        }) {
            "В копии есть изменённая версия уже существующей сделки. Сверьте файлы или восстановите на чистой установке."
        }
        val allTrades = (local.trades + imported.trades).distinctBy { it.id }
        require(validQuantityHistory(allTrades)) { "Импорт привёл бы к отрицательному или недопустимому остатку" }
        val accounts = local.accounts.associateBy { it.id }.toMutableMap()
        for (incoming in imported.accounts) {
            val isPlaceholder = incoming.id == DEFAULT_PORTFOLIO_ID && local.accounts == listOf(defaultPortfolio) && local.trades.isEmpty()
            if (incoming.id in accounts && !isPlaceholder) continue
            var name = incoming.name
            var number = 2
            while (accounts.values.any { it.id != incoming.id && it.name.equals(name, ignoreCase = true) }) {
                val suffix = " (${number++})"
                name = incoming.name.take(MAX_PORTFOLIO_NAME_LENGTH - suffix.length) + suffix
            }
            val account = incoming.copy(name = name)
            accounts[account.id] = account
            dao.writeAccount(account)
        }
        val knownBonds = local.bonds.map { it.isin }.toSet()
        imported.bonds.filter { it.isin !in knownBonds }.forEach { dao.writeBond(it) }
        dao.writeTrades(imported.trades.filter { it.id !in localTrades })
        dao.writePayments(imported.payments.filter { it.id !in localPayments })
        val quoteKeys = local.quotes.map { it.isin to it.date }.toSet()
        dao.writeQuotes(imported.quotes.filter { (it.isin to it.date) !in quoteKeys })
    }

    suspend fun refresh(onlyIsin: String? = null): List<String> = withContext(Dispatchers.IO) {
        refreshLock.withLock {
            val active = dao.trades().groupBy { it.isin }
            val bonds = dao.bonds().filter { it.isin in active && (onlyIsin == null || it.isin == onlyIsin) }
            bonds.flatMap { original -> refreshBond(original, active.getValue(original.isin)) }
        }
    }

    private suspend fun refreshBond(original: Bond, trades: List<Trade>): List<String> = supervisorScope {
        val today = marketToday()
        val asOf = System.currentTimeMillis()
        val errors = mutableListOf<String>()
        val described = attempt { moex.describe(original) }.getOrElse {
            errors += "${original.secid} · карточка и статус выпуска"
            original
        }
        if (described.currency != null && !described.currency.isRubles()) {
            dao.writeBond(described)
            return@supervisorScope listOf("${original.secid} · выпуск в иностранной валюте")
        }

        val earliest = LocalDate.parse(trades.minOf { it.date }).minusDays(7).coerceAtLeast(earliestDate)
        val from = if (described.historyStart == null || earliest.toString() < described.historyStart) earliest
        else maxOf(earliest, today.minusDays(21))
        val maturity = described.maturity?.let(LocalDate::parse)
        val end = minOf(today, maturity ?: today)
        val cachedRecent = dao.recentQuotes(original.isin, from.toString()).associateBy { it.date }
        val quotesTask = async {
            attempt {
                if (from > end) emptyList() else moex.history(described, from, end)
            }
        }
        val currentTask = async {
            attempt {
                if (maturity != null && maturity < today) described to null else moex.current(described)
            }
        }
        val calendarTask = async { attempt { moex.calendar(described) } }

        val historical = quotesTask.await().getOrElse {
            errors += "${original.secid} · история котировок"
            null
        }
        val (latestBond, latestQuote) = currentTask.await().getOrElse {
            errors += "${original.secid} · текущая котировка"
            described to null
        }
        val calendar = calendarTask.await().getOrNull()
        val newEvents = calendar?.payments
        if (calendar?.complete != true) errors += "${original.secid} · календарь выплат"
        database.withTransaction {
            val record = latestBond.copy(
                calendarUpdatedAt = if (calendar?.complete == true) asOf else original.calendarUpdatedAt,
                historyStart = if (historical != null) minOf(earliest.toString(), original.historyStart ?: "9999-12-31")
                else original.historyStart
            )
            dao.writeBond(record)
            val updatedQuotes = buildMap {
                (historical.orEmpty() + listOfNotNull(latestQuote)).forEach { quote ->
                    val old = get(quote.date) ?: cachedRecent[quote.date]
                    put(quote.date, quote.copy(
                        nominal = quote.nominal ?: old?.nominal,
                        accrued = quote.accrued ?: old?.accrued
                    ))
                }
            }.values.toList()
            if (record.currency == null || record.currency.isRubles()) {
                dao.writeQuotes(updatedQuotes)
                val persisted = dao.payments(original.isin).associateBy { it.id }
                val merged = newEvents.orEmpty().map { incoming ->
                    val old = persisted[incoming.id]
                    if (old != null && old.date == incoming.date && incoming.kind == PaymentKind.REDEMPTION &&
                        incoming.amountPerBond == null && incoming.recordDate == null && calendar?.complete != true) {
                        old
                    } else {
                        incoming.copy(receivedDate = old?.receivedDate, receivedAmount = old?.receivedAmount)
                    }
                }
                if (calendar?.complete == true && merged.isNotEmpty()) {
                    val liveIds = merged.map { it.id }.toSet()
                    persisted.values.filter { it.isScheduled && it.id !in liveIds }.forEach { dao.deletePayment(it.id) }
                }
                dao.writePayments(merged)
            }
        }
        errors
    }

    private suspend fun <T> attempt(action: suspend () -> T): Result<T> = try {
        Result.success(action())
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        Result.failure(error)
    }
}
