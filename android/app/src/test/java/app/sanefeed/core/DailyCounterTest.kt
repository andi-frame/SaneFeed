package app.sanefeed.core

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DailyCounterTest {
    private class MemoryStore : DailyCounter.Store {
        val days = HashMap<Platform, DailyCounter.Day>()
        override fun load(platform: Platform) = days[platform]
        override fun save(platform: Platform, day: DailyCounter.Day) {
            days[platform] = day
        }
    }

    private val store = MemoryStore()
    private var today = LocalDate.of(2026, 11, 19)
    private fun counter(maxKeys: Int = DailyCounter.DEFAULT_MAX_KEYS) = DailyCounter(store, { today }, maxKeys)

    @Test
    fun `counts each key once per day`() {
        val counter = counter()
        assertTrue(counter.record(Platform.X, "a#1"))
        assertFalse(counter.record(Platform.X, "a#1"))
        assertTrue(counter.record(Platform.X, "b#2"))
        assertEquals(2, counter.count(Platform.X))
    }

    @Test
    fun `platforms are counted separately`() {
        val counter = counter()
        counter.record(Platform.X, "a#1")
        assertTrue(counter.record(Platform.INSTAGRAM, "a#1"))
        assertEquals(1, counter.count(Platform.X))
        assertEquals(1, counter.count(Platform.INSTAGRAM))
    }

    @Test
    fun `resets at local midnight`() {
        val counter = counter()
        counter.record(Platform.X, "a#1")
        today = today.plusDays(1)
        assertEquals(0, counter.count(Platform.X))
        assertTrue(counter.record(Platform.X, "a#1"))
        assertEquals(1, counter.count(Platform.X))
        assertEquals(today, store.days.getValue(Platform.X).date)
    }

    @Test
    fun `restores count and dedupe keys from the store`() {
        counter().apply {
            record(Platform.X, "a#1")
            record(Platform.X, "b#2")
        }
        val restored = counter()
        assertEquals(2, restored.count(Platform.X))
        assertFalse(restored.record(Platform.X, "a#1"))
    }

    @Test
    fun `ignores a stored day that is not today`() {
        counter().record(Platform.X, "a#1")
        today = today.plusDays(1)
        val restored = counter()
        assertEquals(0, restored.count(Platform.X))
        assertTrue(restored.record(Platform.X, "a#1"))
    }

    @Test
    fun `key set is bounded and drops the oldest key`() {
        val counter = counter(maxKeys = 2)
        counter.record(Platform.X, "a")
        counter.record(Platform.X, "b")
        counter.record(Platform.X, "c")
        assertEquals(listOf("b", "c"), store.days.getValue(Platform.X).keys)
        assertEquals(3, counter.count(Platform.X))
        assertFalse(counter.record(Platform.X, "c"))
    }

    @Test
    fun `counts flow publishes changes`() {
        val counter = counter()
        counter.record(Platform.INSTAGRAM, "a")
        assertEquals(1, counter.counts.value[Platform.INSTAGRAM])
    }
}
