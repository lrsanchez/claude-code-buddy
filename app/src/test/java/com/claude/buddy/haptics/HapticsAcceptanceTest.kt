package com.claude.buddy.haptics

import com.claude.buddy.haptics.HapticEvent
import com.claude.buddy.haptics.HapticPattern
import com.claude.buddy.haptics.HapticSink
import com.claude.buddy.haptics.HapticsController
import com.claude.buddy.haptics.HapticPatterns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Acceptance tests for APP-003 — Haptic patterns for approval / deny / level-up.
 *
 * AC5's flag-clear / once-per-level behaviour and AC7's Activity wiring and the
 * AndroidManifest PERMISSION live in Android framework classes (Activity /
 * ViewModel / manifest) that cannot be driven on a plain JVM. Those are verified
 * by source inspection and reported separately.
 */

private class FakeSink : HapticSink {
    val played = mutableListOf<HapticPattern>()
    var result = true
    override fun play(pattern: HapticPattern): Boolean {
        played.add(pattern)
        return result
    }
}

class HapticsAcceptanceTest {

    // ── AC1 ──────────────────────────────────────────────────────────────────
    // Every named event resolves to a non-empty, well-formed waveform: pulses
    // non-empty, timings in (0, 1000] ms, amplitudes in 1..255, total duration
    // <= 2000 ms, and no repeat index (played once).
    @Test
    fun ac1_allWaveformsAreWellFormed() {
        for (event in HapticEvent.entries) {
            val p = HapticPatterns.forEvent(event)
            assertTrue("${event.name} waveform must be non-empty", p.pulses.isNotEmpty())
            for (pulse in p.pulses) {
                assertTrue("${event.name} pulse duration must be > 0, was ${pulse.durationMs}", pulse.durationMs > 0)
                assertTrue("${event.name} pulse duration must be <= 1000, was ${pulse.durationMs}", pulse.durationMs <= 1000)
                assertTrue("${event.name} amplitude must be >= 1, was ${pulse.amplitude}", pulse.amplitude >= 1)
                assertTrue("${event.name} amplitude must be <= 255, was ${pulse.amplitude}", pulse.amplitude <= 255)
                assertTrue("${event.name} gap must be >= 0, was ${pulse.gapAfterMs}", pulse.gapAfterMs >= 0)
            }
            assertTrue(
                "${event.name} total duration must be <= 2000, was ${p.totalDurationMs}",
                p.totalDurationMs <= 2000
            )
            // Contract: waveform plays once (no repeat index). HapticPattern
            // carries no repeat field.
            assertFalse("pattern must not expose a repeat index", hasRepeat(p))
        }
    }

    // ── AC2 ──────────────────────────────────────────────────────────────────
    // The three waveforms are pairwise distinct: APPROVE has exactly 2 pulses,
    // DENY exactly 1 pulse longer than a single APPROVE pulse, LEVEL_UP at
    // least 3 pulses.
    @Test
    fun ac2_waveformsAreDistinctWithRequiredPulseCountsAndDenyLongerThanApprovePulse() {
        val approve = HapticPatterns.forEvent(HapticEvent.APPROVE)
        val deny = HapticPatterns.forEvent(HapticEvent.DENY)
        val levelUp = HapticPatterns.forEvent(HapticEvent.LEVEL_UP)

        assertNotEquals("APPROVE and DENY must differ", approve.pulses, deny.pulses)
        assertNotEquals("APPROVE and LEVEL_UP must differ", approve.pulses, levelUp.pulses)
        assertNotEquals("DENY and LEVEL_UP must differ", deny.pulses, levelUp.pulses)

        assertEquals("APPROVE must have exactly 2 pulses", 2, approve.pulses.size)
        assertEquals("DENY must have exactly 1 pulse", 1, deny.pulses.size)
        assertTrue("LEVEL_UP must have at least 3 pulses, had ${levelUp.pulses.size}", levelUp.pulses.size >= 3)

        // DENY's single pulse is longer than each APPROVE pulse.
        val denyPulse = deny.pulses.single()
        for (ap in approve.pulses) {
            assertTrue(
                "DENY pulse (${denyPulse.durationMs}) must be longer than APPROVE pulse (${ap.durationMs})",
                denyPulse.durationMs > ap.durationMs
            )
        }
    }

