package com.claude.buddy.haptics

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

// ── Real vibrator sink ────────────────────────────────────────────────────────

class VibratorHapticSink(private val context: Context) : HapticSink {

    // VibratorManager only exists from API 31; minSdk is 26, so fall back to
    // the deprecated system service on older devices.
    private val vibrator: Vibrator?
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    override fun play(pattern: HapticPattern): Boolean {
        val vibe = vibrator ?: return false
        if (!vibe.hasVibrator()) return false

        // Convert pulses to an alternating on/off waveform: each pulse's
        // duration with its amplitude, then its gap with amplitude 0.
        val timings   = mutableListOf<Long>()
        val amplitudes = mutableListOf<Int>()
        pattern.pulses.forEach { p ->
            timings.add(p.durationMs)
            amplitudes.add(p.amplitude)
            if (p.gapAfterMs > 0) {
                timings.add(p.gapAfterMs)
                amplitudes.add(0)
            }
        }

        val effect = VibrationEffect.createWaveform(
            timings.toLongArray(),
            amplitudes.toIntArray(),
            -1,  // no repeat
        )
        vibe.vibrate(effect)
        return true
    }
}
