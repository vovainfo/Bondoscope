package ru.bondmobile

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException
import java.math.BigDecimal
import java.net.URI
import java.net.URLEncoder
import java.time.LocalDate
import java.util.Locale
import javax.net.ssl.HttpsURLConnection

data class BondOffer(val date: String, val details: OfferDetails?)
data class BondCalendar(val payments: List<Payment>, val complete: Boolean, val offers: List<BondOffer>?)

internal data class PlacementBoard(
    val secid: String,
    val id: String,
    val market: String,
    val firstHistoryDate: String,
    val lastHistoryDate: String?
)

class Moex {
    suspend fun search(text: String): List<Bond> {
        val query = text.trim()
        return table(request("/securities.json", "q" to query, "iss.only" to "securities"), "securities")
            .filter { (it.str("group") == "stock_bonds" || it.str("type")?.contains("bond", true) == true) }
            .mapNotNull { row ->
                val isin = row.str("isin")?.takeIf(isinPattern::matches) ?: return@mapNotNull null
                val code = row.str("secid") ?: return@mapNotNull null
                Bond(isin, code, row.str("shortname") ?: row.str("name") ?: code, row.str("primary_boardid"))
            }.distinctBy { it.isin }.sortedByDescending { it.isin.equals(query, true) || it.secid.equals(query, true) }
            .take(20)
    }

    suspend fun describe(original: Bond): Bond {
        val raw = request("/securities/${segment(original.secid)}.json", "iss.only" to "description,boards")
        val description = table(raw, "description").mapNotNull { row ->
            row.str("name")?.uppercase(Locale.ROOT)?.let { it to row.str("value") }
        }.toMap()
        val boards = table(raw, "boards").filter { it.str("engine") == "stock" && it.str("market") == "bonds" }
        val primary = boards.firstOrNull { it.str("is_primary") == "1" }
            ?: boards.firstOrNull { it.str("boardid") == original.board }
            ?: boards.firstOrNull { it.str("is_traded") == "1" }
        if (description.isEmpty() || (boards.isEmpty() && description["GROUP"] != "stock_bonds")) {
            throw IOException("Выпуск облигаций не найден на Мосбирже")
        }
        val resolvedIsin = description["ISIN"]
        if (resolvedIsin != null && resolvedIsin != original.isin) throw IOException("ISIN выпуска не совпадает")
        val today = marketToday()
        val status = readDefaultStatus(original, description["HASDEFAULT"], description["HASTECHNICALDEFAULT"], today)
        val nominal = description["FACEVALUE"]?.decimalOrNull()?.toPlainString()
        return original.copy(
            secid = description["SECID"] ?: original.secid,
            name = description["SHORTNAME"] ?: description["NAME"] ?: original.name,
            board = primary?.str("boardid") ?: original.board,
            maturity = description["MATDATE"].asDate(),
            currency = description["FACEUNIT"] ?: primary?.str("currencyid") ?: original.currency,
            nominal = nominal ?: original.nominal,
            nominalDate = if (nominal != null) today.toString() else original.nominalDate,
            updatedAt = System.currentTimeMillis(),
            defaultStatus = status.defaultStatus,
            paymentsUnverifiedThrough = status.paymentsUnverifiedThrough
        )
    }

    internal fun readDefaultStatus(original: Bond, defaultFlag: String?, technicalFlag: String?, today: LocalDate): Bond =
        when {
            defaultFlag == "1" -> original.copy(defaultStatus = BondDefaultStatus.DEFAULT)
            technicalFlag == "1" && (defaultFlag == "0" || original.defaultStatus != BondDefaultStatus.DEFAULT) ->
                original.copy(defaultStatus = BondDefaultStatus.TECHNICAL_DEFAULT)
            defaultFlag == "0" && technicalFlag == "0" && original.defaultStatus != null ->
                original.copy(defaultStatus = null,
                    paymentsUnverifiedThrough = maxOf(original.paymentsUnverifiedThrough.orEmpty(), today.toString()))
            else -> original
        }

