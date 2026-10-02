package com.xorbi.zeus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The endpointer decides when a dictation stops. Getting it wrong is either
 * "cuts you off mid-sentence" or "waits forever", so the state transitions are
 * pinned down here.
 */
class EndpointerTest {

    private val loud = -12f   // speech
    private val quiet = -55f  // room tone

    private fun speech(startAtMs: Long, untilMs: Long): Endpointer {
        val e = Endpointer(silenceHoldMs = 900L, maxSpeechMs = 15_000L)
        e.onFrame(loud, 100L, startAtMs)
        var t = startAtMs + 100L
        while (t < untilMs) {
            e.onFrame(loud, 100L, t)
            t += 100L
        }
        return e
    }

    @Test
    fun `stays silent until speech is detected`() {
        val e = Endpointer()
        assertEquals(Endpointer.Decision.NONE, e.onFrame(quiet, 100L, 0L))
        assertEquals(Endpointer.Decision.NONE, e.onFrame(quiet, 100L, 100L))
        assertFalse(e.hasSpeech)
    }

    @Test
    fun `fires speech onset exactly once`() {
        val e = Endpointer()
        assertEquals(Endpointer.Decision.SPEECH_STARTED, e.onFrame(loud, 100L, 0L))
        assertEquals(Endpointer.Decision.NONE, e.onFrame(loud, 100L, 100L))
        assertEquals(Endpointer.Decision.NONE, e.onFrame(loud, 100L, 200L))
        assertTrue(e.hasSpeech)
    }

    @Test
    fun `holds through a short gap inside a sentence`() {
        val e = speech(startAtMs = 0L, untilMs = 2_000L)
        // 600 ms of quiet is a pause between words, not the end of the utterance.
        assertEquals(Endpointer.Decision.NONE, e.onFrame(quiet, 100L, 2_000L))
        assertEquals(Endpointer.Decision.NONE, e.onFrame(quiet, 100L, 2_500L))
        // Speaking again resets the silence timer.
        assertEquals(Endpointer.Decision.NONE, e.onFrame(loud, 100L, 2_600L))
        assertTrue(e.hasSpeech)
    }

    @Test
    fun `fires end of speech after the silence hold`() {
        val e = speech(startAtMs = 0L, untilMs = 2_000L)
        assertEquals(Endpointer.Decision.NONE, e.onFrame(quiet, 100L, 2_000L))
        assertEquals(Endpointer.Decision.NONE, e.onFrame(quiet, 100L, 2_400L))
        assertEquals(Endpointer.Decision.END_OF_SPEECH, e.onFrame(quiet, 100L, 2_900L))
    }

    @Test
    fun `caps utterance length`() {
        val e = Endpointer(silenceHoldMs = 900L, maxSpeechMs = 3_000L)
        var t = 0L
        assertEquals(Endpointer.Decision.SPEECH_STARTED, e.onFrame(loud, 100L, t))
        var decision = Endpointer.Decision.NONE
        while (t < 5_000L) {
            decision = e.onFrame(loud, 100L, t)
            t += 100L
        }
        assertEquals(Endpointer.Decision.END_OF_SPEECH, decision)
    }

    @Test
    fun `discards a burst too short to be speech`() {
        val e = Endpointer(minSpeechMs = 350L)
        e.onFrame(loud, 100L, 0L)
        e.onFrame(loud, 100L, 100L)
        assertFalse(e.endedWorthReporting())

        val longer = speech(startAtMs = 0L, untilMs = 1_000L)
        assertTrue(longer.endedWorthReporting())
    }

    @Test
    fun `reset clears everything`() {
        val e = speech(startAtMs = 0L, untilMs = 1_000L)
        e.reset()
        assertFalse(e.hasSpeech)
        assertEquals(0L, e.activeDurationMs)
        assertEquals(Endpointer.Decision.NONE, e.onFrame(quiet, 100L, 2_000L))
    }
}

class RmsTest {

    @Test
    fun `digital silence reports the floor rather than negative infinity`() {
        val silence = ShortArray(1024)
        assertEquals(rmsDbfs(silence, silence.size), SILENCE_FLOOR_DB, 0.001f)
    }

    @Test
    fun `empty input is treated as silence`() {
        assertEquals(rmsDbfs(ShortArray(0), 0), SILENCE_FLOOR_DB, 0.001f)
    }

    @Test
    fun `full scale reads zero dbfs`() {
        val full = ShortArray(1024) { 32767 }
        assertEquals(rmsDbfs(full, full.size), 0f, 0.01f)
    }

    @Test
    fun `half scale is about minus six db`() {
        val half = ShortArray(1024) { 16384 }
        val db = rmsDbfs(half, half.size)
        // log10(16384/32768) * 20 = -6.02 dB
        assertTrue("expected about -6 dB, got $db", db > -6.5f && db < -5.5f)
    }

    @Test
    fun `thresholds are ordered so a quiet room cannot be mistaken for speech`() {
        assertTrue(Endpointer.SPEECH_THRESHOLD_DB > Endpointer.SILENCE_THRESHOLD_DB)
    }
}

class ResamplerTest {

    @Test
    fun `drops the volume to match the new sample count`() {
        // 48000 -> 16000 is exactly 3:1.
        val input = ShortArray(480) { 1000 }
        val out = ShortArray(200)
        val written = Resampler(48_000, 16_000).resample(input, input.size, out)
        assertEquals(160, written)
        assertEquals(1000, out[0].toInt())
    }

    @Test
    fun `interpolates for ratios that are not whole numbers`() {
        // 44100 -> 16000 is 2.75625: fractional steps must be preserved.
        val input = ShortArray(200) { i -> (i * 10).toShort() }
        val out = ShortArray(100)
        val written = Resampler(44_100, 16_000).resample(input, input.size, out)
        assertTrue("expected roughly 72 samples, got $written", written in 60..80)
        // Monotone input must produce a monotone output.
        for (i in 1 until written) {
            assertTrue("output went backwards at $i", out[i] >= out[i - 1])
        }
    }

    @Test
    fun `refuses a buffer too short to interpolate`() {
        val out = ShortArray(10)
        assertEquals(0, Resampler(48_000, 16_000).resample(ShortArray(1), 1, out))
    }

    @Test
    fun `never writes past the output buffer`() {
        val input = ShortArray(1000) { 500 }
        val out = ShortArray(10)
        val written = Resampler(48_000, 16_000).resample(input, input.size, out)
        assertTrue(written <= out.size)
    }
}