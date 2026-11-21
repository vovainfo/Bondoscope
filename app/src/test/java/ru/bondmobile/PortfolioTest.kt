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

    private fun assertMoney(expected: String, actual: BigDecimal?) {
        assertEquals(0, BigDecimal(expected).compareTo(requireNotNull(actual)))
    }
}
