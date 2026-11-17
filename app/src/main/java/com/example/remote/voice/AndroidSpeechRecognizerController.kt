package com.example.remote.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

class AndroidSpeechRecognizerController(
  context: Context,
  private val onReady: () -> Unit,
  private val onRmsChanged: (Float) -> Unit,
  private val onPartialResult: (String) -> Unit,
  private val onFinalResult: (String) -> Unit,
  private val onError: (String) -> Unit,
) {
  private val appContext = context.applicationContext ?: context
  private val mainHandler = Handler(Looper.getMainLooper())
  private var recognizer: SpeechRecognizer? = null
  private var isCurrentlyListening = false
  @Volatile private var isDestroyed = false
  private val sessionGeneration = AtomicLong(0L)

  fun isAvailable(): Boolean =
    runCatching { SpeechRecognizer.isRecognitionAvailable(appContext) }.getOrDefault(false)

  fun startListening() {
    val requestedGen = sessionGeneration.incrementAndGet()
    isDestroyed = false
    mainHandler.post {
      if (isDestroyed || sessionGeneration.get() != requestedGen) return@post
      try {
        if (!isAvailable()) {
          onError("Speech recognition service is unavailable on this phone.")
          return@post
        }
        stopInternal()
        val sr = SpeechRecognizer.createSpeechRecognizer(appContext)
        if (sr == null) {
          onError("Unable to initialize speech recognizer on this device.")
          return@post
        }
        recognizer = sr
        sr.setRecognitionListener(
          object : RecognitionListener {
            private fun isStale(): Boolean = isDestroyed || sessionGeneration.get() != requestedGen

            override fun onReadyForSpeech(params: Bundle?) {
              if (isStale()) return
              isCurrentlyListening = true
              runCatching { onReady() }
            }

            override fun onBeginningOfSpeech() {}

            override fun onRmsChanged(rmsdB: Float) {
              if (isStale()) return
              val normalized = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
              runCatching { onRmsChanged(normalized) }
            }

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
              if (isStale()) return
              runCatching { onRmsChanged(0f) }
            }

            override fun onError(error: Int) {
              if (isStale()) return
              isCurrentlyListening = false
              runCatching { onRmsChanged(0f) }
              runCatching { onError(mapErrorMessage(error)) }
            }

            override fun onResults(results: Bundle?) {
              if (isStale()) return
              isCurrentlyListening = false
              runCatching { onRmsChanged(0f) }
              val matches =
                runCatching { results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) }
                  .getOrNull()
              val top = matches?.firstOrNull()?.trim().orEmpty()
              if (top.isNotEmpty()) {
                runCatching { onFinalResult(top) }
              } else {
                runCatching { onError("Didn't catch that. Switched to TV Assistant.") }
              }
            }

            override fun onPartialResults(partialResults: Bundle?) {
              if (isStale()) return
              val partial =
                runCatching {
                  partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.trim()
                    .orEmpty()
                }.getOrDefault("")
              if (partial.isNotEmpty()) {
                runCatching { onPartialResult(partial) }
              }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
          }
        )

        val intent =
          Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
          }
        sr.startListening(intent)
      } catch (t: Throwable) {
        isCurrentlyListening = false
        stopInternal()
        runCatching { onError(t.message ?: "Unable to start microphone recognition.") }
      }
    }
  }

  fun stopListening() {
    sessionGeneration.incrementAndGet()
    mainHandler.post {
      if (isCurrentlyListening) {
        runCatching { recognizer?.stopListening() }
      }
      isCurrentlyListening = false
    }
  }

  fun destroy() {
    isDestroyed = true
    sessionGeneration.incrementAndGet()
    mainHandler.post { stopInternal() }
  }

  private fun stopInternal() {
    isCurrentlyListening = false
    val current = recognizer
    recognizer = null
    if (current != null) {
      runCatching { current.cancel() }
      runCatching { current.destroy() }
    }
  }

  companion object {
    fun buildFallbackActivityIntent(): Intent =
      Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak a TV voice command")
      }

    private fun mapErrorMessage(errorCode: Int): String =
      when (errorCode) {
        SpeechRecognizer.ERROR_AUDIO -> "Microphone audio error on phone."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is required."
        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Voice recognition needs network or offline speech pack."
        SpeechRecognizer.ERROR_NO_MATCH -> "No speech recognized."
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech heard."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech recognizer is busy."
        else -> "Voice recognition unavailable (code $errorCode)."
      }
  }
}

class TvVoiceAudioStreamer(
  context: Context,
  private val scope: CoroutineScope,
  private val sendBegin: suspend (Int) -> Boolean,
  private val sendPayload: suspend (ByteArray) -> Boolean,
  private val sendEnd: suspend (Int) -> Boolean,
  private val onStopped: () -> Unit = {},
) {
  private val appContext = context.applicationContext ?: context
  private var streamingJob: Job? = null

  @SuppressLint("MissingPermission")
  fun startStreaming(sessionId: Int) {
    stopStreaming()
    val hasPermission =
      runCatching {
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) ==
          PackageManager.PERMISSION_GRANTED
      }.getOrDefault(false)

    if (!hasPermission) {
      runCatching { onStopped() }
      return
    }

    streamingJob =
      scope.launch(Dispatchers.IO) {
        val sampleRate = 16_000
        val minBuf =
          runCatching {
            AudioRecord.getMinBufferSize(
              sampleRate,
              AudioFormat.CHANNEL_IN_MONO,
              AudioFormat.ENCODING_PCM_16BIT,
            )
          }.getOrDefault(0)
        if (minBuf <= 0) {
          runCatching { onStopped() }
          return@launch
        }
        val bufferSize = maxOf(minBuf, 4096)
        var recorder: AudioRecord? = null
        var didSendBegin = false
        try {
          recorder = createAudioRecordWithFallback(sampleRate, bufferSize)
          if (recorder == null || recorder.state != AudioRecord.STATE_INITIALIZED) {
            return@launch
          }
          if (!sendBegin(sessionId)) {
            return@launch
          }
          didSendBegin = true
          recorder.startRecording()
          val chunk = ByteArray(2048)
          while (isActive) {
            val read = recorder.read(chunk, 0, chunk.size)
            if (read > 0) {
              val ok = sendPayload(chunk.copyOf(read))
              if (!ok) break
            } else if (read < 0) {
              break
            }
          }
        } catch (ce: CancellationException) {
          throw ce
        } catch (_: Throwable) {
        } finally {
          runCatching { recorder?.stop() }
          runCatching { recorder?.release() }
          if (didSendBegin) {
            withContext(NonCancellable) {
              try {
                sendEnd(sessionId)
              } catch (_: Throwable) {
              }
            }
          }
          runCatching { onStopped() }
        }
      }
  }

  @SuppressLint("MissingPermission")
  private fun createAudioRecordWithFallback(sampleRate: Int, bufferSize: Int): AudioRecord? {
    val sources =
      intArrayOf(
        MediaRecorder.AudioSource.VOICE_RECOGNITION,
        MediaRecorder.AudioSource.MIC,
      )
    for (source in sources) {
      val candidate =
        try {
          AudioRecord(
            source,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize,
          )
        } catch (_: Throwable) {
          null
        }
      if (candidate != null) {
        if (candidate.state == AudioRecord.STATE_INITIALIZED) {
          return candidate
        }
        runCatching { candidate.release() }
      }
    }
    return null
  }

  fun stopStreaming() {
    streamingJob?.cancel()
    streamingJob = null
  }
}
