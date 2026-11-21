package ru.bondmobile

import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth

class PortfolioTest {
    private val isin = "RU000A0JXQ85"
    private val bond = Bond(isin, isin, "Облигация А", "TQCB", currency = "RUB")
    private fun day(value: String) = LocalDate.parse(value)

    @Test
    fun calculatesCostAndResultWithPartialSaleAndScheduledPayments() {
        val buy = Trade("buy", isin, TradeSide.BUY, 10, "2025-05-05", null, PriceUnit.PERCENT,
            "98", "1000", "8", "5")
        val sell = Trade("sell", isin, TradeSide.SELL, 3, "2025-05-06", null, PriceUnit.RUB_WITH_ACCRUED,
            "1000", null, null, "3")
        val coupon = Payment("coupon", isin, PaymentKind.COUPON, "2025-05-07", "2025-05-06", "20")
        val amortization = Payment("amort", isin, PaymentKind.AMORTIZATION, "2025-05-09", "2025-05-08", "100")
        val quotes = listOf(Quote(isin, "2025-05-06", "97", "1000", "12"),
            Quote(isin, "2025-05-09", "100", "900", "0"))
        val math = PortfolioMath(Portfolio(listOf(bond), listOf(buy, sell), listOf(coupon, amortization), quotes))
        val before = math.overview(day("2025-05-06"))
        assertEquals(7L, before.holdings.single().quantity)
        assertMoney("6874", before.points.last().value)
        assertMoney("-14", before.points.last().result)
        val after = math.overview(day("2025-05-09"))
        assertMoney("6300", after.points.last().value)
        assertMoney("252", after.points.last().result)
        assertMoney("140", after.receivedCoupons)
        assertMoney("700", after.receivedPrincipal)
    }

    @Test
    fun determinesCouponEligibilityBySettlementAndKeepsUnknownAmounts() {
        val oldBuy = Trade("old", isin, TradeSide.BUY, 8, "2025-03-02", "2025-03-03", PriceUnit.RUB_WITH_ACCRUED,
            "1000", null, null, "0")
        val laterBuy = oldBuy.copy(id = "later", quantity = 3, date = "2025-03-07", settlement = "2025-03-11")
        val sale = laterBuy.copy(id = "sale", side = TradeSide.SELL, quantity = 2, settlement = "2025-03-10")
        val coupon = Payment("coupon", isin, PaymentKind.COUPON, "2025-03-11", "2025-03-10", "20")
        val math = PortfolioMath(Portfolio(listOf(bond), listOf(oldBuy, laterBuy, sale), listOf(coupon)))
        assertMoney("120", math.estimate(coupon).amount)
        assertEquals(6L, math.estimate(coupon.copy(amountPerBond = null)).units)
        assertNull(math.estimate(coupon.copy(amountPerBond = null)).amount)
        assertEquals(9L, math.estimate(coupon.copy(recordDate = null)).units)
        val withoutSettlement = PortfolioMath(Portfolio(listOf(bond), listOf(oldBuy, laterBuy.copy(settlement = null))))
        assertEquals(11L, withoutSettlement.estimate(coupon).units)
    }

    @Test
    fun appliesRedemptionOnCalendarDateAndPreventsStaleValuationAcrossAmortization() {
        val buy = Trade("buy", isin, TradeSide.BUY, 2, "2025-05-02", null, PriceUnit.RUB_WITH_ACCRUED,
            "1000", null, null, "0")
        val quote = Quote(isin, "2025-05-05", "99", "1000", "10")
        val amortization = Payment("amort", isin, PaymentKind.AMORTIZATION, "2025-05-06", null, "100")
        val scheduled = Payment("redeem", isin, PaymentKind.REDEMPTION, "2025-05-07", null, "900")
        val base = Portfolio(listOf(bond), listOf(buy), listOf(amortization, scheduled), listOf(quote))
        val math = PortfolioMath(base)
        val before = math.overview(day("2025-05-06"))
        assertNull(before.points.last().value)
        assertEquals(2L, before.holdings.single().quantity)
        assertMoney("200", before.receivedPrincipal)
        val updated = math.overview(day("2025-05-07"))
        assertTrue(updated.holdings.isEmpty())
        assertMoney("2000", updated.receivedPrincipal)
        assertMoney("0", updated.points.last().result)
        assertEquals("2025-05-07", base.payments.redemptionDate())

        val incomplete = PortfolioMath(base.copy(payments = listOf(amortization, scheduled.copy(amountPerBond = null))))
            .overview(day("2025-05-07"))
        assertTrue(incomplete.holdings.isEmpty())
        assertTrue(incomplete.payoutsIncomplete)
        assertNull(incomplete.points.last().result)
    }

