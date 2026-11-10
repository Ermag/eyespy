package com.eyespy.app.viewer

import com.eyespy.app.viewer.PresenceLogic.Decision
import com.eyespy.app.viewer.PresenceLogic.Observation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PresenceLogicTest {
    private val r = 150
    private fun fix(d: Float, acc: Float) = Observation(false, d, acc)

    @Test fun falseExitOnHomeWifiIsIgnored() {
        // The real case: a coarse fix ~330 m off triggered an exit while the phone sat on home Wi-Fi.
        val d = PresenceLogic.decide("away", Observation(true, null, null), r, 1)
        assertTrue(d is Decision.Ignore)
    }

    @Test fun falseExitWithWifiDroppedIsIgnoredByPreciseFix() {
        assertTrue(PresenceLogic.decide("away", fix(3f, 15f), r, 1) is Decision.Ignore)
    }

    @Test fun realExitIsReported() {
        assertEquals("away", (PresenceLogic.decide("away", fix(400f, 20f), r, 1) as Decision.Report).state)
    }

    @Test fun losingWifiAloneIsNotAway() {
        // off Wi-Fi and no fix: retry rather than report
        assertEquals(Decision.Retry, PresenceLogic.decide("away", Observation(false, null, null), r, 1))
    }

    @Test fun imprecisFixRetriesThenUsesBestGuess() {
        val unsure = fix(160f, 60f)
        assertEquals(Decision.Retry, PresenceLogic.decide("away", unsure, r, 1))
        assertEquals(Decision.Retry, PresenceLogic.decide("away", unsure, r, 2))
        assertEquals("away", (PresenceLogic.decide("away", unsure, r, 3) as Decision.Report).state)
        assertTrue(PresenceLogic.decide("away", fix(120f, 60f), r, 3) is Decision.Ignore)
    }

    @Test fun arrivalOnHomeWifiIsReportedImmediately() {
        assertEquals("home", (PresenceLogic.decide("home", Observation(true, null, null), r, 1) as Decision.Report).state)
    }

    @Test fun falseArrivalFarAwayIsIgnored() {
        assertTrue(PresenceLogic.decide("home", fix(2000f, 30f), r, 1) is Decision.Ignore)
    }

    @Test fun periodicCheck() {
        assertEquals("home", (PresenceLogic.decide(null, Observation(true, null, null), r, 1) as Decision.Report).state)
        assertEquals("away", (PresenceLogic.decide(null, fix(5000f, 50f), r, 1) as Decision.Report).state)
        assertEquals(Decision.NoChange, PresenceLogic.decide(null, fix(150f, 80f), r, 1))
        assertEquals(Decision.NoChange, PresenceLogic.decide(null, Observation(false, null, null), r, 1))
    }
}