    // ── AC3 ──────────────────────────────────────────────────────────────────
    // Controller wired to a recording fake sink: approve event plays exactly
    // one waveform equal to the APPROVE pattern.
    @Test
    fun ac3_approveEventRecordsExactlyTheApprovePattern() {
        val sink = FakeSink()
        val controller = HapticsController(sink)

        val result = controller.play(HapticEvent.APPROVE)

        assertEquals("approve must return true on a working sink", true, result)
        assertEquals("exactly one waveform recorded", 1, sink.played.size)
        assertEquals("recorded waveform equals APPROVE pattern", HapticPatterns.forEvent(HapticEvent.APPROVE), sink.played.single())
    }

    // ── AC4 ──────────────────────────────────────────────────────────────────
    // Same for the deny event — exactly one waveform equal to the DENY pattern.
    @Test
    fun ac4_denyEventRecordsExactlyTheDenyPattern() {
        val sink = FakeSink()
        val controller = HapticsController(sink)

        val result = controller.play(HapticEvent.DENY)

        assertEquals("deny must return true on a working sink", true, result)
        assertEquals("exactly one waveform recorded", 1, sink.played.size)
        assertEquals("recorded waveform equals DENY pattern", HapticPatterns.forEvent(HapticEvent.DENY), sink.played.single())
    }

    // ── AC5 (pure-Kotlin part) ───────────────────────────────────────────────
    // LEVEL_UP triggers exactly one LEVEL_UP waveform. The flag-clear and
    // once-per-level firing live in app state owned by the Activity/ViewModel,
    // which cannot be driven on a plain JVM — covered by source inspection.
    @Test
    fun ac5_levelUpEventRecordsExactlyTheLevelUpPattern() {
        val sink = FakeSink()
        val controller = HapticsController(sink)

        val result = controller.play(HapticEvent.LEVEL_UP)

        assertEquals("level-up must return true on a working sink", true, result)
        assertEquals("exactly one waveform recorded", 1, sink.played.size)
        assertEquals("recorded waveform equals LEVEL_UP pattern", HapticPatterns.forEvent(HapticEvent.LEVEL_UP), sink.played.single())
    }

    // ── AC6 ──────────────────────────────────────────────────────────────────
    // No vibration hardware: sink is absent (null controller) -> play returns
    // false, records nothing, throws no exception. Also a sink that reports it
    // played nothing.
    @Test
    fun ac6_noSinkOrUnavailableSinkReturnsFalseAndRecordsNothingWithoutThrowing() {
        // Null sink (no motor).
        val noSink = HapticsController(null)
        for (event in HapticEvent.entries) {
            var thrown: Throwable? = null
            val result = try {
                noSink.play(event)
            } catch (t: Throwable) {
                thrown = t
                false
            }
            assertTrue("play with null sink for $event must not throw", thrown == null)
            assertFalse("play with null sink for $event must return false, was $result", result)
        }

        // Sink present but reports it could not play (e.g. no motor on device).
        val unavailSink = FakeSink().apply { result = false }
        val controller = HapticsController(unavailSink)
        for (event in HapticEvent.entries) {
            var thrown: Throwable? = null
            val result = try {
                controller.play(event)
            } catch (t: Throwable) {
                thrown = t
                false
            }
            assertTrue("play with unavailable sink for $event must not throw", thrown == null)
            assertFalse("play with unavailable sink for $event must return false, was $result", result)
        }
    }

    // ── AC7 (pure-Kotlin part) ───────────────────────────────────────────────
    // Each event routes exactly through forEvent into the sink, which the
    // controller exercises in AC3/AC4/AC5. The Activity wiring (approve/deny
    // tap, auto-approve no-haptic, level-up once) and the AndroidManifest.xml
    // android.permission.VIBRATE declaration require source inspection.
    @Test
    fun ac7_controllerRoutesEachEventThroughForEvent() {
        val sink = FakeSink()
        val controller = HapticsController(sink)

        controller.play(HapticEvent.APPROVE)
        controller.play(HapticEvent.DENY)

        assertEquals("exactly two waveforms recorded", 2, sink.played.size)
        assertEquals("first is APPROVE pattern", HapticPatterns.forEvent(HapticEvent.APPROVE), sink.played[0])
        assertEquals("second is DENY pattern", HapticPatterns.forEvent(HapticEvent.DENY), sink.played[1])
    }

    // ── helpers ──────────────────────────────────────────────────────────────
    private fun hasRepeat(p: HapticPattern): Boolean {
        // Contract's HapticPattern has no repeat field. Guard against a
        // repeat property arriving anyway (e.g. from a library-type morph).
        return try {
            val f = p.javaClass.getDeclaredField("repeat")
            f.isAccessible = true
            val v = f.get(p)
            v != 0
        } catch (e: NoSuchFieldException) {
            false
        } catch (e: IllegalAccessException) {
            false
        }
    }
}
