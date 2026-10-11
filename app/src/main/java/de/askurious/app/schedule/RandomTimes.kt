package de.askurious.app.schedule

import de.askurious.app.data.Quiet
import de.askurious.app.data.Trigger
import java.time.LocalDate
import java.time.LocalTime

/**
 * The times of a random module on one day, drawn per person and day. The server draws the same times with the same
 * arithmetic (app/diary.py, random_times; test vectors in tests/diary_random_vectors.json of both repositories), so
 * a prompt the phone posts is one the server takes as due: SplitMix64 over a state from the person's seed, the
 * module key (FNV-1a 64) and the day (days since 1970-01-01), all in unsigned 64-bit arithmetic.
 *
 * The free minutes of the day (the windows without the quiet hours) are cut into perDay equal slots; each slot draws
 * one minute, kept minGap/2 from its edges, so two prompts are at least minGap apart.
 */
object RandomTimes {
    private const val GOLDEN = 0x9E3779B97F4A7C15uL
    private const val DAY = 1440

    /** (next value, next state) of SplitMix64. */
    fun splitmix(state: ULong): Pair<ULong, ULong> {
        val next = state + GOLDEN
        var z = (next xor (next shr 30)) * 0xBF58476D1CE4E5B9uL
        z = (z xor (z shr 27)) * 0x94D049BB133111EBuL
        return (z xor (z shr 31)) to next
    }

    /** FNV-1a 64 over the UTF-8 bytes of [text]. */
    fun fnv(text: String): ULong {
        var h = 0xCBF29CE484222325uL
        for (b in text.toByteArray(Charsets.UTF_8)) h = (h xor b.toUByte().toULong()) * 0x100000001B3uL
        return h
    }

    /** "HH:MM" as minutes of the day. */
    fun minutes(hhmm: String): Int {
        val (h, m) = hhmm.split(":").map { it.trim().toInt() }
        return h * 60 + m
    }

    /** The minutes of [start, end); an end before the start runs over midnight, start == end is nothing. */
    private fun spans(start: Int, end: Int): List<IntRange> = when {
        start < end -> listOf(start until end)
        start > end -> listOf(start until DAY, 0 until end)
        else -> emptyList()
    }

    /** The free minutes of the day: inside [windows] and outside [quiet], as runs (start, end exclusive) in order. */
    fun free(windows: List<Pair<String, String>>, quiet: Quiet?): List<Pair<Int, Int>> {
        val free = BooleanArray(DAY)
        for ((a, b) in windows) for (r in spans(minutes(a), minutes(b))) for (x in r) free[x] = true
        if (quiet != null) for (r in spans(minutes(quiet.start), minutes(quiet.end))) for (x in r) free[x] = false
        val out = mutableListOf<Pair<Int, Int>>()
        var x = 0
        while (x < DAY) {
            if (!free[x]) {
                x++
                continue
            }
            val start = x
            while (x < DAY && free[x]) x++
            out += start to x
        }
        return out
    }

    /** The times of the module [key] with trigger [t] on [day] for the person with [seed], in order. */
    fun times(seed: Long, key: String, day: LocalDate, t: Trigger.Random, quiet: Quiet?): List<LocalTime> {
        val n = t.perDay
        val gap = t.minGap
        val free = free(t.windows, quiet)
        val total = free.sumOf { it.second - it.first }
        if (n <= 0 || total <= 0) return emptyList()
        var state = seed.toULong() xor fnv(key) xor (day.toEpochDay().toULong() * GOLDEN)
        val out = mutableListOf<Int>()
        for (i in 0 until n) {
            val start = i * total / n
            val end = (i + 1) * total / n
            var lo = start + gap / 2
            var hi = end - (gap - gap / 2)
            if (hi <= lo) {
                // the slots are shorter than the gap: anywhere in the slot
                lo = start
                hi = end
            }
            if (hi <= lo) continue
            val (value, next) = splitmix(state)
            state = next
            var index = lo + (value % (hi - lo).toULong()).toInt()
            for ((a, b) in free) {
                if (index < b - a) {
                    out += a + index
                    break
                }
                index -= b - a
            }
        }
        return out.sorted().map { LocalTime.of(it / 60, it % 60) }
    }
}
