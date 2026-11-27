package ru.bondmobile

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.Collator
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import java.util.Locale

const val MAX_QUOTE_AGE_DAYS = 7L

@ConsistentCopyVisibility
data class Holding internal constructor(
    val bond: Bond,
    val quote: Quote?,
    internal val clean: CleanPosition,
    val accruedPerBond: BigDecimal? = null,
    val accruedIssue: ValuationIssue? = null,
    val accruedIsEstimated: Boolean = false
) {
    val quantity: Long get() = clean.quantity
    val quoteDate: String? get() = quote?.date
    val cleanValue: BigDecimal? get() = clean.cleanAmount
    val cleanIssue: ValuationIssue? get() = clean.issue
    val priceIsEstimated: Boolean get() = clean.priceIsEstimated
    val estimatedAccrued: BigDecimal? get() = accruedPerBond.takeIf { accruedIsEstimated }
    val accruedValue: BigDecimal? = accruedPerBond?.multiply(quantity.toBigDecimal())
    val value: BigDecimal? = knownValue(cleanValue, accruedValue)
    val valueIsComplete: Boolean get() = cleanValue != null && accruedValue != null
    val usesEstimatedValuation: Boolean get() = priceIsEstimated || accruedIsEstimated
}

private fun knownValue(clean: BigDecimal?, accrued: BigDecimal?): BigDecimal? =
    clean?.let { it + (accrued ?: BigDecimal.ZERO) } ?: accrued

enum class QuoteField { PRICE, NOMINAL, ACCRUED }

internal enum class PositionSort(val label: String) {
    NAME("По алфавиту"),
    FIRST_TRADE("По дате первой сделки"),
    MATURITY("По дате погашения"),
    OFFER("По дате оферты"),
    QUANTITY("По количеству облигаций"),
    PRICE("По цене, %")
}

sealed interface ValuationIssue {
    data object NoQuote : ValuationIssue
    data object OldQuote : ValuationIssue
    data object UnsupportedCurrency : ValuationIssue
    data object Maturity : ValuationIssue
    data object NominalChanged : ValuationIssue
    data class PrincipalUnverified(val date: String, val kind: PaymentKind) : ValuationIssue
    data class AccruedAfterPrincipal(val date: String) : ValuationIssue
    data class CouponUnverified(val date: String) : ValuationIssue
    data class CouponAccrualUnknown(val date: String) : ValuationIssue
    data class MissingFields(val fields: List<QuoteField>) : ValuationIssue
}

data class PortfolioPoint(
    val date: LocalDate,
    val value: BigDecimal?,
    val result: BigDecimal?,
    val usesEstimatedValuation: Boolean,
    val accruedValue: BigDecimal?,
    val positionsWithAccrued: Int,
    val openPositions: Int,
    val resultUsesEstimatedValuation: Boolean,
    val issuesWithResult: Int,
    val issuesTracked: Int,
    val cleanValue: BigDecimal?,
    val positionsWithCleanValue: Int,
    val usesEstimatedCleanPrice: Boolean
) {
    val valueIsPartial: Boolean get() = positionsWithCleanValue < openPositions || positionsWithAccrued < openPositions
    val cleanValueIsPartial: Boolean get() = cleanValue != null && positionsWithCleanValue < openPositions
    val resultIsPartial: Boolean get() = result != null && issuesWithResult < issuesTracked
}

data class Overview(
    val holdings: List<Holding>,
    val points: List<PortfolioPoint>,
    val receivedCoupons: BigDecimal,
    val receivedPrincipal: BigDecimal,
    val payoutsIncomplete: Boolean,
    val premium: PremiumValue,
    val premiumChange: PortfolioPriceChange?
)

data class PremiumValue(val amount: BigDecimal?, val valuedPositions: Int)

