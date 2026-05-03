package com.rittme.theofficer.audio

import android.media.AudioManager
import android.media.audiofx.DynamicsProcessing
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi

enum class NightAudioStrength {
    MILD,
    MEDIUM,
    STRONG;

    companion object {
        fun fromName(name: String?): NightAudioStrength =
            entries.firstOrNull { it.name == name } ?: MEDIUM
    }
}

private data class CompressorPreset(
    val thresholdDb: Float,
    val ratio: Float,
    val makeupGainDb: Float,
    val attackMs: Float,
    val releaseMs: Float
)

private val PRESETS = mapOf(
    NightAudioStrength.MILD to CompressorPreset(-18f, 3f, 3f, 5f, 80f),
    NightAudioStrength.MEDIUM to CompressorPreset(-24f, 4f, 6f, 5f, 80f),
    NightAudioStrength.STRONG to CompressorPreset(-30f, 8f, 9f, 5f, 80f)
)

/**
 * Wraps an [android.media.audiofx.DynamicsProcessing] effect attached to ExoPlayer's
 * audio session. Implements a single-band compressor + brick-wall limiter so loud
 * peaks are tamed and quiet dialogue is lifted — i.e. "night mode" for audio.
 */
class NightAudioController {

    private var effect: DynamicsProcessing? = null
    private var enabled = false
    private var strength = NightAudioStrength.MEDIUM
    private var sessionId: Int = AudioManager.ERROR

    val isSupported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P

    fun setEnabled(on: Boolean) {
        if (enabled == on) return
        enabled = on
        rebuild()
    }

    fun setStrength(value: NightAudioStrength) {
        if (strength == value) return
        strength = value
        rebuild()
    }

    fun onAudioSessionId(id: Int) {
        if (sessionId == id) return
        sessionId = id
        rebuild()
    }

    fun release() {
        runCatching { effect?.release() }
        effect = null
    }

    private fun rebuild() {
        runCatching { effect?.release() }
        effect = null
        if (!isSupported) return
        if (!enabled) return
        if (sessionId == AudioManager.ERROR || sessionId == 0) return
        try {
            effect = build(sessionId, PRESETS.getValue(strength))
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to attach DynamicsProcessing effect", t)
            effect = null
        }
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun build(audioSessionId: Int, preset: CompressorPreset): DynamicsProcessing {
        val channelCount = 2
        val config = DynamicsProcessing.Config.Builder(
            DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
            channelCount,
            /* preEqInUse */ false, /* preEqBandCount */ 0,
            /* mbcInUse */ true, /* mbcBandCount */ 1,
            /* postEqInUse */ false, /* postEqBandCount */ 0,
            /* limiterInUse */ true
        ).build()

        val dp = DynamicsProcessing(0, audioSessionId, config)

        for (channel in 0 until channelCount) {
            val band = DynamicsProcessing.MbcBand(
                /* enabled */ true,
                /* cutoffFrequency */ 20_000f,
                /* attackTime */ preset.attackMs,
                /* releaseTime */ preset.releaseMs,
                /* ratio */ preset.ratio,
                /* threshold */ preset.thresholdDb,
                /* kneeWidth */ 6f,
                /* noiseGateThreshold */ -100f,
                /* expanderRatio */ 1f,
                /* preGain */ 0f,
                /* postGain */ preset.makeupGainDb
            )
            dp.setMbcBandByChannelIndex(channel, 0, band)

            val limiter = DynamicsProcessing.Limiter(
                /* inUse */ true,
                /* enabled */ true,
                /* linkGroup */ 0,
                /* attackTime */ 1f,
                /* releaseTime */ 50f,
                /* ratio */ 8f,
                /* threshold */ -1f,
                /* postGain */ 0f
            )
            dp.setLimiterByChannelIndex(channel, limiter)
        }

        dp.setEnabled(true)
        return dp
    }

    companion object {
        private const val TAG = "NightAudioController"
    }
}
