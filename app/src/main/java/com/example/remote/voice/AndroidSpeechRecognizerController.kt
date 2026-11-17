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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

class AndroidSpeechRecognizerController(
  private val context: Context,
  private val onReady: () -> Unit,
  private val onRmsChanged: (Float) -> Unit,
  private val onPartialResult: (String) -> Unit,
  private val onFinalResult: (String) -> Unit,
  private val onError: (String) -> Unit,
) {
  private val mainHandler = Handler(Looper.getMainLooper())
  private var recognizer: SpeechRecognizer? = null
  private var isCurrentlyListening = false

  fun isAvailable(): Boolean =
    runCatching { SpeechRecognizer.isRecognitionAvailable(context) }.getOrDefault(false)

  fun startListening() {
    mainHandler.post {
      if (!isAvailable()) {
        onError("Speech recognition service is not installed on this phone. Use TV Assistant or type a command below.")
        return@post
      }
      stopInternal()
      try {
        val sr = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = sr
        sr.setRecognitionListener(
          object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
              isCurrentlyListening = true
              onReady()
            }

            override fun onBeginningOfSpeech() {}

            override fun onRmsChanged(rmsdB: Float) {
              val normalized = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
              onRmsChanged(normalized)
            }

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
              onRmsChanged(0f)
            }

            override fun onError(error: Int) {
              isCurrentlyListening = false
              onRmsChanged(0f)
              onError(mapErrorMessage(error))
            }

            override fun onResults(results: Bundle?) {
              isCurrentlyListening = false
              onRmsChanged(0f)
              val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
              val top = matches?.firstOrNull()?.trim().orEmpty()
              if (top.isNotEmpty()) {
                onFinalResult(top)
              } else {
                onError("Didn't catch that. Tap the mic to try again.")
              }
            }

            override fun onPartialResults(partialResults: Bundle?) {
              val partial =
                partialResults
                  ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                  ?.firstOrNull()
                  ?.trim()
                  .orEmpty()
              if (partial.isNotEmpty()) {
                onPartialResult(partial)
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
      } catch (e: Exception) {
        isCurrentlyListening = false
        onError(e.message ?: "Unable to start microphone recognition.")
      }
    }
  }

  fun stopListening() {
    mainHandler.post {
      if (isCurrentlyListening) {
        runCatching { recognizer?.stopListening() }
      }
      isCurrentlyListening = false
    }
  }

  fun destroy() {
    mainHandler.post { stopInternal() }
  }

  private fun stopInternal() {
    isCurrentlyListening = false
    runCatching { recognizer?.cancel() }
    runCatching { recognizer?.destroy() }
    recognizer = null
  }

  companion object {
    fun buildFallbackActivityIntent(): Intent =
      Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak a TV voice command")
      }

    private fun mapErrorMessage(errorCode: Int): String =
      when (errorCode) {
        SpeechRecognizer.ERROR_AUDIO -> "Microphone audio error. Please check mic access."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is required for voice commands."
        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Voice recognition needs a network connection or offline speech pack."
        SpeechRecognizer.ERROR_NO_MATCH -> "No speech recognized. Tap the mic and speak clearly."
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech heard. Tap the mic to speak again."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech recognizer is busy. Please try again."
        else -> "Voice recognition stopped (code $errorCode)."
      }
  }
}

class TvVoiceAudioStreamer(
  private val context: Context,
  private val scope: CoroutineScope,
  private val sendBegin: suspend (Int) -> Boolean,
  private val sendPayload: suspend (ByteArray) -> Boolean,
  private val sendEnd: suspend (Int) -> Boolean,
  private val onStopped: () -> Unit = {},
) {
  private var streamingJob: Job? = null

  @SuppressLint("MissingPermission")
  fun startStreaming(sessionId: Int) {
    stopStreaming()
    if (
      ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
      PackageManager.PERMISSION_GRANTED
    ) {
      onStopped()
      return
    }

    streamingJob =
      scope.launch(Dispatchers.IO) {
        val sampleRate = 16_000
        val minBuf =
          AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
          )
        if (minBuf <= 0) {
          onStopped()
          return@launch
        }
        val bufferSize = maxOf(minBuf, 4096)
        var recorder: AudioRecord? = null
        try {
          recorder =
            AudioRecord(
              MediaRecorder.AudioSource.VOICE_RECOGNITION,
              sampleRate,
              AudioFormat.CHANNEL_IN_MONO,
              AudioFormat.ENCODING_PCM_16BIT,
              bufferSize,
            )
          if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            onStopped()
            return@launch
          }
          if (!sendBegin(sessionId)) {
            onStopped()
            return@launch
          }
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
        } catch (_: Throwable) {
        } finally {
          runCatching { recorder?.stop() }
          runCatching { recorder?.release() }
          runCatching { sendEnd(sessionId) }
          onStopped()
        }
      }
  }

  fun stopStreaming() {
    streamingJob?.cancel()
    streamingJob = null
  }
}
