package ru.bondmobile

import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

data class Holding(
    val bond: Bond,
    val quantity: Long,
    val value: BigDecimal?,
    val quoteDate: String?,
    val stale: Boolean
)

data class PortfolioPoint(
    val date: LocalDate,
    val value: BigDecimal?,
    val result: BigDecimal?
)

data class Overview(
    val holdings: List<Holding>,
    val points: List<PortfolioPoint>,
    val receivedCoupons: BigDecimal,
    val receivedPrincipal: BigDecimal,
    val partialValue: BigDecimal,
    val valueIsPartial: Boolean,
    val payoutsIncomplete: Boolean
)

data class PaymentEstimate(val units: Long?, val amount: BigDecimal?)

data class MonthlyReceipt(
    val coupons: BigDecimal = BigDecimal.ZERO,
    val principal: BigDecimal = BigDecimal.ZERO,
    val incomplete: Boolean = false
) {
    val total: BigDecimal get() = coupons + principal
}

class PortfolioMath(private val portfolio: Portfolio) {
    private val trades = portfolio.trades.groupBy { it.isin }
    private val quotes = portfolio.quotes.groupBy { it.isin }.mapValues { (_, rows) -> rows.sortedBy { it.date } }
    private val bonds = portfolio.bonds.associateBy { it.isin }
    private val payments = portfolio.payments.filter {
        it.isScheduled && bonds[it.isin]?.currency?.isRubles() != false
    }.groupBy { it.isin }
    private val closures = payments.mapNotNull { (isin, schedule) -> schedule.redemptionDate()?.let { isin to it } }.toMap()
    private val candidates by lazy {
        payments.values.flatten().filter { payment ->
            val cutoff = payment.recordDate ?: payment.date
            trades[payment.isin].orEmpty().any { (it.settlement ?: it.date) <= cutoff } && estimate(payment).units != 0L
        }
    }

    fun tradeValue(trade: Trade): BigDecimal? {
        val pricingDate = trade.settlement ?: trade.date
        val exactQuote = quoteAt(trade.isin, pricingDate)?.takeIf { it.date == pricingDate }
        return trade.cashFlow(nominalAt(trade.isin, pricingDate, exactQuote), exactQuote?.accrued?.toBigDecimal())
    }

    fun estimate(payment: Payment): PaymentEstimate {
        val cutoff = payment.recordDate ?: payment.date
        if (!payment.isScheduled || closures[payment.isin]?.let { it < cutoff } == true) {
            return PaymentEstimate(0, BigDecimal.ZERO)
        }
        val settled = trades[payment.isin].orEmpty().filter { (it.settlement ?: it.date) <= cutoff }
        val quantity = settled.sumOf { if (it.side == TradeSide.BUY) it.quantity.toLong() else -it.quantity.toLong() }
        if (quantity < 0) return PaymentEstimate(null, null)
        val estimate = payment.amountPerBond?.toBigDecimal()?.multiply(quantity.toBigDecimal())
        return PaymentEstimate(quantity, estimate)
    }

    fun paymentCandidates(): List<Payment> = candidates

    fun monthlyReceipts(today: LocalDate = marketToday()): Map<YearMonth, MonthlyReceipt> =
        receivedBy(today).groupBy { YearMonth.from(LocalDate.parse(it.first.date)) }.mapValues { (_, rows) ->
            summarizeReceipts(rows)
        }

