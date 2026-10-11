package de.askurious.app.data

import org.json.JSONObject

/**
 * The clock of a fast-forward test person (server: "clock" of POST /study): from [t0] (epoch milliseconds) on, one
 * study day passes in 24 h / [factor] of real time. Everything the app computes for such a study (dates, prompt
 * times, expiry, reminders, quiet hours, what it sends as "due", "notified", "started", "completed") is in the
 * virtual time [virtual]; an alarm rings at the real moment [real] of a virtual one. The zone stays the phone's.
 */
data class Clock(val t0: Long, val factor: Int) {
    /** The virtual time at the real moment [real]: t0 + (real − t0) · factor. */
    fun virtual(real: Long): Long = t0 + (real - t0) * factor

    /**
     * The real moment at which the virtual time reaches [virtual]: t0 + (virtual − t0) / factor, rounded up, so
     * that an alarm set to it never rings before its virtual moment.
     */
    fun real(virtual: Long): Long = t0 - Math.floorDiv(t0 - virtual, factor.toLong())

    fun toJson(): JSONObject = JSONObject().put("t0", t0).put("factor", factor)

    companion object {
        /** {"t0", "factor"} or null; a factor below 2 is no fast-forward (the clock would be the real one). */
        fun fromJson(o: JSONObject?): Clock? {
            if (o == null || !o.has("t0")) return null
            val factor = o.optInt("factor", 1)
            return if (factor >= 2) Clock(o.optLong("t0"), factor) else null
        }
    }
}
