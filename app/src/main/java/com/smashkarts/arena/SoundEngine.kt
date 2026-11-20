package com.smashkarts.arena

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * Zero-asset procedural Android AudioTrack synthesizer for rockets, pickups, boosts, and explosions.
 */
class SoundEngine {
    var muted: Boolean = false
    private val sampleRate = 22050

    fun playTone(startHz: Double, endHz: Double, durationMs: Int, volume: Double = 0.35) {
        if (muted) return
        thread(isDaemon = true) {
            try {
                val count = (sampleRate * durationMs) / 1000
                val pcm = ShortArray(count)
                var phase = 0.0
                for (i in 0 until count) {
                    val t = i.toDouble() / count
                    val freq = startHz + (endHz - startHz) * t
                    phase += 2.0 * PI * freq / sampleRate
                    val env = (1.0 - t) * volume
                    pcm[i] = (sin(phase) * env * Short.MAX_VALUE).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                }
                playBuffer(pcm)
            } catch (_: Exception) {}
        }
    }

    fun playExplosion(big: Boolean = false) {
        if (muted) return
        thread(isDaemon = true) {
            try {
                val durMs = if (big) 520 else 280
                val count = (sampleRate * durMs) / 1000
                val pcm = ShortArray(count)
                var lp = 0.0
                for (i in 0 until count) {
                    val t = i.toDouble() / count
                    val noise = Random.nextDouble(-1.0, 1.0)
                    lp = lp * 0.85 + noise * 0.15
                    val env = (1.0 - t) * (1.0 - t) * (if (big) 0.75 else 0.5)
                    pcm[i] = (lp * env * Short.MAX_VALUE).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                }
                playBuffer(pcm)
            } catch (_: Exception) {}
        }
    }

    fun playPickup() {
        playTone(540.0, 1180.0, 160, 0.32)
    }

    fun playRocket() {
        playTone(360.0, 95.0, 210, 0.35)
    }

    fun playBoost() {
        playTone(260.0, 780.0, 260, 0.3)
    }

    private fun playBuffer(pcm: ShortArray) {
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(pcm.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        track.write(pcm, 0, pcm.size)
        track.play()
        Thread.sleep((pcm.size * 1000L) / sampleRate + 60L)
        track.release()
    }
}
