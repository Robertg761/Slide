package com.slide.asr

import android.Manifest
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Records and transcribes on behalf of the keyboard, in its own process.
 *
 * The separation is the point. A loaded speech model is tens to hundreds of megabytes, and an
 * input method is one of the processes Android is most willing to kill when memory runs short —
 * losing the keyboard mid-sentence is far worse than losing a transcription. Here, the worst case
 * is that dictation dies and typing carries on.
 *
 * Audio never crosses the process boundary either. A few seconds of 16kHz float samples is several
 * megabytes, well past what a Binder transaction will carry, so recording happens on this side and
 * only the finished text is sent back.
 */
class VoiceInputService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val recorder = AudioRecorder()
    private val transcriber by lazy { WhisperTranscriber(applicationContext) }

    private var client: Messenger? = null
    private var work: Job? = null

    private val messenger = Messenger(Handler(Looper.getMainLooper()) { message ->
        when (message.what) {
            VoiceInput.MSG_START -> {
                client = message.replyTo
                start(WhisperModel.fromId(message.data?.getString(VoiceInput.KEY_MODEL)))
                true
            }

            VoiceInput.MSG_STOP -> { stop(); true }
            VoiceInput.MSG_CANCEL -> { cancel(); true }
            else -> false
        }
    })

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onUnbind(intent: Intent?): Boolean {
        cancel()
        return false
    }

    override fun onDestroy() {
        recorder.cancel()
        // Deliberately blocking: the process is going away, and leaving hundreds of megabytes of
        // native allocation to be reclaimed by process death is fine, but leaving the microphone
        // open is not.
        scope.cancel()
        super.onDestroy()
    }

    private fun start(model: WhisperModel) {
        if (work?.isActive == true) return

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            sendError("Microphone permission is needed for voice typing")
            return
        }

        work = scope.launch {
            sendState(VoiceInput.State.Preparing)
            if (!transcriber.load(model)) {
                sendError("Speech model could not be loaded")
                sendState(VoiceInput.State.Idle)
                return@launch
            }

            // Start recording only once the model is resident. Recording first would capture the
            // user's first word during a load that can take hundreds of milliseconds, but they
            // would be speaking to a UI that had not yet said it was listening.
            if (!recorder.start { level -> sendLevel(level) }) {
                sendError("The microphone is not available")
                sendState(VoiceInput.State.Idle)
                return@launch
            }
            sendState(VoiceInput.State.Listening)
        }
    }

    private fun stop() {
        val pending = work
        work = scope.launch {
            pending?.join() // in case stop arrives before the model has finished loading

            if (!recorder.isRecording) {
                sendState(VoiceInput.State.Idle)
                return@launch
            }

            val audio = recorder.stop()
            sendState(VoiceInput.State.Transcribing)

            when (val result = transcriber.transcribe(audio)) {
                is WhisperTranscriber.Result.Text -> sendResult(result.value)
                WhisperTranscriber.Result.NoSpeech -> sendResult("")
                is WhisperTranscriber.Result.Failed -> sendError(result.reason)
            }
            sendState(VoiceInput.State.Idle)
        }
    }

    private fun cancel() {
        work?.cancel()
        work = null
        recorder.cancel()
        sendState(VoiceInput.State.Idle)
    }

    private fun sendState(state: VoiceInput.State) =
        send(Message.obtain(null, VoiceInput.MSG_STATE, state.ordinal, 0))

    private fun sendLevel(level: Float) =
        send(Message.obtain(null, VoiceInput.MSG_LEVEL, (level * VoiceInput.LEVEL_SCALE).toInt(), 0))

    private fun sendResult(text: String) = send(
        Message.obtain(null, VoiceInput.MSG_RESULT).apply {
            data = Bundle().apply { putString(VoiceInput.KEY_TEXT, text) }
        },
    )

    private fun sendError(reason: String) {
        Log.w(TAG, reason)
        send(
            Message.obtain(null, VoiceInput.MSG_ERROR).apply {
                data = Bundle().apply { putString(VoiceInput.KEY_REASON, reason) }
            },
        )
    }

    private fun send(message: Message) {
        try {
            client?.send(message)
        } catch (e: RemoteException) {
            // The keyboard went away mid-dictation. Nothing to report to and nothing to do but
            // stop holding the microphone.
            Log.i(TAG, "Keyboard is gone; abandoning dictation", e)
            client = null
            recorder.cancel()
        }
    }

    private companion object {
        const val TAG = "SlideAsr"
    }
}
