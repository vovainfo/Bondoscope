package ru.bondmobile

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

const val MAX_QUOTE_AGE_DAYS = 7L

data class Holding(
    val bond: Bond,
    val quantity: Long,
    val value: BigDecimal?,
    val quote: Quote?,
    val issue: ValuationIssue?,
    val estimatedAccrued: BigDecimal? = null
) {
    val quoteDate: String? get() = quote?.date
}

enum class QuoteField { PRICE, NOMINAL, ACCRUED }

sealed interface ValuationIssue {
    data object NoQuote : ValuationIssue
    data object OldQuote : ValuationIssue
    data object UnsupportedCurrency : ValuationIssue
    data object Maturity : ValuationIssue
    data object NominalChanged : ValuationIssue
    data class AfterPayment(val date: String, val kind: PaymentKind) : ValuationIssue
    data class CouponUnverified(val date: String) : ValuationIssue
    data class CouponAccrualUnknown(val date: String) : ValuationIssue
    data class MissingFields(val fields: List<QuoteField>) : ValuationIssue
}

data class PortfolioPoint(
    val date: LocalDate,
    val value: BigDecimal?,
    val result: BigDecimal?,
    val usesEstimatedValuation: Boolean,
    val positionsWithValue: Int,
    val openPositions: Int,
    val resultUsesEstimatedValuation: Boolean,
    val issuesWithResult: Int,
    val issuesTracked: Int
) {
    val valueIsPartial: Boolean get() = value != null && positionsWithValue < openPositions
    val resultIsPartial: Boolean get() = result != null && issuesWithResult < issuesTracked
}

