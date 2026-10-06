package com.claude.buddy.haptics

import org.junit.Test
import org.junit.Assert

// ── Haptic tests (pure JVM, no Android framework) ─────────────────────────────

class HapticsTest {

    // ── Pattern well-formedness (AC1) ─────────────────────────────────────────

    @Test
    fun everyEventMapsToWellFormedPattern() {
        HapticEvent.values().forEach { event ->
            val pattern = HapticPatterns.forEvent(event)
            Assert.assertTrue("pulses not empty for $event", pattern.pulses.isNotEmpty())
            Assert.assertTrue(
                "totalDurationMs ${pattern.totalDurationMs} > 2000 for $event",
                pattern.totalDurationMs <= 2000,
            )
            pattern.pulses.forEach { pulse ->
                Assert.assertTrue("durationMs ${pulse.durationMs} out of range for $event", pulse.durationMs > 0 && pulse.durationMs <= 1000)
                Assert.assertTrue("amplitude ${pulse.amplitude} out of range for $event", pulse.amplitude >= 1 && pulse.amplitude <= 255)
            }
        }
    }

    // ── Patterns are distinct, correct pulse counts (AC2) ─────────────────────

    @Test
    fun patternsArePairwiseDistinct() {
        val approve = HapticPatterns.forEvent(HapticEvent.APPROVE).pulses
        val deny    = HapticPatterns.forEvent(HapticEvent.DENY).pulses
        val levelUp = HapticPatterns.forEvent(HapticEvent.LEVEL_UP).pulses
        Assert.assertNotEquals("approve and deny must differ", approve, deny)
        Assert.assertNotEquals("approve and level-up must differ", approve, levelUp)
        Assert.assertNotEquals("deny and level-up must differ", deny, levelUp)
    }

    @Test
    fun pulseCounts() {
        Assert.assertEquals("APPROVE has 2 pulses", 2, HapticPatterns.forEvent(HapticEvent.APPROVE).pulses.size)
        Assert.assertEquals("DENY has 1 pulse", 1, HapticPatterns.forEvent(HapticEvent.DENY).pulses.size)
        Assert.assertTrue("LEVEL_UP has >= 3 pulses", HapticPatterns.forEvent(HapticEvent.LEVEL_UP).pulses.size >= 3)
    }

    @Test
    fun denyPulseLongerThanAnApprovePulse() {
        val denyPulse = HapticPatterns.forEvent(HapticEvent.DENY).pulses[0].durationMs
        val approveDurations = HapticPatterns.forEvent(HapticEvent.APPROVE).pulses.map { it.durationMs }
        Assert.assertTrue("DENY pulse longer than every APPROVE pulse", denyPulse > approveDurations.max())
    }

    // ── Controller / sink behaviour (AC3, AC4, AC5, AC6) ──────────────────────

    @Test
    fun sinkRecordsExactlyOneApprovePattern() {
        val fake = FakeSink()
        val c = HapticsController(fake)
        Assert.assertTrue(c.play(HapticEvent.APPROVE))
        Assert.assertEquals("one pattern recorded", 1, fake.played.size)
        Assert.assertEquals(HapticEvent.APPROVE, fake.played[0].event)
        val expected = HapticPatterns.forEvent(HapticEvent.APPROVE)
        Assert.assertEquals(expected.event,  fake.played[0].event)
        Assert.assertEquals(expected.pulses, fake.played[0].pulses)
    }

    @Test
    fun sinkRecordsExactlyOneDenyPattern() {
        val fake = FakeSink()
        val c = HapticsController(fake)
        Assert.assertTrue(c.play(HapticEvent.DENY))
        Assert.assertEquals("one pattern recorded", 1, fake.played.size)
        Assert.assertEquals(HapticEvent.DENY, fake.played[0].event)
        val expected = HapticPatterns.forEvent(HapticEvent.DENY)
        Assert.assertEquals(expected.event,  fake.played[0].event)
        Assert.assertEquals(expected.pulses, fake.played[0].pulses)
    }

    @Test
    fun levelUpRecordsOneWaveform() {
        val fake = FakeSink()
        val c = HapticsController(fake)
        Assert.assertTrue(c.play(HapticEvent.LEVEL_UP))
        Assert.assertEquals("one pattern recorded", 1, fake.played.size)
        Assert.assertEquals(HapticEvent.LEVEL_UP, fake.played[0].event)
        val expected = HapticPatterns.forEvent(HapticEvent.LEVEL_UP)
        Assert.assertEquals(expected.event,  fake.played[0].event)
        Assert.assertEquals(expected.pulses, fake.played[0].pulses)
    }

    @Test
    fun nullSinkReturnsFalseAndThrowsNothing() {
        val c = HapticsController(null)
        Assert.assertFalse(c.play(HapticEvent.APPROVE))
        Assert.assertFalse(c.play(HapticEvent.DENY))
        Assert.assertFalse(c.play(HapticEvent.LEVEL_UP))
    }

    @Test
    fun sinkReturningFalsePropagatesFalse() {
        val c = HapticsController(FalseSink())
        Assert.assertFalse(c.play(HapticEvent.APPROVE))
        Assert.assertFalse(c.play(HapticEvent.DENY))
        Assert.assertFalse(c.play(HapticEvent.LEVEL_UP))
    }

    // ── Fakes ─────────────────────────────────────────────────────────────────

    class FakeSink : HapticSink {
        val played = mutableListOf<HapticPattern>()
        override fun play(pattern: HapticPattern): Boolean {
            played.add(pattern)
            return true
        }
    }

    class FalseSink : HapticSink {
        override fun play(pattern: HapticPattern): Boolean = false
    }
}