    suspend fun placementDate(bond: Bond): LocalDate? = coroutineScope {
        suspend fun placementDetails(secid: String) = request(
            "/securities/${segment(secid)}.json", "iss.only" to "description,boards",
            "description.columns" to "name,value", "boards.columns" to "secid,engine,market,boardid,title,history_from,history_till"
        )

        val original = placementDetails(bond.secid)
        val raw = if (bond.secid == bond.isin && table(original, "description").isEmpty()) {
            val secid = search(bond.isin).firstOrNull { it.isin == bond.isin && it.secid != bond.secid }?.secid
                ?: return@coroutineScope null
            placementDetails(secid)
        } else original

        decodePlacementBoards(raw, bond.isin).map { board ->
            async {
                val path = "/history/engines/stock/markets/${segment(board.market)}/boards/" +
                    "${segment(board.id)}/securities/${segment(board.secid)}.json"
                val window = buildList {
                    add("from" to board.firstHistoryDate)
                    board.lastHistoryDate?.let { add("till" to it) }
                    add("history.columns" to "SECID,BOARDID,TRADEDATE,NUMTRADES")
                }
                decodePlacementTradeDate(pages(path, "history", *window.toTypedArray()), board)
            }
        }.awaitAll().filterNotNull().minOrNull()
    }

    internal fun decodePlacementBoards(raw: Any, isin: String): List<PlacementBoard> {
        val issue = table(raw, "description").firstOrNull { it.str("name").equals("ISIN", ignoreCase = true) }
        if (issue?.str("value") != isin) return emptyList()

        return table(raw, "boards")
            .filter { it.str("engine") == "stock" && it.str("title")?.startsWith("Размещение", ignoreCase = true) == true }
            .mapNotNull { row ->
                val secid = row.str("secid") ?: return@mapNotNull null
                val id = row.str("boardid") ?: return@mapNotNull null
                val market = row.str("market") ?: return@mapNotNull null
                val from = row.str("history_from").asDate() ?: return@mapNotNull null
                PlacementBoard(secid, id, market, from, row.str("history_till").asDate())
            }.distinctBy { Triple(it.secid, it.market, it.id) }
    }

    internal fun decodePlacementTradeDate(rows: List<JSONObject>, board: PlacementBoard): LocalDate? = rows.asSequence()
        .filter { it.str("SECID") == board.secid && it.str("BOARDID") == board.id &&
            (it.str("NUMTRADES")?.toLongOrNull() ?: 0L) > 0L }
        .mapNotNull { it.str("TRADEDATE").asDate() }
        .minOrNull()?.let(LocalDate::parse)

    suspend fun current(bond: Bond): Pair<Bond, Quote?> {
        val board = bond.board ?: return bond to null
        val raw = request(
            "/engines/stock/markets/bonds/boards/${segment(board)}/securities/${segment(bond.secid)}.json",
            "iss.only" to "securities,marketdata"
        )
        val security = table(raw, "securities").firstOrNull()
        val market = table(raw, "marketdata").firstOrNull()
        val nominal = security?.number("FACEVALUE")
        val now = marketToday().toString()
        val settlement = security?.str("SETTLEDATE").asDate()
        val currentNominal = nominal?.takeIf { bond.nominalDate != now && (settlement == null || settlement <= now) }
        val currency = security?.str("FACEUNIT") ?: bond.currency
        val updated = bond.copy(
            maturity = security?.str("MATDATE").asDate() ?: bond.maturity,
            currency = currency,
            nominal = currentNominal ?: bond.nominal,
            nominalDate = if (currentNominal != null) now else bond.nominalDate
        )
        val date = market?.str("TRADEDATE", "SYSTIME")?.take(10).asDate()
        val last = market?.number("LAST")?.takeIf { it.toBigDecimal() > BigDecimal.ZERO }
        val quote = if (date == now && last != null) {
            Quote(bond.isin, now, last, nominal, security?.number("ACCRUEDINT"))
        } else null
        return updated to quote
    }

    suspend fun calendar(bond: Bond): BondCalendar = coroutineScope {
        val path = "/securities/${bond.isin}/bondization.json"
        val alternative = "/statistics/engines/stock/markets/bonds/bondization/${bond.isin}.json"
        suspend fun read(block: String): List<JSONObject>? = try {
            pages(path, block)
        } catch (_: MissingIssTable) {
            try { pages(alternative, block) } catch (_: IOException) { null }
        } catch (_: IOException) {
            null
        }
        val couponTask = async { read("coupons") }
        val principalTask = async { read("amortizations") }
        val offersTask = async { read("offers") }
        val couponRows = couponTask.await()
        val principalRows = principalTask.await()
        BondCalendar(decodeCalendar(bond, couponRows.orEmpty(), principalRows.orEmpty()),
            couponRows != null && principalRows != null, offersTask.await()?.let(::decodeOffers))
    }