data class Overview(
    val holdings: List<Holding>,
    val points: List<PortfolioPoint>,
    val receivedCoupons: BigDecimal,
    val receivedPrincipal: BigDecimal,
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
        .mapValues { (_, rows) -> rows.groupBy { it.portfolioId }.values }
    private val quotes = portfolio.quotes.groupBy { it.isin }.mapValues { (_, rows) -> rows.sortedBy { it.date } }
    private val bonds = portfolio.bonds.associateBy { it.isin }
    private val payments = portfolio.payments.filter {
        it.isScheduled && bonds[it.isin]?.currency?.isRubles() != false
    }.groupBy { it.isin }
    private val closures = payments.mapNotNull { (isin, schedule) ->
        schedule.redemptionDate(bonds[isin])?.let { isin to it }
    }.toMap()
    private val candidates by lazy {
        payments.values.flatten().filter { payment ->
            val cutoff = payment.recordDate ?: payment.date
            trades[payment.isin].orEmpty().any { rows -> rows.any { (it.settlement ?: it.date) <= cutoff } } &&
                estimate(payment).units != 0L
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
        val quantities = trades[payment.isin].orEmpty().map { rows ->
            rows.filter { (it.settlement ?: it.date) <= cutoff }
                .sumOf { if (it.side == TradeSide.BUY) it.quantity.toLong() else -it.quantity.toLong() }
        }
        if (quantities.any { it < 0 }) return PaymentEstimate(null, null)
        val quantity = quantities.sum()
        val estimate = payment.amountPerBond?.toBigDecimal()?.multiply(quantity.toBigDecimal())
        return PaymentEstimate(quantity, estimate)
    }

    fun paymentCandidates(): List<Payment> = candidates

    fun paymentStatus(payment: Payment, today: String): PaymentStatus = when {
        payment.date > today -> PaymentStatus.PLANNED
        bonds[payment.isin]?.requiresPaymentVerification(payment.date) == true -> PaymentStatus.UNVERIFIED
        else -> PaymentStatus.BY_CALENDAR
    }

    fun monthlyReceipts(today: LocalDate = marketToday()): Map<YearMonth, MonthlyReceipt> =
        receivedBy(today).groupBy { YearMonth.from(LocalDate.parse(it.first.date)) }.mapValues { (_, rows) ->
            summarizeReceipts(rows)
        }

    fun overview(today: LocalDate = marketToday()): Overview {
        val date = today.toString()
        val dailyTrades = portfolio.trades.groupBy { it.date }
        val costFlows = portfolio.trades.associate { it.id to tradeValue(it) }
        val received = receivedBy(today)
        val dailyReceived = received.groupBy { it.first.date }
        val totals = summarizeReceipts(received)
        val balance = mutableMapOf<String, Long>()
        val flows = mutableMapOf<String, BigDecimal?>()
        fun addFlow(isin: String, amount: BigDecimal?) {
            val previous = flows.getOrDefault(isin, BigDecimal.ZERO)
            flows[isin] = if (previous != null && amount != null) previous + amount else null
        }
        val series = mutableListOf<PortfolioPoint>()
        val first = portfolio.trades.minOfOrNull { LocalDate.parse(it.date) } ?: today
        var day = minOf(first, today)
        var holdings = emptyList<Holding>()
        while (day <= today) {
            val key = day.toString()
            dailyTrades[key].orEmpty().forEach { trade ->
                val delta = if (trade.side == TradeSide.BUY) trade.quantity.toLong() else -trade.quantity.toLong()
                balance[trade.isin] = balance.getOrDefault(trade.isin, 0) + delta
                addFlow(trade.isin, costFlows[trade.id])
            }
            dailyReceived[key].orEmpty().forEach { (payment, amount) ->
                addFlow(payment.isin, amount)
            }

            val active = mutableListOf<Holding>()
            var value = BigDecimal.ZERO
            var result = BigDecimal.ZERO
            var positionsWithValue = 0
            var usesEstimatedValuation = false
            var resultUsesEstimatedValuation = false
            var issuesWithResult = 0
            for ((isin, flow) in flows) {
                val count = balance.getOrDefault(isin, 0)
                val closed = closures[isin]?.let { it <= key } == true
                val current = if (count > 0 && !closed) holding(isin, count, day) else null
                current?.let(active::add)
                val holdingValue = if (current == null) BigDecimal.ZERO else current.value
                if (holdingValue == null) continue
                val estimated = current?.estimatedAccrued != null
                if (current != null) {
                    value += holdingValue
                    positionsWithValue++
                    usesEstimatedValuation = usesEstimatedValuation || estimated
                }
                if (flow != null) {
                    result += holdingValue + flow
                    issuesWithResult++
                    resultUsesEstimatedValuation = resultUsesEstimatedValuation || estimated
                }
            }
            if (portfolio.trades.isNotEmpty()) {
                series += PortfolioPoint(
                    date = day,
                    value = value.takeIf { active.isEmpty() || positionsWithValue > 0 },
                    result = result.takeIf { issuesWithResult > 0 },
                    usesEstimatedValuation = usesEstimatedValuation,
                    positionsWithValue = positionsWithValue,
                    openPositions = active.size,
                    resultUsesEstimatedValuation = resultUsesEstimatedValuation,
                    issuesWithResult = issuesWithResult,
                    issuesTracked = flows.size
                )
            }
            if (day == today) holdings = active.sortedBy { it.bond.name }
            day = day.plusDays(1)
        }
        return Overview(
            holdings,
            series,
            totals.coupons,
            totals.principal,
            totals.incomplete
        )
    }

    private fun receivedBy(today: LocalDate): List<Pair<Payment, BigDecimal?>> {
        val date = today.toString()
        return candidates.filter { it.date <= date }.map { payment ->
            val amount = if (paymentStatus(payment, date) == PaymentStatus.BY_CALENDAR) estimate(payment).amount else null
            payment to amount
        }
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
        val date = day.toString()
        val quote = quoteAt(isin, date)
        fun unavailable(issue: ValuationIssue) = Holding(bond, count, null, quote, issue)
        if (bond.currency != null && !bond.currency.isRubles()) {
            return unavailable(ValuationIssue.UnsupportedCurrency)
        }
        val schedule = payments[isin].orEmpty()
        val matured = bond.maturity?.let { it <= date } == true || schedule.any {
            it.kind == PaymentKind.REDEMPTION && it.date <= date
        }
        if (matured) return unavailable(ValuationIssue.Maturity)
        if (quote == null) return unavailable(ValuationIssue.NoQuote)
        if (ChronoUnit.DAYS.between(LocalDate.parse(quote.date), day) > MAX_QUOTE_AGE_DAYS) {
            return unavailable(ValuationIssue.OldQuote)
        }

        val crossedPayments = schedule.filter { it.date > quote.date && it.date <= date }
        val principal = crossedPayments.filter { it.kind != PaymentKind.COUPON }.maxByOrNull { it.date }
        if (principal != null) return unavailable(ValuationIssue.AfterPayment(principal.date, principal.kind))

        val nominal = nominalAt(isin, quote.date, quote)
        val currentNominal = if (quote.date == date) nominal else nominalAt(isin, date, null)
        if (nominal != null && currentNominal != null && nominal.compareTo(currentNominal) != 0) {
            return unavailable(ValuationIssue.NominalChanged)
        }

        val lastCoupon = crossedPayments.maxByOrNull { it.date }
        if (lastCoupon != null && bond.requiresPaymentVerification(lastCoupon.date)) {
            return unavailable(ValuationIssue.CouponUnverified(lastCoupon.date))
        }
        val quotedAccrued = quote.accrued?.toBigDecimal()
        val clean = quote.cleanPercent?.toBigDecimal()
        if (nominal == null || clean == null || (lastCoupon == null && quotedAccrued == null)) {
            return unavailable(ValuationIssue.MissingFields(buildList {
                if (clean == null) add(QuoteField.PRICE)
                if (nominal == null) add(QuoteField.NOMINAL)
                if (lastCoupon == null && quotedAccrued == null) add(QuoteField.ACCRUED)
            }))
        }
        val estimatedAccrued = lastCoupon?.let { couponAccruedAt(schedule, day, it.date, nominal) }
        if (lastCoupon != null && estimatedAccrued == null) {
            return unavailable(ValuationIssue.CouponAccrualUnknown(lastCoupon.date))
        }
        val accrued = estimatedAccrued ?: requireNotNull(quotedAccrued)
        val value = (clean.multiply(nominal).movePointLeft(2) + accrued) * count.toBigDecimal()
        return Holding(bond, count, value, quote, null, estimatedAccrued)
    }

    private fun couponAccruedAt(schedule: List<Payment>, day: LocalDate, start: String, nominal: BigDecimal): BigDecimal? {
        val date = day.toString()
        val next = schedule.singleOrNull { it.kind == PaymentKind.COUPON && it.periodStart == start && it.date > date }
            ?: return null
        val periodNominal = next.nominalBefore?.toBigDecimal() ?: return null
        if (nominal.compareTo(periodNominal) != 0) return null
        val coupon = next.amountPerBond?.toBigDecimal() ?: return null
        val startDate = LocalDate.parse(start)
        val daysElapsed = ChronoUnit.DAYS.between(startDate, day).toBigDecimal()
        val periodDays = ChronoUnit.DAYS.between(startDate, LocalDate.parse(next.date)).toBigDecimal()
        return coupon.multiply(daysElapsed).divide(periodDays, 2, RoundingMode.HALF_UP)
    }
}