data class PortfolioPriceChange(
    val date: LocalDate,
    val previousDate: LocalDate,
    val issues: List<IssuePriceChange>
) {
    val comparablePositions = issues.count { it.amount != null }
    val amount = issues.mapNotNull { it.amount }.takeIf { it.isNotEmpty() }?.sumOf { it }

    internal fun sortedIssues(sort: PeriodChangeSort): List<IssuePriceChange> =
        issues.sortedByAbsoluteChange(sort.value)
}

internal enum class PeriodChangeSort(val label: String, val value: (IssuePriceChange) -> BigDecimal?) {
    PERCENTAGE_POINTS("По п.п.", IssuePriceChange::percentagePoints),
    RUBLES("По рублям", IssuePriceChange::amount)
}

private fun List<IssuePriceChange>.sortedByAbsoluteChange(value: (IssuePriceChange) -> BigDecimal?): List<IssuePriceChange> =
    sortedWith(compareBy<IssuePriceChange, BigDecimal?>(nullsLast(reverseOrder())) { value(it)?.abs() }
        .thenBy { it.bond.name }.thenBy { it.bond.isin })

data class IssuePriceChange(
    val bond: Bond,
    val previousPrice: BigDecimal?,
    val price: BigDecimal?,
    val percent: BigDecimal?,
    val percentagePoints: BigDecimal?,
    val previousNominal: BigDecimal?,
    val nominal: BigDecimal?,
    val previousQuantity: Long,
    val quantity: Long,
    val amount: BigDecimal?,
    val principalEvents: List<Payment>
)

internal data class CleanPosition(
    val price: BigDecimal?, val nominal: BigDecimal?, val quantity: Long, val quoteDate: String?,
    val issue: ValuationIssue?, val priceIsEstimated: Boolean = false
) {
    val cleanAmount: BigDecimal? =
        if (quantity == 0L) BigDecimal.ZERO else if (price == null || nominal == null) null
        else price.movePointLeft(2) * nominal * quantity.toBigDecimal()

    val premiumAmount: BigDecimal?
        get() = if (quantity == 0L) BigDecimal.ZERO else
            cleanAmount?.let { it - requireNotNull(nominal) * quantity.toBigDecimal() }
}

internal data class ResultExclusion(
    val bond: Bond,
    val holding: Holding?,
    val incompleteTrades: List<Trade>,
    val incompletePayments: List<Payment>
)

private data class ValuationSnapshot(val date: LocalDate, val holdings: List<Holding>)

private val Payment.affectsNominal: Boolean
    get() = kind == PaymentKind.AMORTIZATION || kind == PaymentKind.PRINCIPAL

data class PaymentEstimate(val units: Long?, val amount: BigDecimal?)

internal data class QuoteChange(val previousDate: String, val percentagePoints: BigDecimal)

internal enum class IssuePaymentPeriod(val label: String) {
    PLANNED("Планируемые"), PAST("Прошедшие")
}

internal enum class IssuePaymentType(val label: String) {
    ALL("Все"), COUPONS("Купоны"), PRINCIPAL("Амортизация и погашение");

