package com.eyespy.app.viewer

/**
 * Pure decision logic for confirming a geofence transition before it is reported.
 *
 * Geofence transitions can be false: one coarse location fix a few hundred metres off is enough
 * for Play services to report an exit while you are at home. So every transition (and every
 * periodic check) is confirmed with what the phone can observe right now:
 *  - connected to a known home Wi-Fi access point -> definitely home. Losing Wi-Fi proves nothing.
 *  - a fresh location fix whose whole error circle is inside / outside the home radius.
 */
object PresenceLogic {
    const val MAX_ATTEMPTS = 3

    /** What the phone sees now. distanceM / accuracyM are null when no fresh fix was obtained. */
    data class Observation(val onHomeWifi: Boolean, val distanceM: Float?, val accuracyM: Float?)

    sealed interface Decision {
        /** Send this state to the server. */
        data class Report(val state: String, val why: String) : Decision
        /** The candidate transition is contradicted by what we observe: drop it. */
        data class Ignore(val candidate: String, val why: String) : Decision
        /** Not sure yet: look again later. */
        data object Retry : Decision
        /** Periodic check without a confident answer: leave things as they are. */
        data object NoChange : Decision
    }

    /** "home", "away", or null when the observation can't tell. */
    fun observed(o: Observation, radiusM: Int): Pair<String, String>? {
        if (o.onHomeWifi) return "home" to "on home Wi-Fi"
        val d = o.distanceM ?: return null
        val acc = o.accuracyM ?: return null
        return when {
            d + acc <= radiusM -> "home" to "location fix inside home (±${acc.toInt()} m)"
            d - acc > radiusM -> "away" to "location fix ${d.toInt()} m from home (±${acc.toInt()} m)"
            else -> null
        }
    }

    /**
     * @param candidate "home" / "away" from a geofence transition, or null for a periodic check.
     * @param attempt 1-based attempt number for a transition.
     */
    fun decide(candidate: String?, o: Observation, radiusM: Int, attempt: Int): Decision {
        val seen = observed(o, radiusM)
        if (candidate == null) return if (seen != null) Decision.Report(seen.first, seen.second) else Decision.NoChange
        if (seen != null) {
            return if (seen.first == candidate) Decision.Report(candidate, seen.second)
            else Decision.Ignore(candidate, seen.second)
        }
        if (attempt < MAX_ATTEMPTS) return Decision.Retry
        // Still unsure after the last attempt: go with where the last fix's centre is, if we have one.
        val d = o.distanceM ?: return Decision.Report(candidate, "no fresh location; trusting the geofence")
        val state = if (d > radiusM) "away" else "home"
        return if (state == candidate) Decision.Report(candidate, "best guess from an imprecise fix")
        else Decision.Ignore(candidate, "imprecise fix points the other way")
    }
}