    @Test
    fun keepsUnverifiedCouponAndRedemptionOutOfReceiptsAndLeavesTheHoldingOpen() {
        val defaulted = bond.copy(defaultStatus = BondDefaultStatus.DEFAULT, maturity = "2026-11-21")
        val regular = bond.copy(isin = "RU000A10DZK8")
        val buy = Trade("buy", isin, TradeSide.BUY, 64, "2025-07-24", null, PriceUnit.RUB_WITH_ACCRUED,
            "1000", null, null, "0")
        val coupon = Payment("coupon", isin, PaymentKind.COUPON, "2026-11-16", "2026-11-13", "20.34")
        val redemption = Payment("redemption", isin, PaymentKind.REDEMPTION, "2026-11-21", null, "1000")
        val ordinaryCoupon = Payment("other", regular.isin, PaymentKind.COUPON, "2026-11-21", null, "10")
        val data = Portfolio(listOf(defaulted, regular),
            listOf(buy, buy.copy(id = "other", isin = regular.isin, quantity = 1)),
            listOf(coupon, redemption, ordinaryCoupon),
            listOf(Quote(regular.isin, "2026-11-21", "100", "1000", "0")))
        val math = PortfolioMath(data)
        val today = day("2026-11-21")
        val overview = math.overview(today)
        assertEquals(PaymentStatus.PLANNED, math.paymentStatus(redemption, "2026-11-20"))
        assertEquals(PaymentStatus.UNVERIFIED, math.paymentStatus(redemption, "2026-11-21"))
        assertEquals(PaymentStatus.UNVERIFIED, math.paymentStatus(coupon, "2026-11-21"))
        assertEquals(PaymentStatus.BY_CALENDAR, math.paymentStatus(ordinaryCoupon, "2026-11-21"))
        assertMoney("1301.76", math.estimate(coupon).amount)
        assertMoney("64000", math.estimate(redemption).amount)
        assertEquals(64L, overview.holdings.single { it.bond.isin == isin }.quantity)
        assertMoney("10", overview.receivedCoupons)
        assertMoney("0", overview.receivedPrincipal)
        assertTrue(overview.payoutsIncomplete)
        assertMoney("10", overview.points.last().result)
        assertTrue(overview.points.last().resultIsPartial)
        val month = math.monthlyReceipts(today).getValue(YearMonth.of(2026, 11))
        assertMoney("10", month.total)
        assertTrue(month.incomplete)
    }

    @Test
    fun preservesMoexDefaultStatusUntilExplicitlyClearedAndKeepsEarlierPaymentsUnverified() {
        val moex = Moex()
        val today = day("2026-11-21")
        val technical = moex.readDefaultStatus(bond, "0", "1", today)
        assertEquals(BondDefaultStatus.TECHNICAL_DEFAULT, technical.defaultStatus)
        val flagged = moex.readDefaultStatus(technical, "1", "1", today)
        assertEquals(BondDefaultStatus.DEFAULT, flagged.defaultStatus)
        assertEquals(flagged, moex.readDefaultStatus(flagged, null, "1", today))
        assertEquals(flagged, moex.readDefaultStatus(flagged, "0", null, today))
        val cleared = moex.readDefaultStatus(flagged, "0", "0", today)
        assertNull(cleared.defaultStatus)
        assertTrue(cleared.requiresPaymentVerification("2026-11-21"))
        assertFalse(cleared.requiresPaymentVerification("2026-11-22"))

        val portfolio = Portfolio(listOf(flagged))
        val bytes = ByteArrayOutputStream().apply { Backup.write(portfolio, this) }.toByteArray()
        assertEquals(portfolio, Backup.read(ByteArrayInputStream(bytes), today))
    }