    fun includes(kind: PaymentKind): Boolean = when (this) {
        ALL -> true
        COUPONS -> kind == PaymentKind.COUPON
        PRINCIPAL -> kind != PaymentKind.COUPON
    }
}

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
    private val firstTradeDates by lazy {
        trades.mapValues { (_, accounts) -> accounts.minOf { account -> account.minOf(Trade::date) } }
    }
    private val quotes = portfolio.quotes.groupBy { it.isin }.mapValues { (_, rows) -> rows.sortedBy { it.date } }
    private val nominalQuotes by lazy { quotes.mapValues { (_, rows) -> rows.filter { it.nominal != null } } }
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

    fun firstTradeDate(isin: String): String? = firstTradeDates[isin]

    internal fun priceQuotes(isin: String): List<Quote> =
        quotes[isin].orEmpty().filter { it.cleanPercent != null }

    internal fun quoteChange(quote: Quote): QuoteChange? {
        val price = quote.cleanPercent?.toBigDecimal() ?: return null
        val previous = quotes[quote.isin].orEmpty().lastOrNull { it.date < quote.date && it.cleanPercent != null }
            ?: return null
        return QuoteChange(previous.date, price - requireNotNull(previous.cleanPercent).toBigDecimal())
    }

    internal fun issuePayments(isin: String, today: String, period: IssuePaymentPeriod, type: IssuePaymentType): List<Payment> {
        val payments = candidates.filter { payment ->
            payment.isin == isin && type.includes(payment.kind) && when (period) {
                IssuePaymentPeriod.PLANNED -> payment.date > today
                IssuePaymentPeriod.PAST -> payment.date <= today
            }
        }
        return when (period) {
            IssuePaymentPeriod.PLANNED -> payments.sortedBy { it.date }
            IssuePaymentPeriod.PAST -> payments.sortedByDescending { it.date }
        }
    }

    fun maturityDate(bond: Bond): String? = bond.maturity ?: payments[bond.isin]?.redemptionDate()

    internal fun sortedHoldings(
        holdings: List<Holding>, sort: PositionSort, ascending: Boolean, today: String
    ): List<Holding> {
        val collator = Collator.getInstance(Locale.forLanguageTag("ru-RU"))
        val byName = compareBy<Holding> { collator.getCollationKey(it.bond.name) }.thenBy { it.bond.isin }
        val dates = nullsLast(if (ascending) naturalOrder<String>() else reverseOrder())
        val ordering = when (sort) {
            PositionSort.NAME -> if (ascending) byName else byName.reversed()
            PositionSort.FIRST_TRADE -> compareBy<Holding, String?>(dates) { firstTradeDate(it.bond.isin) }
            PositionSort.MATURITY -> compareBy<Holding, String?>(dates) { maturityDate(it.bond) }
            PositionSort.OFFER -> compareBy<Holding, String?>(dates) { it.bond.upcomingOfferDate(today) }
            PositionSort.QUANTITY -> if (ascending) compareBy(Holding::quantity) else compareByDescending(Holding::quantity)
            PositionSort.PRICE -> {
                val prices = nullsLast(if (ascending) naturalOrder<BigDecimal>() else reverseOrder())
                compareBy<Holding, BigDecimal?>(prices) { it.quote?.cleanPercent?.toBigDecimalOrNull() }
            }
        }
        return holdings.sortedWith(ordering.then(byName))
    }

    fun remainingNominal(bond: Bond, date: String): BigDecimal? {
        if (bond.currency?.isRubles() == false) return null
        if (closures[bond.isin]?.let { it <= date } == true) return BigDecimal.ZERO
        val quote = nominalQuotes[bond.isin]?.latestAt(date)
        val snapshotDate = bond.nominalDate
        val snapshot = bond.nominal
        val (baseDate, baseNominal) = when {
            snapshot != null && snapshotDate != null && snapshotDate <= date &&
                (quote == null || snapshotDate >= quote.date) -> snapshotDate to snapshot
            quote != null -> quote.date to requireNotNull(quote.nominal)
            else -> return nominalAt(bond.isin, date, null)
        }
        var amount = baseNominal.toBigDecimal()
        for (payment in payments[bond.isin].orEmpty()) {
            if (payment.affectsNominal && payment.date > baseDate && payment.date <= date &&
                !bond.requiresPaymentVerification(payment.date)) {
                amount -= payment.amountPerBond?.toBigDecimal() ?: return null
            }
        }
        return amount.takeIf { it.signum() >= 0 }
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

    internal fun holdingsAt(day: LocalDate): List<Holding> = holdingsAt(day, tradesThrough(day.toString()))

    internal fun cleanValueChange(start: LocalDate, end: LocalDate): PortfolioPriceChange =
        priceChange(ValuationSnapshot(start, holdingsAt(start)), ValuationSnapshot(end, holdingsAt(end)), subtractNominal = false)

    internal fun resultExclusions(day: LocalDate, currentHoldings: List<Holding>? = null): List<ResultExclusion> {
        val date = day.toString()
        val history = tradesThrough(date)
        val unavailable = (currentHoldings ?: holdingsAt(day, history))
            .filterNot { it.valueIsComplete }.associateBy { it.bond.isin }
        val incompletePayments = receivedBy(day).filter { (_, amount) -> amount == null }
            .map { it.first }.groupBy { it.isin }
        return history.mapNotNull { (isin, rows) ->
            val issue = unavailable[isin]
            val tradeGaps = rows.filter { tradeValue(it) == null }
            val paymentGaps = incompletePayments[isin].orEmpty()
            if (issue == null && tradeGaps.isEmpty() && paymentGaps.isEmpty()) null
            else ResultExclusion(bondFor(isin), issue, tradeGaps, paymentGaps)
        }.sortedBy { it.bond.name }
    }

    private fun tradesThrough(date: String): Map<String, List<Trade>> =
        portfolio.trades.filter { it.date <= date }.groupBy { it.isin }

    private fun holdingsAt(day: LocalDate, history: Map<String, List<Trade>>): List<Holding> {
        val date = day.toString()
        return history.mapNotNull { (isin, rows) -> openHolding(isin, rows.sumOf { it.signedQuantity() }, day, date) }
            .sortedBy { it.bond.name }
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
        val firstDate = first.toString()
        val sessionDates = portfolio.quotes.asSequence()
            .filter { it.isin in trades && it.cleanPercent != null && it.date in firstDate..date }
            .map { it.date }.distinct().sortedDescending().take(2).toSet()
        val sessions = mutableListOf<ValuationSnapshot>()
        var day = minOf(first, today)
        var holdings = emptyList<Holding>()
        while (day <= today) {
            val key = day.toString()
            dailyTrades[key].orEmpty().forEach { trade ->
                balance[trade.isin] = balance.getOrDefault(trade.isin, 0) + trade.signedQuantity()
                addFlow(trade.isin, costFlows[trade.id])
            }
            dailyReceived[key].orEmpty().forEach { (payment, amount) ->
                addFlow(payment.isin, amount)
            }

            val active = mutableListOf<Holding>()
            var cleanValue = BigDecimal.ZERO
            var accruedValue = BigDecimal.ZERO
            var result = BigDecimal.ZERO
            var positionsWithCleanValue = 0
            var positionsWithAccrued = 0
            var usesEstimatedCleanPrice = false
            var usesEstimatedValuation = false
            var resultUsesEstimatedValuation = false
            var issuesWithResult = 0
            for ((isin, flow) in flows) {
                val count = balance.getOrDefault(isin, 0)
                val current = openHolding(isin, count, day, key)
                if (current != null) {
                    active += current
                    current.cleanValue?.let {
                        cleanValue += it
                        positionsWithCleanValue++
                    }
                    current.accruedValue?.let {
                        accruedValue += it
                        positionsWithAccrued++
                    }
                    usesEstimatedCleanPrice = usesEstimatedCleanPrice || current.priceIsEstimated
                    usesEstimatedValuation = usesEstimatedValuation || current.usesEstimatedValuation
                }
                if (flow != null) {
                    val holdingValue = if (current == null) BigDecimal.ZERO
                        else current.value.takeIf { current.valueIsComplete } ?: continue
                    result += holdingValue + flow
                    issuesWithResult++
                    resultUsesEstimatedValuation = resultUsesEstimatedValuation || current?.usesEstimatedValuation == true
                }
            }
            if (portfolio.trades.isNotEmpty()) {
                val knownCleanValue = cleanValue.takeIf { active.isEmpty() || positionsWithCleanValue > 0 }
                val knownAccruedValue = accruedValue.takeIf { active.isEmpty() || positionsWithAccrued > 0 }
                series += PortfolioPoint(
                    date = day,
                    value = knownValue(knownCleanValue, knownAccruedValue),
                    result = result.takeIf { issuesWithResult > 0 },
                    usesEstimatedValuation = usesEstimatedValuation,
                    accruedValue = knownAccruedValue,
                    positionsWithAccrued = positionsWithAccrued,
                    openPositions = active.size,
                    resultUsesEstimatedValuation = resultUsesEstimatedValuation,
                    issuesWithResult = issuesWithResult,
                    issuesTracked = flows.size,
                    cleanValue = knownCleanValue,
                    positionsWithCleanValue = positionsWithCleanValue,
                    usesEstimatedCleanPrice = usesEstimatedCleanPrice
                )
            }
            if (key in sessionDates) sessions += ValuationSnapshot(day, active)
            if (day == today) holdings = active.sortedBy { it.bond.name }
            day = day.plusDays(1)
        }
        return Overview(
            holdings,
            series,
            totals.coupons,
            totals.principal,
            totals.incomplete,
            premiumValue(holdings),
            if (sessions.size == 2 && ChronoUnit.DAYS.between(sessions.last().date, today) <= MAX_QUOTE_AGE_DAYS)
                priceChange(sessions.first(), sessions.last(), subtractNominal = true).takeIf { it.issues.isNotEmpty() }
            else null
        )
    }

    private fun premiumValue(holdings: List<Holding>): PremiumValue {
        val values = holdings.mapNotNull { it.clean.premiumAmount }
        val amount = values.sumOf { it }.takeIf { values.isNotEmpty() || holdings.isEmpty() }
        return PremiumValue(amount, values.size)
    }

    private fun priceChange(previous: ValuationSnapshot, current: ValuationSnapshot, subtractNominal: Boolean): PortfolioPriceChange {
        val before = previous.holdings.associateBy { it.bond.isin }
        val after = current.holdings.associateBy { it.bond.isin }
        val start = previous.date.toString()
        val end = current.date.toString()
        val issues = (before.keys + after.keys).map { isin ->
            val bond = bondFor(isin)
            val old = before[isin]?.clean ?: holding(isin, 0, previous.date).clean
            val new = after[isin]?.clean ?: holding(isin, 0, current.date).clean
            val percentagePoints = old.price?.takeIf { old.quoteDate != new.quoteDate }?.let { base ->
                new.price?.minus(base)
            }
            val percent = old.price?.takeIf { it.signum() > 0 }?.let { base ->
                percentagePoints?.movePointRight(2)?.divide(base, 12, RoundingMode.HALF_UP)
            }
            val oldAmount = if (subtractNominal) old.premiumAmount else old.cleanAmount
            val newAmount = if (subtractNominal) new.premiumAmount else new.cleanAmount
            val amount = if (oldAmount != null && newAmount != null && !old.priceIsEstimated && !new.priceIsEstimated)
                newAmount - oldAmount else null
            val principal = payments[isin].orEmpty().filter {
                it.affectsNominal && it.date > start && it.date <= end
            }.sortedBy { it.date }
            IssuePriceChange(bond, old.price, new.price, percent, percentagePoints, old.nominal, new.nominal,
                old.quantity, new.quantity, amount, principal)
        }
        return PortfolioPriceChange(current.date, previous.date,
            if (subtractNominal) issues.sortedByAbsoluteChange(IssuePriceChange::percent) else issues)
    }

    private fun cleanPosition(
        bond: Bond, quantity: Long, day: LocalDate, quote: Quote?, crossedPrincipal: List<Payment>
    ): CleanPosition {
        val date = day.toString()
        val nominal = remainingNominal(bond, date)
        fun unavailable(issue: ValuationIssue) = CleanPosition(null, nominal, quantity, quote?.date, issue)
        if (bond.currency?.isRubles() == false) return unavailable(ValuationIssue.UnsupportedCurrency)
        if (bond.maturity?.let { it <= date } == true ||
            payments[bond.isin].orEmpty().any { it.kind == PaymentKind.REDEMPTION && it.date <= date }) {
            return unavailable(ValuationIssue.Maturity)
        }
        if (quote == null) return unavailable(ValuationIssue.NoQuote)
        if (ChronoUnit.DAYS.between(LocalDate.parse(quote.date), day) > MAX_QUOTE_AGE_DAYS) {
            return unavailable(ValuationIssue.OldQuote)
        }
        crossedPrincipal.filter { bond.requiresPaymentVerification(it.date) }.maxByOrNull { it.date }?.let {
            return unavailable(ValuationIssue.PrincipalUnverified(it.date, it.kind))
        }
        if (crossedPrincipal.isEmpty() && quote.nominal != null && nominal != null &&
            quote.nominal.toBigDecimal().compareTo(nominal) != 0) {
            return unavailable(ValuationIssue.NominalChanged)
        }
        val price = quote.cleanPercent?.toBigDecimal()
        val issue = if (price == null || nominal == null) {
            ValuationIssue.MissingFields(buildList {
                if (price == null) add(QuoteField.PRICE)
                if (nominal == null) add(QuoteField.NOMINAL)
            })
        } else null
        return CleanPosition(price, nominal, quantity, quote.date, issue,
            priceIsEstimated = issue == null && crossedPrincipal.isNotEmpty())
    }

    private fun Trade.signedQuantity(): Long = if (side == TradeSide.BUY) quantity.toLong() else -quantity.toLong()

    private fun openHolding(isin: String, count: Long, day: LocalDate, date: String): Holding? =
        if (count > 0 && closures[isin]?.let { it <= date } != true) holding(isin, count, day) else null

    private fun bondFor(isin: String): Bond = bonds[isin] ?: Bond(isin, isin, isin)

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

    private fun quoteAt(isin: String, date: String): Quote? = quotes[isin]?.latestAt(date)

    private fun List<Quote>.latestAt(date: String): Quote? {
        val index = binarySearchBy(date) { it.date }
        return getOrNull(if (index >= 0) index else -index - 2)
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
        val bond = bondFor(isin)
        val date = day.toString()
        val quote = quoteAt(isin, date)
        val schedule = payments[isin].orEmpty()
        val crossedPayments = if (quote == null) emptyList() else schedule.filter { it.date > quote.date && it.date <= date }
        val crossedPrincipal = crossedPayments.filter { it.affectsNominal }
        val clean = cleanPosition(bond, count, day, quote, crossedPrincipal)
        val base = Holding(bond, quote, clean)
        if (quote == null || (clean.issue != null && clean.issue !is ValuationIssue.MissingFields)) return base

        val lastCoupon = crossedPayments.filter { it.kind == PaymentKind.COUPON }.maxByOrNull { it.date }
        if (lastCoupon != null && bond.requiresPaymentVerification(lastCoupon.date)) {
            return base.copy(accruedIssue = ValuationIssue.CouponUnverified(lastCoupon.date))
        }
        val lastPrincipal = crossedPrincipal.maxByOrNull { it.date }
        if (lastPrincipal != null && (lastCoupon == null || lastCoupon.date < lastPrincipal.date)) {
            return base.copy(accruedIssue = ValuationIssue.AccruedAfterPrincipal(lastPrincipal.date))
        }
        if (lastCoupon != null) {
            val estimatedAccrued = clean.nominal?.let { couponAccruedAt(schedule, day, lastCoupon.date, it) }
                ?: return base.copy(accruedIssue = ValuationIssue.CouponAccrualUnknown(lastCoupon.date))
            return base.copy(accruedPerBond = estimatedAccrued, accruedIsEstimated = true)
        }
        val quotedAccrued = quote.accrued?.toBigDecimal()
            ?: return base.copy(accruedIssue = ValuationIssue.MissingFields(listOf(QuoteField.ACCRUED)))
        return base.copy(accruedPerBond = quotedAccrued)
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