    internal fun decodeOffers(rows: List<JSONObject>): List<BondOffer> = rows.mapNotNull { row ->
        val date = row.str("offerdate").asDate() ?: return@mapNotNull null
        val details = OfferDetails(
            type = row.str("offertype"),
            startDate = row.str("offerdatestart").asDate(),
            endDate = row.str("offerdateend").asDate(),
            pricePercent = row.number("price"),
            amount = row.number("value"),
            nominal = row.number("facevalue"),
            currency = row.str("faceunit"),
            agent = row.str("agent")
        )
        BondOffer(date, details.takeUnless { it == OfferDetails() })
    }.sortedWith(compareBy<BondOffer> { it.date }
        .thenByDescending { it.details?.endDate != null }
        .thenByDescending { it.details?.startDate != null }
        .thenByDescending { it.details?.pricePercent != null || it.details?.amount != null }
        .thenByDescending { it.details != null })
        .distinctBy { it.date }

    internal fun decodeCalendar(bond: Bond, couponRows: List<JSONObject>, principalRows: List<JSONObject>): List<Payment> {
        val coupons = couponRows.mapNotNull { row ->
            val date = row.str("coupondate").asDate() ?: return@mapNotNull null
            val period = row.str("startdate").asDate()
            Payment(
                id = "moex:${bond.isin}:coupon:${period ?: date}",
                isin = bond.isin,
                kind = PaymentKind.COUPON,
                date = date,
                recordDate = row.str("recorddate").asDate(),
                amountPerBond = row.rubleAmount(),
                periodStart = period,
                nominalBefore = row.number("facevalue")?.takeIf { row.str("faceunit")?.isRubles() == true }
            )
        }
        val principal = principalRows.mapNotNull { row ->
            val date = row.str("amortdate").asDate() ?: return@mapNotNull null
            val paid = row.rubleAmount()
            val before = row.number("facevalue")?.takeIf { row.str("faceunit")?.isRubles() == true }
            val kind = when {
                date == bond.maturity -> PaymentKind.REDEMPTION
                row.number("valueprc")?.toBigDecimal()?.compareTo(BigDecimal(100)) == 0 -> PaymentKind.REDEMPTION
                bond.maturity != null && date < bond.maturity -> PaymentKind.AMORTIZATION
                paid != null && before != null && paid.toBigDecimal() < before.toBigDecimal() -> PaymentKind.AMORTIZATION
                else -> PaymentKind.PRINCIPAL
            }
            val id = if (kind == PaymentKind.REDEMPTION) "moex:${bond.isin}:redemption"
            else "moex:${bond.isin}:principal:$date"
            Payment(id, bond.isin, kind, date, row.str("recorddate").asDate(), paid, nominalBefore = before)
        }
        val inferredMaturity = bond.maturity?.takeIf { date -> principal.none { it.date == date } }?.let { date ->
            Payment("moex:${bond.isin}:redemption", bond.isin, PaymentKind.REDEMPTION, date, null, null)
        }
        return (coupons + principal + listOfNotNull(inferredMaturity)).distinctBy { it.id }
    }

    suspend fun history(bond: Bond, from: LocalDate, till: LocalDate): List<Quote> {
        val market = "/history/engines/stock/markets/bonds"
        val path = "$market/securities/${segment(bond.secid)}.json"
        val rows = pages(path, "history", "from" to from.toString(), "till" to till.toString())
        return rows.groupBy { it.str("TRADEDATE") }.mapNotNull { (rawDate, candidates) ->
            val date = rawDate.asDate() ?: return@mapNotNull null
            val priced = candidates.filter { it.cleanPrice() != null }
            val row = priced.firstOrNull { it.str("BOARDID") == bond.board }
                ?: priced.filter { it.str("BOARDID") in regularBoards }.maxByOrNull {
                    it.number("NUMTRADES")?.toBigDecimal() ?: BigDecimal.ZERO
                }
                ?: priced.singleOrNull()?.takeIf { candidates.size == 1 && it.str("BOARDID") == null }
                ?: return@mapNotNull null
            Quote(bond.isin, date, row.cleanPrice(), row.number("FACEVALUE"), row.number("ACCINT", "ACCRUEDINT"))
        }
    }