    @Test
    fun decodesIssTablesAndKeepsCouponSeparateFromRedemptionOnSameDay() {
        val raw = JSONTokener("""[
          {"charsetinfo":{"name":"utf-8"}},
          {"coupons":[{"isin":"RU000A0JXQ85","coupondate":"2017-10-27","recorddate":"2017-10-26",
            "startdate":"2017-04-28","initialfacevalue":1000,"facevalue":1000,"faceunit":"RUB",
            "value":48.12,"valueprc":9.65,"value_rub":48.12},
            {"coupondate":"2018-04-27","startdate":"2017-10-27","faceunit":"RUB","value":null}]}
        ]""").nextValue()
        val principal = JSONObject("""{"amortizations":{"columns":["amortdate","recorddate","value_rub","faceunit"],
            "data":[["2017-10-27","2017-10-26",100,"RUB"],["2018-04-27","2018-04-26",900,"RUB"]]}}""")
        val entries = Moex().decodeCalendar(bond.copy(maturity = "2018-04-27"), Moex.table(raw, "coupons"),
            Moex.table(principal, "amortizations"))
        assertEquals(PaymentKind.COUPON, entries[0].kind)
        assertEquals("48.12", entries[0].amountPerBond)
        assertEquals("2017-10-26", entries[0].recordDate)
        assertNull(entries[1].amountPerBond)
        assertEquals(PaymentKind.AMORTIZATION, entries[2].kind)
        assertEquals(PaymentKind.REDEMPTION, entries[3].kind)
        assertEquals(4, entries.distinctBy { it.id }.size)
    }

    @Test
    fun usesExecutedPlacementTradesAfterTheOrderCollectionPeriod() {
        val placementIsin = "RU000A10GDW3"
        val raw = JSONObject("""{
          "description":{"columns":["name","value"],"data":[["ISIN","$placementIsin"],["STARTDATEMOEX","2026-11-11"]]},
          "boards":{"columns":["secid","boardid","engine","market","title","history_from","history_till","listed_from"],"data":[
            ["$placementIsin","TQCB","stock","bonds","Т+: Облигации - безадрес.","2026-11-11","2026-11-20","2026-11-11"],
            ["$placementIsin","PSAU","stock","ndm","Размещение - адрес.","2026-10-27","2026-11-11","2026-10-27"],
            ["$placementIsin","PACT","stock","ndm","Размещение: Аукцион",null,null,"2026-10-27"]
          ]}
        }""")
        val history = JSONObject("""{"history":{"columns":["SECID","BOARDID","TRADEDATE","NUMTRADES"],"data":[
            ["$placementIsin","PSAU","2026-10-27",0],
            ["$placementIsin","PSAU","2026-11-10",0],
            ["$placementIsin","PSAU","2026-11-11",16155]
        ]}}""")
        val moex = Moex()
        val board = moex.decodePlacementBoards(raw, placementIsin).single()
        assertEquals(PlacementBoard(placementIsin, "PSAU", "ndm", "2026-10-27", "2026-11-11"), board)
        val rows = Moex.table(history, "history")
        val placementDate = moex.decodePlacementTradeDate(rows, board)
        assertEquals(day("2026-11-11"), placementDate)
        assertNull(moex.decodePlacementTradeDate(rows.dropLast(1), board))
        assertNull(moex.decodePlacementTradeDate(rows, board.copy(id = "TQCB")))
        assertTrue(moex.decodePlacementBoards(raw, isin).isEmpty())
        raw.remove("boards")
        assertTrue(moex.decodePlacementBoards(raw, placementIsin).isEmpty())

        val purchase = Trade("placed", placementIsin, TradeSide.BUY, 74, "2026-10-27", null,
            PriceUnit.PERCENT, "100", null, null, "0")
        val quote = Quote(placementIsin, "2026-11-11", "100.09", "1000", "0")
        val math = PortfolioMath(Portfolio(listOf(Bond(placementIsin, placementIsin, "СБСекр7АС")),
            listOf(purchase), quotes = listOf(quote)))
        assertNull(math.tradeValue(purchase))
        assertMoney("-74000", math.tradeValue(purchase.copy(date = requireNotNull(placementDate).toString())))
    }

