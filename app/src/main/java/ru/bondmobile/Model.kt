package ru.bondmobile

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId

enum class TradeSide { BUY, SELL }
enum class PriceUnit { PERCENT, RUB_CLEAN, RUB_WITH_ACCRUED }
enum class PaymentKind(val label: String) {
    COUPON("Купон"), AMORTIZATION("Амортизация"), REDEMPTION("Погашение"),
    PRINCIPAL("Выплата номинала")
}
enum class BondDefaultStatus(val label: String) {
    DEFAULT("Дефолт"), TECHNICAL_DEFAULT("Технический дефолт")
}
enum class PaymentStatus { PLANNED, BY_CALENDAR, UNVERIFIED }

@Entity(tableName = "bonds")
data class Bond(
    @PrimaryKey val isin: String,
    val secid: String,
    val name: String,
    val board: String? = null,
    val maturity: String? = null,
    val currency: String? = null,
    val nominal: String? = null,
    val nominalDate: String? = null,
    val updatedAt: Long = 0,
    val calendarUpdatedAt: Long = 0,
    val historyStart: String? = null,
    val defaultStatus: BondDefaultStatus? = null,
    val paymentsUnverifiedThrough: String? = null
) {
    fun requiresPaymentVerification(date: String): Boolean =
        defaultStatus != null || paymentsUnverifiedThrough?.let { date <= it } == true
}

const val DEFAULT_PORTFOLIO_ID = "default"
const val MAX_PORTFOLIO_NAME_LENGTH = 60
val defaultPortfolio = NamedPortfolio(DEFAULT_PORTFOLIO_ID, "Основной")

@Entity(tableName = "portfolios")
data class NamedPortfolio(@PrimaryKey val id: String, val name: String)

@Entity(
    tableName = "trades",
    indices = [Index("isin"), Index(value = ["portfolioId", "isin"])],
    foreignKeys = [ForeignKey(entity = NamedPortfolio::class, parentColumns = ["id"], childColumns = ["portfolioId"])]
)
data class Trade(
    @PrimaryKey val id: String,
    val isin: String,
    val side: TradeSide,
    val quantity: Int,
    val date: String,
    val settlement: String?,
    val unit: PriceUnit,
    val price: String,
    val nominal: String?,
    val accrued: String?,
    val fee: String,
    val portfolioId: String = DEFAULT_PORTFOLIO_ID
) {
    fun cashFlow(nominalOverride: BigDecimal? = null, accruedOverride: BigDecimal? = null): BigDecimal? {
        val priceValue = price.toBigDecimal()
        val cleanOrTotal = when (unit) {
            PriceUnit.PERCENT -> {
                val base = nominal?.toBigDecimal() ?: nominalOverride ?: return null
                priceValue.multiply(base).movePointLeft(2)
            }
            PriceUnit.RUB_CLEAN, PriceUnit.RUB_WITH_ACCRUED -> priceValue
        }
        val interest = if (unit == PriceUnit.RUB_WITH_ACCRUED) BigDecimal.ZERO
        else accrued?.toBigDecimal() ?: accruedOverride ?: return null
        val total = (cleanOrTotal + interest) * quantity.toBigDecimal()
        return if (side == TradeSide.BUY) -total - fee.toBigDecimal() else total - fee.toBigDecimal()
    }
}

@Entity(tableName = "quotes", primaryKeys = ["isin", "date"])
data class Quote(
    val isin: String,
    val date: String,
    val cleanPercent: String?,
    val nominal: String?,
    val accrued: String?
)

@Entity(tableName = "payments", indices = [Index("isin")])
data class Payment(
    @PrimaryKey val id: String,
    val isin: String,
    val kind: PaymentKind,
    val date: String,
    val recordDate: String?,
    val amountPerBond: String?,
    val periodStart: String? = null,
    val nominalBefore: String? = null,
    val source: String = "MOEX",
    // Retained for older databases and backup files.
    val receivedDate: String? = null,
    val receivedAmount: String? = null
) {
    val isScheduled: Boolean get() = source == "MOEX"
}

data class Portfolio(
    val bonds: List<Bond> = emptyList(),
    val trades: List<Trade> = emptyList(),
    val payments: List<Payment> = emptyList(),
    val quotes: List<Quote> = emptyList(),
    val accounts: List<NamedPortfolio> = listOf(defaultPortfolio)
) {
    fun scoped(portfolioId: String?): Portfolio {
        if (portfolioId == null) return this
        val selected = trades.filter { it.portfolioId == portfolioId }
        val issues = selected.map { it.isin }.toSet()
        return copy(bonds = bonds.filter { it.isin in issues }, trades = selected,
            payments = payments.filter { it.isin in issues }, quotes = quotes.filter { it.isin in issues })
    }
}

val isinPattern = Regex("[A-Z]{2}[A-Z0-9]{9}[0-9]")
const val MAX_QUANTITY = 1_000_000_000
val earliestDate: LocalDate = LocalDate.of(1990, 1, 1)

val marketZone: ZoneId = ZoneId.of("Europe/Moscow")
fun marketToday(): LocalDate = LocalDate.now(marketZone)

private val inputDecimal = Regex("[0-9]{1,14}(\\.[0-9]{1,10})?")
fun String.decimalOrNull(): BigDecimal? = trim().replace(" ", "").replace("\u00a0", "").replace("\u202f", "")
    .replace(',', '.').takeIf(inputDecimal::matches)
    ?.toBigDecimalOrNull()

fun String.isRubles(): Boolean = uppercase() in setOf("RUB", "SUR", "RUR")

fun validQuantityHistory(trades: List<Trade>): Boolean = trades.groupBy { it.portfolioId to it.isin }.values.all { transactions ->
    val byDate = transactions.groupBy { it.date }.toSortedMap()
    var balance = 0L
    byDate.values.all { daily ->
        balance += daily.sumOf { if (it.side == TradeSide.BUY) it.quantity.toLong() else -it.quantity.toLong() }
        balance in 0..MAX_QUANTITY.toLong()
    }
}

fun Iterable<Payment>.redemptionDate(bond: Bond? = null): String? =
    filter { it.isScheduled && it.kind == PaymentKind.REDEMPTION && bond?.requiresPaymentVerification(it.date) != true }
    .minOfOrNull { it.date }
