package ru.bondmobile

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.time.LocalDate

object Backup {
    private const val FILE_TYPE = "ru.bondmobile.portfolio"
    private const val VERSION = 2
    private const val MAX_BYTES = 32 * 1024 * 1024

    fun write(portfolio: Portfolio, output: OutputStream) {
        val json = obj(
            "format" to FILE_TYPE, "version" to VERSION,
            "portfolios" to array(portfolio.accounts) { obj("id" to it.id, "name" to it.name) },
            "bonds" to array(portfolio.bonds) {
                obj("isin" to it.isin, "secid" to it.secid, "name" to it.name, "board" to it.board,
                    "maturity" to it.maturity, "currency" to it.currency, "nominal" to it.nominal,
                    "nominalDate" to it.nominalDate, "updatedAt" to it.updatedAt,
                    "calendarUpdatedAt" to it.calendarUpdatedAt, "historyStart" to it.historyStart,
                    "defaultStatus" to it.defaultStatus?.name, "paymentsUnverifiedThrough" to it.paymentsUnverifiedThrough,
                    "offerDate" to it.offerDate)
            },
            "trades" to array(portfolio.trades) {
                obj("id" to it.id, "isin" to it.isin, "side" to it.side.name, "quantity" to it.quantity,
                    "date" to it.date, "settlement" to it.settlement, "unit" to it.unit.name, "price" to it.price,
                    "nominal" to it.nominal, "accrued" to it.accrued, "fee" to it.fee, "portfolioId" to it.portfolioId)
            },
            "payments" to array(portfolio.payments) {
                obj("id" to it.id, "isin" to it.isin, "kind" to it.kind.name, "date" to it.date,
                    "recordDate" to it.recordDate, "amountPerBond" to it.amountPerBond,
                    "periodStart" to it.periodStart, "nominalBefore" to it.nominalBefore, "source" to it.source,
                    "receivedDate" to it.receivedDate, "receivedAmount" to it.receivedAmount)
            },
            "quotes" to array(portfolio.quotes) {
                obj("isin" to it.isin, "date" to it.date, "cleanPercent" to it.cleanPercent,
                    "nominal" to it.nominal, "accrued" to it.accrued)
            }
        ).toString().toByteArray(Charsets.UTF_8)
        require(json.size <= MAX_BYTES) { "Резервная копия превышает 32 МБ" }
        output.write(json)
    }