    @Test
    fun preservesLegacyReceiptDataInBackupsAndRejectsMissingIssue() {
        val trade = Trade("buy", isin, TradeSide.BUY, 2, "2025-05-02", null, PriceUnit.RUB_WITH_ACCRUED,
            "1000.63", null, null, "0")
        val actual = Payment("coupon", isin, PaymentKind.COUPON, "2025-05-04", null, null,
            receivedDate = "2025-05-05", receivedAmount = "103.86")
        val portfolio = Portfolio(listOf(bond), listOf(trade), listOf(actual), listOf(Quote(isin, "2025-05-02", "100", "1000", "0")))
        val bytes = ByteArrayOutputStream().apply { Backup.write(portfolio, this) }.toByteArray()
        assertEquals(portfolio, Backup.read(ByteArrayInputStream(bytes), day("2025-05-09")))
        val invalid = ByteArrayOutputStream().apply { Backup.write(portfolio.copy(bonds = emptyList()), this) }.toByteArray()
        val result = runCatching { Backup.read(ByteArrayInputStream(invalid), day("2025-05-09")) }
        assertTrue(result.isFailure)
    }

    @Test
    fun aggregatesByCalendarMonthAndIgnoresLegacyConfirmationDatesAndAmounts() {
        val buy = Trade("buy", isin, TradeSide.BUY, 2, "2025-11-30", null, PriceUnit.RUB_WITH_ACCRUED,
            "1000", null, null, "0")
        val coupon = Payment("coupon", isin, PaymentKind.COUPON, "2025-12-30", "2025-12-29", "20",
            receivedDate = "2026-01-02", receivedAmount = "137.51")
        val future = Payment("future", isin, PaymentKind.COUPON, "2026-01-15", null, "25")
        val unknown = Payment("unknown", isin, PaymentKind.COUPON, "2026-01-06", null, null)
        val amortization = Payment("amort", isin, PaymentKind.AMORTIZATION, "2026-01-07", null, "100")
        val redemption = Payment("final", isin, PaymentKind.REDEMPTION, "2026-02-02", null, "900")
        val manual = coupon.copy(id = "manual", source = "MANUAL", date = "2026-01-02")
        val schedule = listOf(coupon, future, unknown, amortization, redemption, manual)
        val math = PortfolioMath(Portfolio(listOf(bond), listOf(buy), schedule))
        val december = YearMonth.of(2025, 12)
        val january = YearMonth.of(2026, 1)
        val february = YearMonth.of(2026, 2)
        assertTrue(math.monthlyReceipts(day("2025-12-29")).isEmpty())
        val asOfJanuary = math.monthlyReceipts(day("2026-01-07"))
        assertEquals(setOf(december, january), asOfJanuary.keys)
        assertMoney("40", asOfJanuary.getValue(december).coupons)
        assertFalse(asOfJanuary.getValue(december).incomplete)
        assertMoney("200", asOfJanuary.getValue(january).principal)
        assertMoney("200", asOfJanuary.getValue(january).total)
        assertTrue(asOfJanuary.getValue(january).incomplete)
        assertMoney("1800", math.monthlyReceipts(day("2026-02-02")).getValue(february).principal)
        assertFalse(math.paymentCandidates().any { it.id == manual.id })
    }

