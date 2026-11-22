package ru.bondmobile

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChartScaleTest {
    @Test
    fun labelsNegativeAndPositiveResultsAndAlignsZeroWithTheGrid() {
        val scale = ChartScale(BigDecimal("-650000"), BigDecimal("850000"))
        assertEquals(listOf("1", "0,5", "0", "−0,5", "−1").map { "$it\u00a0млн ₽" }, scale.labels)
        assertEquals(10f, scale.y(BigDecimal("1000000"), 160f, 10f), 0.01f)
        assertEquals(80f, scale.y(BigDecimal.ZERO, 160f, 10f), 0.01f)
        assertEquals(150f, scale.y(BigDecimal("-1000000"), 160f, 10f), 0.01f)
    }

    @Test
    fun usesTheSameZeroBasedScaleForReceiptBarsAndTheirLabels() {
        val scale = ChartScale(BigDecimal.ZERO, BigDecimal("3200"))
        assertEquals(0, BigDecimal("4000").compareTo(scale.maximumTick))
        assertEquals(listOf("4", "3", "2", "1", "0").map { "$it\u00a0тыс. ₽" }, scale.labels)
        assertEquals(35.6f, scale.y(BigDecimal("3200"), 148f, 10f), 0.01f)
        assertEquals(138f, scale.y(BigDecimal.ZERO, 148f, 10f), 0.01f)
    }

    @Test
    fun labelsTheCleanPriceAxisInPercent() {
        val scale = ChartScale(BigDecimal("98.5"), BigDecimal("100.5"), ChartUnit.PERCENT)
        assertEquals(listOf("100,5", "100", "99,5", "99", "98,5").map { "$it\u00a0%" }, scale.labels)
    }

    @Test
    fun keepsLabelsDistinctForConstantValuesAndSmallRanges() {
        val ranges = listOf("0" to "0", "120" to "120", "-150" to "-150", "1000000.01" to "1000000.03", "1" to "2")
        for ((low, high) in ranges) {
            val scale = ChartScale(BigDecimal(low), BigDecimal(high))
            assertTrue(scale.ticks.first() >= BigDecimal(high))
            assertTrue(scale.ticks.last() <= BigDecimal(low))
            assertTrue(scale.labels.size in 2..6)
            assertEquals(scale.labels.size, scale.labels.distinct().size)
        }
    }
}
