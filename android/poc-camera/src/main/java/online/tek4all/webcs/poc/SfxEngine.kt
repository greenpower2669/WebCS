package online.tek4all.webcs.poc

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.SoundPool
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

class SfxEngine(context: Context) {
    @Volatile private var enabled = true
    @Volatile private var volume = 0.70f
    private val executor = Executors.newFixedThreadPool(4)
    private val sampleRate = 22050
    private val readySamples = ConcurrentHashMap.newKeySet<Int>()
    private val appContext = context.applicationContext

    private val soundPool = SoundPool.Builder()
        .setMaxStreams(6)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()

    private val shotSampleId: Int
    private val reloadSampleId: Int

    private val shotFallback by lazy { buildShot() }
    private val empty by lazy { buildEmptyClick() }
    private val endOfMagazine by lazy { buildEndOfMagazine() }
    private val reloadFallback by lazy { buildReload() }
    private val hitReward by lazy { buildHitReward() }

    init {
        soundPool.setOnLoadCompleteListener { _, sampleId, status ->
            if (status == 0) readySamples.add(sampleId)
        }
        shotSampleId = soundPool.load(appContext, R.raw.tir, 1)
        reloadSampleId = soundPool.load(appContext, R.raw.reload, 1)
    }

    fun configure(isEnabled: Boolean, volumePercent: Int) {
        enabled = isEnabled
        volume = (volumePercent.coerceIn(0, 100) / 100f)
    }

    fun playShot() = playSampleOrFallback(shotSampleId, shotFallback)
    fun playEmpty() = play(empty)
    fun playEndOfMagazine() = play(endOfMagazine)
    fun playReload() = playSampleOrFallback(reloadSampleId, reloadFallback)

    /** Petit retour positif distinct du PAN, joué seulement sur un TOUCHÉ. */
    fun playHitReward() = play(hitReward, delayMs = 95L)

    fun release() {
        executor.shutdownNow()
        try { soundPool.release() } catch (_: Exception) { }
    }

    private fun playSampleOrFallback(sampleId: Int, fallback: ShortArray) {
        if (!enabled || volume <= 0f) return
        val v = volume
        val streamId = if (readySamples.contains(sampleId)) {
            try { soundPool.play(sampleId, v, v, 1, 0, 1f) } catch (_: Exception) { 0 }
        } else 0
        if (streamId == 0) play(fallback)
    }

    private fun play(samples: ShortArray, delayMs: Long = 0L) {
        if (!enabled || volume <= 0f) return
        val playVolume = volume
        executor.execute {
            var track: AudioTrack? = null
            try {
                if (delayMs > 0L) Thread.sleep(delayMs)
                val bytes = ByteArray(samples.size * 2)
                var p = 0
                for (sample in samples) {
                    val value = sample.toInt()
                    bytes[p++] = (value and 0xff).toByte()
                    bytes[p++] = ((value shr 8) and 0xff).toByte()
                }
                track = AudioTrack.Builder()
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
                    .setBufferSizeInBytes(bytes.size)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
                track.write(bytes, 0, bytes.size)
                track.setVolume(playVolume)
                track.play()
                Thread.sleep(samples.size * 1000L / sampleRate + 60L)
            } catch (_: Exception) {
                // Les SFX ne doivent jamais interrompre une partie ou la caméra.
            } finally {
                try { track?.stop() } catch (_: Exception) { }
                try { track?.release() } catch (_: Exception) { }
            }
        }
    }

    private fun buildHitReward(): ShortArray {
        val n = (sampleRate * 0.24).toInt()
        return ShortArray(n) { i ->
            val t = i.toDouble() / sampleRate
            val first = sin(2.0 * PI * 1180.0 * t) * exp(-t * 20.0)
            val t2 = (t - 0.075).coerceAtLeast(0.0)
            val second = if (t >= 0.075) sin(2.0 * PI * 1760.0 * t2) * exp(-t2 * 18.0) else 0.0
            ((0.60 * first + 0.78 * second) * 15000).toInt().coerceIn(-32767, 32767).toShort()
        }
    }

    private fun buildShot(): ShortArray {
        val n = (sampleRate * 0.18).toInt()
        val out = ShortArray(n)
        var seed = 0x13579BDF
        for (i in 0 until n) {
            seed = seed * 1103515245 + 12345
            val noise = (((seed ushr 16) and 0x7fff) / 16384.0 - 1.0)
            val t = i.toDouble() / sampleRate
            val envelope = exp(-t * 24.0)
            val thump = sin(2.0 * PI * 105.0 * t) * exp(-t * 18.0)
            val crack = noise * envelope
            out[i] = ((0.72 * crack + 0.42 * thump).coerceIn(-1.0, 1.0) * 30000).toInt().toShort()
        }
        return out
    }

    private fun buildEmptyClick(): ShortArray {
        val n = (sampleRate * 0.10).toInt()
        return ShortArray(n) { i ->
            val t = i.toDouble() / sampleRate
            val env = exp(-t * 55.0)
            val tone = sin(2.0 * PI * 1650.0 * t) + 0.45 * sin(2.0 * PI * 2850.0 * t)
            (tone * env * 11500).toInt().coerceIn(-32767, 32767).toShort()
        }
    }

    private fun buildEndOfMagazine(): ShortArray {
        val n = (sampleRate * 0.34).toInt()
        return ShortArray(n) { i ->
            val t = i.toDouble() / sampleRate
            val a = if (t < 0.11) sin(2.0 * PI * 1850.0 * t) * exp(-t * 30.0) else 0.0
            val t2 = (t - 0.14).coerceAtLeast(0.0)
            val b = if (t >= 0.14) sin(2.0 * PI * 900.0 * t2) * exp(-t2 * 18.0) else 0.0
            ((a + 0.8 * b) * 12500).toInt().coerceIn(-32767, 32767).toShort()
        }
    }

    private fun buildReload(): ShortArray {
        val n = (sampleRate * 1.25).toInt()
        val out = ShortArray(n)
        fun addClick(at: Double, frequency: Double, amplitude: Double, decay: Double) {
            val start = (at * sampleRate).toInt()
            val length = (0.16 * sampleRate).toInt()
            for (j in 0 until length) {
                val index = start + j
                if (index !in out.indices) break
                val t = j.toDouble() / sampleRate
                val sample = sin(2.0 * PI * frequency * t) * exp(-t * decay) * amplitude
                val mixed = out[index].toInt() + (sample * 22000).toInt()
                out[index] = mixed.coerceIn(-32767, 32767).toShort()
            }
        }
        addClick(0.02, 780.0, 0.75, 30.0)
        addClick(0.28, 420.0, 0.95, 18.0)
        addClick(0.62, 980.0, 0.72, 26.0)
        addClick(0.92, 1450.0, 0.88, 25.0)
        addClick(1.08, 620.0, 0.62, 22.0)
        return out
    }
}
