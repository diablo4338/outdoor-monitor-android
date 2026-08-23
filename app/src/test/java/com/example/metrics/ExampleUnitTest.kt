package com.example.metrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherStateTest {
    @Test
    fun refreshDoesNotReplaceEstablishedStatusWithLoading() {
        val ready = WeatherState().withSnapshot(
            SensorDevice.Primary,
            snapshot(temp = "20", hum = "50", timestamp = 1L),
        )

        assertSame(ready, ready.withLoading(SensorDevice.Primary, true))
    }

    @Test
    fun backendFailureDoesNotMutateCardState() {
        val ready = WeatherState().withSnapshot(
            SensorDevice.Primary,
            snapshot(temp = "20", hum = "50", timestamp = 1L),
        )

        assertSame(
            ready,
            ready.withIssue(SensorDevice.Primary, null, warning = false, clearData = false),
        )
    }

    @Test
    fun cardsKeepIndependentValuesAndStatuses() {
        val primaryReady = WeatherState().withSnapshot(
            SensorDevice.Primary,
            snapshot(temp = "20", hum = "50", timestamp = 1L),
        )
        val bothLoaded = primaryReady.withSnapshot(
            SensorDevice.External,
            snapshot(externalTemp = null, externalHum = null, externalTimestamp = 2L),
        )

        assertEquals("20", bothLoaded.primaryCard.temp)
        assertEquals(SensorCardStatus.Ready, bothLoaded.primaryCard.status)
        assertEquals(SensorCardStatus.SensorUnavailable, bothLoaded.externalCard.status)
    }

    @Test
    fun repeatedIdenticalSensorErrorProducesEqualState() {
        val first = WeatherState().withIssue(
            SensorDevice.External,
            "Нет данных",
            warning = true,
            clearData = false,
        )
        val second = first.withIssue(
            SensorDevice.External,
            "Нет данных",
            warning = true,
            clearData = false,
        )

        assertEquals(first, second)
    }

    @Test
    fun olderAvailabilityResultCannotOverrideNewerResult() {
        val tracker = BackendAvailabilityTracker()

        tracker.report(requestId = 2L, available = false)
        tracker.report(requestId = 1L, available = true)

        assertTrue(tracker.unavailable.value)
        tracker.report(requestId = 3L, available = true)
        assertFalse(tracker.unavailable.value)
    }

    private fun snapshot(
        temp: String? = null,
        hum: String? = null,
        externalTemp: String? = null,
        externalHum: String? = null,
        timestamp: Long? = null,
        externalTimestamp: Long? = null,
    ) = WeatherSnapshot(
        temp = temp,
        hum = hum,
        externalTemp = externalTemp,
        externalHum = externalHum,
        lastUpdateEpochSeconds = timestamp,
        externalLastUpdateEpochSeconds = externalTimestamp,
    )
}