    private suspend fun pages(path: String, block: String, vararg args: Pair<String, String>): List<JSONObject> {
        val accumulated = mutableListOf<JSONObject>()
        var start = 0
        var previousPage: String? = null
        while (true) {
            val raw = request(path, *args, "iss.only" to "$block,$block.cursor", "start" to start.toString(), "limit" to "100")
            if (!hasBlock(raw, block)) throw MissingIssTable(block)
            val rows = table(raw, block)
            if (rows.isEmpty()) return accumulated
            val signature = rows.joinToString("|")
            if (signature == previousPage) throw IOException("Мосбиржа повторила страницу $block")
            previousPage = signature
            accumulated += rows
            val cursor = table(raw, "$block.cursor").firstOrNull()
            val total = cursor?.str("TOTAL")?.toIntOrNull()
            val next = start + rows.size
            if (total != null && next >= total) return accumulated
            if (total == null && block != "history" && rows.size < 100) return accumulated
            if (next > 120_000) throw IOException("Слишком большой ответ Мосбиржи")
            start = next
        }
    }

    private suspend fun request(path: String, vararg params: Pair<String, String>): Any {
        currentCoroutineContext().ensureActive()
        val query = (listOf("iss.meta" to "off") + params).joinToString("&") {
            "${segment(it.first)}=${segment(it.second)}"
        }
        val connection = URI("https://iss.moex.com/iss$path?$query").toURL().openConnection() as HttpsURLConnection
        connection.connectTimeout = 8_000
        connection.readTimeout = 12_000
        connection.setRequestProperty("Accept", "application/json")
        try {
            if (connection.responseCode != 200) throw IOException("Мосбиржа ответила HTTP ${connection.responseCode}")
            return connection.inputStream.bufferedReader(Charsets.UTF_8).use { JSONTokener(it.readText()).nextValue() }
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private val regularBoards = setOf("TQOB", "TQCB", "TQOD", "TQIR", "TQOY", "TQOE", "EQOB")

        private fun segment(text: String) = URLEncoder.encode(text, "UTF-8").replace("+", "%20")

        private fun String?.asDate(): String? = this?.takeIf { it.length == 10 }
            ?.let { runCatching { LocalDate.parse(it).toString() }.getOrNull() }

        private fun JSONObject.str(vararg fields: String): String? = fields.firstNotNullOfOrNull { name ->
            val key = when {
                has(name) -> name
                has(name.lowercase(Locale.ROOT)) -> name.lowercase(Locale.ROOT)
                has(name.uppercase(Locale.ROOT)) -> name.uppercase(Locale.ROOT)
                else -> null
            }
            key?.takeUnless { isNull(it) }?.let { optString(it).trim().takeIf(String::isNotEmpty) }
        }

        private fun JSONObject.number(vararg fields: String): String? = str(*fields)
            ?.toBigDecimalOrNull()?.takeIf { it.signum() >= 0 }?.toPlainString()

        private fun JSONObject.cleanPrice() = number("CLOSE", "LEGALCLOSEPRICE", "LASTPRICE")
            ?.takeIf { it.toBigDecimal() > BigDecimal.ZERO }

        private fun JSONObject.rubleAmount(): String? = number("value_rub")
            ?: str("faceunit", "currencyid")?.takeIf(String::isRubles)?.let { number("value") }

        private fun tables(raw: Any): JSONObject = when (raw) {
            is JSONObject -> raw
            is JSONArray -> raw.optJSONObject(1) ?: JSONObject()
            else -> JSONObject()
        }

        private fun hasBlock(raw: Any, name: String) = tables(raw).has(name)

        internal fun table(raw: Any, name: String): List<JSONObject> {
            val payload = tables(raw).opt(name)
            if (payload is JSONArray) return (0 until payload.length()).mapNotNull(payload::optJSONObject)
            val block = payload as? JSONObject ?: return emptyList()
            val columns = block.optJSONArray("columns") ?: return emptyList()
            val rows = block.optJSONArray("data") ?: return emptyList()
            return (0 until rows.length()).mapNotNull { i ->
                val values = rows.optJSONArray(i) ?: return@mapNotNull null
                JSONObject().apply {
                    for (index in 0 until columns.length()) {
                        put(columns.getString(index), values.opt(index) ?: JSONObject.NULL)
                    }
                }
            }
        }
    }
}

private class MissingIssTable(block: String) : IOException("Мосбиржа не вернула таблицу $block")