    @Test
    fun scopesChartsAndTotalsAndCombinesTheSameIssueWithoutDuplicatingThePayment() {
        val reserve = NamedPortfolio("reserve", "Резерв")
        val buy = Trade("a-buy", isin, TradeSide.BUY, 5, "2025-06-02", null, PriceUnit.RUB_WITH_ACCRUED,
            "900", null, null, "0")
        val sell = buy.copy(id = "a-sell", side = TradeSide.SELL, quantity = 2, date = "2025-06-05", price = "1000")
        val secondBuy = buy.copy(id = "b-buy", quantity = 7, date = "2025-06-03", price = "960", portfolioId = reserve.id)
        val coupon = Payment("coupon", isin, PaymentKind.COUPON, "2025-06-06", "2025-06-04", "20")
        val data = Portfolio(listOf(bond), listOf(buy, sell, secondBuy), listOf(coupon),
            listOf(Quote(isin, "2025-06-06", "100", "1000", "0")), listOf(defaultPortfolio, reserve))
        val today = day("2025-06-06")
        val first = PortfolioMath(data.scoped(DEFAULT_PORTFOLIO_ID))
        val second = PortfolioMath(data.scoped(reserve.id))
        val all = PortfolioMath(data.scoped(null))
        assertEquals(3L, first.overview(today).holdings.single().quantity)
        assertEquals(7L, second.overview(today).holdings.single().quantity)
        assertEquals(10L, all.overview(today).holdings.single().quantity)
        assertMoney("3000", first.overview(today).points.last().value)
        assertMoney("7000", second.overview(today).points.last().value)
        assertMoney("10000", all.overview(today).points.last().value)
        assertMoney("600", first.overview(today).points.last().result)
        assertMoney("420", second.overview(today).points.last().result)
        assertMoney("1020", all.overview(today).points.last().result)
        assertMoney("100", first.monthlyReceipts(today).values.single().coupons)
        assertMoney("140", second.monthlyReceipts(today).values.single().coupons)
        assertMoney("240", all.monthlyReceipts(today).values.single().coupons)
        assertEquals(1, all.paymentCandidates().size)
        assertTrue(data.scoped("empty").trades.isEmpty())
    }

    @Test
    fun anotherPortfolioCannotCoverAnUnmatchedSale() {
        val buy = Trade("buy", isin, TradeSide.BUY, 10, "2025-05-02", null, PriceUnit.RUB_WITH_ACCRUED,
            "1000", null, null, "0")
        val sale = buy.copy(id = "sale", side = TradeSide.SELL, quantity = 3, date = "2025-05-05", portfolioId = "other")
        assertFalse(validQuantityHistory(listOf(buy, sale)))
        assertTrue(validQuantityHistory(listOf(buy.copy(portfolioId = "other"), sale)))
    }

    @Test
    fun backupPreservesNamedPortfoliosAndRestoresLegacyTradesToDefault() {
        val reserve = NamedPortfolio("reserve", "Резерв")
        val buy = Trade("buy", isin, TradeSide.BUY, 2, "2025-05-02", null, PriceUnit.RUB_WITH_ACCRUED,
            "1000.63", null, null, "0", reserve.id)
        val portfolio = Portfolio(listOf(bond), listOf(buy), accounts = listOf(defaultPortfolio, reserve))
        val json = ByteArrayOutputStream().apply { Backup.write(portfolio, this) }.toByteArray()
        assertEquals(portfolio, Backup.read(ByteArrayInputStream(json), day("2025-05-09")))
        val legacy = JSONObject(String(json, Charsets.UTF_8)).apply {
            put("version", 1)
            remove("portfolios")
            getJSONArray("trades").getJSONObject(0).remove("portfolioId")
        }
        val restored = Backup.read(ByteArrayInputStream(legacy.toString().toByteArray()), day("2025-05-09"))
        assertEquals(listOf(defaultPortfolio), restored.accounts)
        assertEquals(DEFAULT_PORTFOLIO_ID, restored.trades.single().portfolioId)
    }