    fun overview(today: LocalDate = marketToday()): Overview {
        val date = today.toString()
        val invested = trades.keys
        val dailyTrades = portfolio.trades.groupBy { it.date }
        val costFlows = portfolio.trades.associate { it.id to tradeValue(it) }
        val received = receivedBy(today)
        val dailyReceived = received.groupBy { it.first.date }
        val totals = summarizeReceipts(received)
        val balance = mutableMapOf<String, Long>()
        var totalFlow = BigDecimal.ZERO
        var flowsKnown = true
        val series = mutableListOf<PortfolioPoint>()
        val first = portfolio.trades.minOfOrNull { LocalDate.parse(it.date) } ?: today
        var day = minOf(first, today)
        var holdings = emptyList<Holding>()
        while (day <= today) {
            val key = day.toString()
            dailyTrades[key].orEmpty().forEach { trade ->
                val delta = if (trade.side == TradeSide.BUY) trade.quantity.toLong() else -trade.quantity.toLong()
                balance[trade.isin] = balance.getOrDefault(trade.isin, 0) + delta
                val flow = costFlows[trade.id]
                if (flow == null) flowsKnown = false else totalFlow += flow
            }
            dailyReceived[key].orEmpty().forEach { (_, amount) ->
                if (amount == null) flowsKnown = false else totalFlow += amount
            }
            val active = invested.mapNotNull { isin ->
                val count = balance.getOrDefault(isin, 0)
                if (count <= 0 || closures.getOrDefault(isin, "9999-12-31") <= key) null
                else holding(isin, count, day)
            }
            val completeValue = active.takeIf { items -> items.all { it.value != null } }
                ?.fold(BigDecimal.ZERO) { sum, item -> sum + item.value!! }
            if (portfolio.trades.isNotEmpty()) {
                series += PortfolioPoint(day, completeValue, completeValue?.takeIf { flowsKnown }?.plus(totalFlow))
            }
            if (day == today) holdings = active.sortedBy { it.bond.name }
            day = day.plusDays(1)
        }
        return Overview(
            holdings,
            series,
            totals.coupons,
            totals.principal,
            holdings.mapNotNull { it.value }.fold(BigDecimal.ZERO, BigDecimal::add),
            holdings.any { it.value == null },
            totals.incomplete
        )
    }

    private fun receivedBy(today: LocalDate): List<Pair<Payment, BigDecimal?>> {
        val date = today.toString()
        return candidates.filter { it.date <= date }.map { it to estimate(it).amount }
    }

    private fun summarizeReceipts(rows: List<Pair<Payment, BigDecimal?>>): MonthlyReceipt {
        val (coupons, principal) = rows.partition { it.first.kind == PaymentKind.COUPON }
        return MonthlyReceipt(coupons.sumOf { it.second ?: BigDecimal.ZERO },
            principal.sumOf { it.second ?: BigDecimal.ZERO }, rows.any { it.second == null })
    }

    private fun quoteAt(isin: String, date: String): Quote? {
        val list = quotes[isin] ?: return null
        val index = list.binarySearchBy(date) { it.date }
        return list.getOrNull(if (index >= 0) index else -index - 2)
    }

    private fun nominalAt(isin: String, date: String, exactQuote: Quote?): BigDecimal? {
        exactQuote?.nominal?.let { return it.toBigDecimal() }
        val bond = bonds[isin] ?: return null
        if (bond.nominalDate == date) bond.nominal?.let { return it.toBigDecimal() }
        val schedule = payments[isin].orEmpty()
        val coupon = schedule.firstOrNull {
            it.kind == PaymentKind.COUPON && it.periodStart != null && date >= it.periodStart && date <= it.date
        } ?: return null
        if (schedule.any { it.kind != PaymentKind.COUPON && it.date in coupon.periodStart!!..coupon.date }) return null
        return coupon.nominalBefore?.toBigDecimal()
    }

    private fun holding(isin: String, count: Long, day: LocalDate): Holding {
        val bond = bonds[isin] ?: Bond(isin, isin, isin)
        if (bond.currency != null && !bond.currency.isRubles()) return Holding(bond, count, null, null, false)
        val date = day.toString()
        val quote = quoteAt(isin, date)
        val age = quote?.let { ChronoUnit.DAYS.between(LocalDate.parse(it.date), day) }
        val crossedPayment = quote != null && (payments[isin].orEmpty().any { it.date > quote.date && it.date <= date } ||
            (bond.maturity != null && bond.maturity > quote.date && bond.maturity <= date))
        val matured = bond.maturity?.let { it <= date } == true || payments[isin].orEmpty().any {
            it.kind == PaymentKind.REDEMPTION && it.date <= date
        }
        val nominal = quote?.let { nominalAt(isin, it.date, it) }
        val currentNominal = if (quote?.date == date) nominal else nominalAt(isin, date, null)
        val changedNominal = nominal != null && currentNominal != null && nominal.compareTo(currentNominal) != 0
        val accrued = quote?.accrued?.toBigDecimal()
        val clean = quote?.cleanPercent?.toBigDecimal()
        val value = if (age != null && age <= 7 && !crossedPayment && !matured && !changedNominal &&
            nominal != null && accrued != null && clean != null) {
            (clean.multiply(nominal).movePointLeft(2) + accrued) * count.toBigDecimal()
        } else null
        return Holding(bond, count, value, quote?.date, age != null && age > 0)
    }
}