    fun read(input: InputStream, today: LocalDate = marketToday()): Portfolio {
        val bytes = ByteArrayOutputStream()
        val buffer = ByteArray(16_384)
        while (true) {
            val length = input.read(buffer)
            if (length < 0) break
            require(bytes.size() + length <= MAX_BYTES) { "Резервная копия превышает 32 МБ" }
            bytes.write(buffer, 0, length)
        }
        val root = JSONObject(bytes.toString("UTF-8").removePrefix("\uFEFF"))
        require(root.getString("format") == FILE_TYPE) { "Выберите резервную копию приложения «Бондоскоп»" }
        val version = root.getInt("version")
        require(version in 1..VERSION) { "Эта версия формата резервной копии не поддерживается" }
        val accounts = if (version == 1) listOf(defaultPortfolio) else root.list("portfolios", 50_000) {
            NamedPortfolio(text("id", 160), text("name", MAX_PORTFOLIO_NAME_LENGTH).trim())
        }
        val accountIds = accounts.map { it.id }.toSet()
        require(accountIds.size == accounts.size && DEFAULT_PORTFOLIO_ID in accountIds) { "В копии повреждён список портфелей" }
        val maxTradeDate = today.toString()
        val bonds = root.list("bonds", 5_000) {
            Bond(isin(), text("secid", 40), text("name", 300), optional("board", 25), day("maturity"),
                optional("currency", 10), number("nominal"), day("nominalDate"),
                optLong("updatedAt").coerceIn(0, System.currentTimeMillis()),
                optLong("calendarUpdatedAt").coerceIn(0, System.currentTimeMillis()), day("historyStart"),
                optional("defaultStatus", 24)?.let(BondDefaultStatus::valueOf), day("paymentsUnverifiedThrough"), day("offerDate"))
        }
        val trades = root.list("trades", 50_000) {
            val date = day("date") ?: error("В сделке пропущена дата")
            val settlement = day("settlement")
            require(date <= maxTradeDate && (settlement == null || settlement in date..maxTradeDate))
            val quantity = getInt("quantity").also { require(it in 1..MAX_QUANTITY) }
            val portfolioId = if (version == 1) DEFAULT_PORTFOLIO_ID else text("portfolioId", 160)
            require(portfolioId in accountIds) { "В копии есть сделка без портфеля" }
            Trade(text("id", 160), isin(), TradeSide.valueOf(text("side", 10)), quantity, date, settlement,
                PriceUnit.valueOf(text("unit", 24)), number("price", positive = true) ?: error("Пропущена цена сделки"),
                number("nominal", positive = true), number("accrued"), number("fee") ?: error("Пропущена комиссия"), portfolioId)
        }
        val payments = root.list("payments", 80_000) {
            val received = day("receivedDate")
            val receivedAmount = number("receivedAmount")
            require((received == null) == (receivedAmount == null) && (received == null || received <= maxTradeDate))
            val source = text("source", 12).also { require(it in setOf("MOEX", "MANUAL")) }
            Payment(text("id", 160), isin(), PaymentKind.valueOf(text("kind", 20)),
                day("date") ?: error("В выплате пропущена дата"), day("recordDate"), number("amountPerBond"),
                day("periodStart"), number("nominalBefore"), source, received, receivedAmount)
        }
        val quotes = root.list("quotes", 200_000) {
            val date = day("date") ?: error("В котировке пропущена дата")
            require(date <= maxTradeDate)
            Quote(isin(), date, number("cleanPercent"), number("nominal"), number("accrued"))
        }
        require(bonds.distinctBy { it.isin }.size == bonds.size && trades.distinctBy { it.id }.size == trades.size &&
            payments.distinctBy { it.id }.size == payments.size && quotes.distinctBy { it.isin to it.date }.size == quotes.size) {
            "В резервной копии есть дубли"
        }
        val known = bonds.map { it.isin }.toSet()
        require(trades.all { it.isin in known } && payments.all { it.isin in known } && quotes.all { it.isin in known }) {
            "В резервной копии не хватает карточек выпусков"
        }
        require(validQuantityHistory(trades)) { "В копии есть отрицательный или недопустимый остаток" }
        return Portfolio(bonds, trades, payments, quotes, accounts)
    }

    private fun obj(vararg entries: Pair<String, Any?>) = JSONObject().apply {
        entries.forEach { (key, value) -> put(key, value ?: JSONObject.NULL) }
    }

    private fun <T> array(items: List<T>, row: (T) -> JSONObject) = JSONArray().apply { items.forEach { put(row(it)) } }

    private fun <T> JSONObject.list(key: String, limit: Int, parse: JSONObject.() -> T): List<T> {
        val rows = getJSONArray(key)
        require(rows.length() <= limit) { "Слишком много записей в резервной копии" }
        return (0 until rows.length()).map { rows.getJSONObject(it).parse() }
    }

    private fun JSONObject.optional(key: String, max: Int): String? = if (isNull(key)) null else
        getString(key).also { require(it.isNotBlank() && it.length <= max) }

    private fun JSONObject.text(key: String, max: Int) = optional(key, max) ?: error("Пропущено обязательное поле")

    private fun JSONObject.isin() = text("isin", 12).also { require(isinPattern.matches(it)) }

    private fun JSONObject.day(key: String): String? = optional(key, 10)?.let {
        val date = LocalDate.parse(it)
        require(date >= earliestDate && date.year <= 2200) { "Дата выходит за допустимый диапазон" }
        date.toString()
    }

    private fun JSONObject.number(key: String, positive: Boolean = false): String? = optional(key, 30)?.let {
        val number = requireNotNull(it.decimalOrNull()) { "Некорректная сумма в резервной копии" }
        require(!positive || number.signum() > 0)
        number.toPlainString()
    }
}