    @Test
    fun explainsMissingValuationsWithoutChangingThePartialTotal() {
        val other = bond.copy(isin = "RU000A10GDW3", secid = "RU000A10GDW3", name = "Другая облигация")
        val buy = Trade("buy", isin, TradeSide.BUY, 2, "2026-11-02", null, PriceUnit.RUB_WITH_ACCRUED,
            "1000", null, null, "0")
        val quotes = listOf(Quote(isin, "2026-11-20", "100", "1000", "0"),
            Quote(other.isin, "2026-11-20", "100", "1000", "0"))
        val coupon = Payment("coupon", other.isin, PaymentKind.COUPON, "2026-11-21", null, "10")
        val data = Portfolio(listOf(bond, other), listOf(buy, buy.copy(id = "other-buy", isin = other.isin)),
            listOf(coupon), quotes)
        val today = day("2026-11-21")
        val overview = PortfolioMath(data).overview(today)
        assertTrue(overview.valueIsPartial)
        assertMoney("2000", overview.partialValue)
        assertNull(overview.points.last().value)
        assertMoney("0", overview.points.last().result)
        assertTrue(overview.points.last().resultIsPartial)
        assertMoney("4000", overview.points[overview.points.lastIndex - 1].value)
        val issue = overview.holdings.single { it.bond.isin == other.isin }.issue
        assertEquals(ValuationIssue.CouponAccrualUnknown("2026-11-21"), issue)

        val single = data.copy(bonds = listOf(bond), trades = listOf(buy), payments = emptyList())
        fun issueFor(vararg values: Quote, date: LocalDate = today, instrument: Bond = bond) =
            PortfolioMath(single.copy(bonds = listOf(instrument), quotes = values.toList())).overview(date).holdings.single().issue
        val saved = quotes.first()
        assertNull(issueFor(saved))
        assertEquals(ValuationIssue.NoQuote, issueFor())
        assertEquals(ValuationIssue.OldQuote, issueFor(saved, date = day("2026-11-28")))
        assertEquals(ValuationIssue.MissingFields(listOf(QuoteField.NOMINAL, QuoteField.ACCRUED)),
            issueFor(saved.copy(nominal = null, accrued = null)))
        assertEquals(ValuationIssue.NominalChanged,
            issueFor(saved, instrument = bond.copy(nominal = "900", nominalDate = "2026-11-21")))
        assertEquals(ValuationIssue.Maturity, issueFor(saved, instrument = bond.copy(maturity = "2026-11-21")))
        assertEquals(ValuationIssue.UnsupportedCurrency, issueFor(saved, instrument = bond.copy(currency = "USD")))
    }

    @Test
    fun showsPartialResultFromIssuesWithKnownValuationsAndCashFlows() {
        val noAccrued = bond.copy(isin = "RU000A10C5Z7")
        val unknownCost = bond.copy(isin = "RU000A10GDW3")
        val redeemed = bond.copy(isin = "RU000A10DZK8")
        val secondAccount = NamedPortfolio("second", "Второй")
        val buy = Trade("buy", isin, TradeSide.BUY, 2, "2026-11-02", null, PriceUnit.RUB_WITH_ACCRUED,
            "950", null, null, "0")
        val data = Portfolio(
            listOf(bond, noAccrued, unknownCost, redeemed),
            listOf(buy, buy.copy(id = "no-accrued", isin = noAccrued.isin, portfolioId = secondAccount.id),
                buy.copy(id = "unknown-cost", isin = unknownCost.isin, quantity = 1, unit = PriceUnit.PERCENT,
                    price = "100", portfolioId = secondAccount.id),
                buy.copy(id = "redeemed", isin = redeemed.isin, quantity = 1, price = "900")),
            listOf(Payment("coupon", isin, PaymentKind.COUPON, "2026-11-20", null, "10"),
                Payment("unpriced-coupon", noAccrued.isin, PaymentKind.COUPON, "2026-11-20", null, "30"),
                Payment("redemption", redeemed.isin, PaymentKind.REDEMPTION, "2026-11-20", null, "1000")),
            listOf(Quote(isin, "2026-11-20", "100", "1000", "0"),
                Quote(noAccrued.isin, "2026-11-20", "1.79", "1000", null),
                Quote(unknownCost.isin, "2026-11-20", "100", "1000", "0")),
            listOf(defaultPortfolio, secondAccount)
        )
        val today = day("2026-11-21")
        val total = PortfolioMath(data).overview(today)
        val point = total.points.last()
        assertNull(point.value)
        assertMoney("3000", total.partialValue)
        assertMoney("80", total.receivedCoupons)
        assertMoney("1000", total.receivedPrincipal)
        assertMoney("220", point.result)
        assertEquals(2, point.issuesWithResult)
        assertEquals(4, point.issuesTracked)
        assertTrue(point.resultIsPartial)

        val first = PortfolioMath(data.scoped(DEFAULT_PORTFOLIO_ID)).overview(today).points.last()
        assertMoney("220", first.result)
        assertFalse(first.resultIsPartial)
        val second = PortfolioMath(data.scoped(secondAccount.id)).overview(today).points.last()
        assertNull(second.result)
        assertEquals(0, second.issuesWithResult)
    }

