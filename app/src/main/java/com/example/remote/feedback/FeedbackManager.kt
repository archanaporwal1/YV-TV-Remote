package com.example.remote.feedback

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.example.remote.data.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

enum class FeedbackCue {
  NAV_TICK,
  CONFIRM_CHIME,
  VOLUME_STEP,
  BACK_HOME,
  POWER_OR_APP,
}

class FeedbackManager(context: Context) {

  private val appContext = context.applicationContext ?: context
  private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val isReleased = AtomicBoolean(false)

  private val vibrator: Vibrator? =
    runCatching {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val manager = appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        manager?.defaultVibrator
      } else {
        @Suppress("DEPRECATION")
        appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
      }
    }.getOrNull()

  private val soundPool: SoundPool? =
    runCatching {
      SoundPool.Builder()
        .setMaxStreams(4)
        .setAudioAttributes(
          AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        )
        .build()
    }.getOrNull()

  private val soundIds = ConcurrentHashMap<FeedbackCue, Int>()

  init {
    ioScope.launch {
      loadSynthesizedSounds()
    }
  }

  private fun loadSynthesizedSounds() {
    val pool = soundPool ?: return
    if (isReleased.get()) return
    runCatching {
      val soundDir = File(appContext.cacheDir, "remote_sfx").apply { mkdirs() }
      val specs =
        mapOf(
          FeedbackCue.NAV_TICK to
            generateToneWav(durationMs = 24, startFreqHz = 1150.0, endFreqHz = 680.0, decay = 18.0, secondHarmonic = 0.15),
          FeedbackCue.CONFIRM_CHIME to
            generateToneWav(durationMs = 46, startFreqHz = 880.0, endFreqHz = 1320.0, decay = 10.0, secondHarmonic = 0.35),
          FeedbackCue.VOLUME_STEP to
            generateToneWav(durationMs = 20, startFreqHz = 640.0, endFreqHz = 640.0, decay = 16.0, secondHarmonic = 0.1),
          FeedbackCue.BACK_HOME to
            generateToneWav(durationMs = 36, startFreqHz = 780.0, endFreqHz = 490.0, decay = 13.0, secondHarmonic = 0.2),
          FeedbackCue.POWER_OR_APP to
            generateToneWav(durationMs = 64, startFreqHz = 523.25, endFreqHz = 1046.5, decay = 8.0, secondHarmonic = 0.3),
        )

      specs.forEach { (cue, wavBytes) ->
        if (isReleased.get()) return@forEach
        runCatching {
          val file = File(soundDir, "${cue.name.lowercase()}.wav")
          file.writeBytes(wavBytes)
          val id = pool.load(file.absolutePath, 1)
          if (id > 0) {
            soundIds[cue] = id
          }
        }
      }
    }
  }

  fun trigger(cue: FeedbackCue, settings: AppSettings) {
    if (isReleased.get()) return
    if (settings.hapticsEnabled) {
      triggerVibration(cue)
    }
    if (settings.soundEnabled && settings.soundVolume > 0.01f) {
      val pool = soundPool ?: return
      val id = soundIds[cue]
      if (id != null && id > 0) {
        val vol = settings.soundVolume.coerceIn(0f, 1f)
        runCatching { pool.play(id, vol, vol, 1, 0, 1.0f) }
      }
    }
  }

  private fun triggerVibration(cue: FeedbackCue) {
    val vib = vibrator ?: return
    try {
      if (!vib.hasVibrator()) return
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val effectId =
          when (cue) {
            FeedbackCue.NAV_TICK, FeedbackCue.VOLUME_STEP -> VibrationEffect.EFFECT_TICK
            FeedbackCue.CONFIRM_CHIME, FeedbackCue.BACK_HOME -> VibrationEffect.EFFECT_CLICK
            FeedbackCue.POWER_OR_APP -> VibrationEffect.EFFECT_HEAVY_CLICK
          }
        vib.vibrate(VibrationEffect.createPredefined(effectId))
      } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val (duration, amplitude) =
          when (cue) {
            FeedbackCue.NAV_TICK -> 14L to 70
            FeedbackCue.VOLUME_STEP -> 12L to 60
            FeedbackCue.BACK_HOME -> 18L to 90
            FeedbackCue.CONFIRM_CHIME -> 24L to 120
            FeedbackCue.POWER_OR_APP -> 34L to 160
          }
        vib.vibrate(VibrationEffect.createOneShot(duration, amplitude))
      } else {
        @Suppress("DEPRECATION")
        vib.vibrate(if (cue == FeedbackCue.POWER_OR_APP) 30L else 15L)
      }
    } catch (_: Throwable) {
    }
  }

  fun release() {
    isReleased.set(true)
    runCatching { ioScope.cancel() }
    runCatching { soundPool?.release() }
  }

  private fun generateToneWav(
    durationMs: Int,
    startFreqHz: Double,
    endFreqHz: Double,
    decay: Double,
    secondHarmonic: Double,
  ): ByteArray {
    val sampleRate = 22050
    val numSamples = (sampleRate * durationMs) / 1000
    val pcm = ByteArray(numSamples * 2)
    val buffer = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)

    var phase = 0.0
    for (i in 0 until numSamples) {
      val t = i.toDouble() / numSamples.toDouble()
      val freq = startFreqHz + (endFreqHz - startFreqHz) * t
      phase += 2.0 * PI * freq / sampleRate
      val envelope =
        (if (i < 40) i / 40.0 else 1.0) * exp(-decay * t)
      val wave = sin(phase) + secondHarmonic * sin(phase * 2.0)
      val sample = (wave * envelope * 0.45 * Short.MAX_VALUE).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
      buffer.putShort(sample.toShort())
    }

    val out = ByteArrayOutputStream(44 + pcm.size)
    fun writeIntLE(v: Int) {
      out.write(v and 0xFF)
      out.write((v shr 8) and 0xFF)
      out.write((v shr 16) and 0xFF)
      out.write((v shr 24) and 0xFF)
    }
    fun writeShortLE(v: Int) {
      out.write(v and 0xFF)
      out.write((v shr 8) and 0xFF)
    }

    out.write("RIFF".toByteArray(Charsets.US_ASCII))
    writeIntLE(36 + pcm.size)
    out.write("WAVE".toByteArray(Charsets.US_ASCII))
    out.write("fmt ".toByteArray(Charsets.US_ASCII))
    writeIntLE(16) // PCM chunk size
    writeShortLE(1) // PCM format
    writeShortLE(1) // Mono
    writeIntLE(sampleRate)
    writeIntLE(sampleRate * 2)
    writeShortLE(2)
    writeShortLE(16)
    out.write("data".toByteArray(Charsets.US_ASCII))
    writeIntLE(pcm.size)
    out.write(pcm)
    return out.toByteArray()
  }
}