    @Test
    fun keepsPoliplastValuedAfterTheCouponAndUsesTheNextExchangeQuote() {
        val poliplastIsin = "RU000A10DZK8"
        val poliplast = Bond(poliplastIsin, poliplastIsin, "ПолипП2Б13", "TQCB", currency = "RUB")
        val buy = Trade("buy", poliplastIsin, TradeSide.BUY, 200, "2026-11-20", null, PriceUnit.RUB_WITH_ACCRUED,
            "700", null, null, "0")
        val oldQuote = Quote(poliplastIsin, "2026-11-20", "69.31", "1000", "15.45")
        val paid = Payment("paid", poliplastIsin, PaymentKind.COUPON, "2026-11-21", "2026-11-20", "15.99",
            periodStart = "2026-10-22", nominalBefore = "1000")
        val next = paid.copy(id = "next", date = "2026-12-21", recordDate = "2026-12-18", periodStart = paid.date)
        val data = Portfolio(listOf(poliplast), listOf(buy), listOf(paid, next), listOf(oldQuote))
        val math = PortfolioMath(data)
        val onCoupon = math.overview(day(paid.date))
        assertFalse(onCoupon.valueIsPartial)
        assertMoney("138620", onCoupon.holdings.single().value)
        assertMoney("0", onCoupon.holdings.single().estimatedAccrued)
        assertEquals(oldQuote.date, onCoupon.holdings.single().quoteDate)
        assertMoney("3198", onCoupon.receivedCoupons)
        assertMoney("141710", onCoupon.points.first().value)
        assertFalse(onCoupon.points.first().usesEstimatedValuation)
        assertMoney("138620", onCoupon.points.last().value)
        assertMoney("1818", onCoupon.points.last().result)
        assertTrue(onCoupon.points.last().usesEstimatedValuation)

        val flaggedData = data.copy(bonds = listOf(poliplast.copy(defaultStatus = BondDefaultStatus.TECHNICAL_DEFAULT)))
        val unverified = PortfolioMath(flaggedData).overview(day(paid.date))
        assertEquals(ValuationIssue.CouponUnverified(paid.date), unverified.holdings.single().issue)
        assertNull(unverified.holdings.single().estimatedAccrued)

        val later = math.overview(day("2026-11-22"))
        assertMoney("0.53", later.holdings.single().estimatedAccrued)
        assertMoney("138726", later.points.last().value)
        assertMoney("139260", math.overview(day("2026-11-27")).points.last().value)
        assertEquals(ValuationIssue.OldQuote, math.overview(day("2026-11-28")).holdings.single().issue)

        val fresh = Quote(poliplastIsin, "2026-11-23", "70", "1000", "1.60")
        val refreshed = PortfolioMath(data.copy(quotes = data.quotes + fresh)).overview(day(fresh.date))
        assertMoney("140320", refreshed.holdings.single().value)
        assertNull(refreshed.holdings.single().estimatedAccrued)
        assertFalse(refreshed.points.last().usesEstimatedValuation)
        assertTrue(refreshed.points[1].usesEstimatedValuation)

        val amortization = paid.copy(id = "amortization", kind = PaymentKind.AMORTIZATION, amountPerBond = "100")
        val acrossAmortization = PortfolioMath(data.copy(payments = data.payments + amortization)).overview(day(paid.date))
        assertEquals(ValuationIssue.AfterPayment(paid.date, PaymentKind.AMORTIZATION), acrossAmortization.holdings.single().issue)
        assertNull(acrossAmortization.points.last().value)

        val changed = data.copy(payments = listOf(paid, next.copy(nominalBefore = "900")))
        assertNull(PortfolioMath(changed).overview(day(paid.date)).holdings.single().value)
        val unknownCoupon = data.copy(payments = listOf(paid, next.copy(amountPerBond = null)))
        assertEquals(ValuationIssue.CouponAccrualUnknown(paid.date),
            PortfolioMath(unknownCoupon).overview(day("2026-11-22")).holdings.single().issue)
    }

    private fun assertMoney(expected: String, actual: BigDecimal?) {
        assertEquals(0, BigDecimal(expected).compareTo(requireNotNull(actual)))
    }
}
