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
import kotlinx.coroutines.runBlocking

/**
 * Records and transcribes on behalf of the keyboard, in its own process.
 *
 * Every command and callback carries a client-generated session id. Native abort is cooperative,
 * so a canceled decode can finish after its replacement starts; the id gate ensures the old job
 * cannot reset shared state, stop the replacement recorder, or send UI events for the new session.
 * All service state and Messenger sends are confined to the main thread.
 */
class VoiceInputService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val recorder = AudioRecorder()
    private val transcriberDelegate = lazy { WhisperTranscriber(applicationContext) }
    private val transcriber by transcriberDelegate
    private val sessions = VoiceServiceSessionGate()

    private var client: Messenger? = null
    private var work: Job? = null

    private val messenger = Messenger(Handler(Looper.getMainLooper()) { message ->
        val sessionId = message.data.getLong(
            VoiceInput.KEY_SESSION_ID,
            VoiceInput.NO_SESSION_ID,
        )
        when (message.what) {
            VoiceInput.MSG_START -> {
                val reply = message.replyTo
                if (reply != null) {
                    client = reply
                    start(sessionId, WhisperModel.fromId(message.data.getString(VoiceInput.KEY_MODEL)))
                }
                true
            }

            VoiceInput.MSG_STOP -> {
                stop(sessionId)
                true
            }

            VoiceInput.MSG_CANCEL -> {
                cancel(sessionId)
                true
            }

            else -> false
        }
    })

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onUnbind(intent: Intent?): Boolean {
        val active = sessions.currentSessionId()
        if (active != VoiceInput.NO_SESSION_ID) abandonSession(active) else recorder.cancel()
        client = null
        return false
    }

    override fun onDestroy() {
        val active = sessions.currentSessionId()
        if (active != VoiceInput.NO_SESSION_ID) abandonSession(active) else recorder.cancel()
        if (transcriberDelegate.isInitialized()) {
            // close() first aborts an active decode, then waits for its mutex before freeing the
            // context. This short blocking handoff prevents use-after-free and a cached-process leak.
            runBlocking { transcriber.close() }
        }
        scope.cancel()
        super.onDestroy()
    }

    private fun start(sessionId: Long, model: WhisperModel) {
        if (!sessions.start(sessionId)) {
            if (sessionId != VoiceInput.NO_SESSION_ID && !sessions.isCurrent(sessionId)) {
                sendError(sessionId, "Voice typing is still closing")
                sendState(sessionId, VoiceInput.State.Idle)
            }
            return
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            finishWithError(sessionId, "Microphone permission is needed for voice typing")
            return
        }

        work = scope.launch {
            sendStateIfCurrent(sessionId, VoiceInput.State.Preparing)
            if (!transcriber.load(model)) {
                finishWithError(sessionId, "Speech model could not be loaded")
                return@launch
            }
            if (!sessions.isCurrent(sessionId)) return@launch

            // Audio callbacks originate on the recorder worker. Marshal them to this main scope
            // before reading or mutating any service/session state or touching Messenger.
            val started = recorder.start(
                listener = { level ->
                    scope.launch {
                        if (sessions.isCurrent(sessionId)) sendLevel(sessionId, level)
                    }
                },
                endListener = { reason ->
                    scope.launch { handleRecorderEnd(sessionId, reason) }
                },
            )
            if (!started) {
                finishWithError(sessionId, "The microphone is not available")
                return@launch
            }
            if (!sessions.isCurrent(sessionId)) {
                recorder.cancel()
                return@launch
            }
            sendState(sessionId, VoiceInput.State.Listening)
        }
    }

    private fun stop(sessionId: Long) {
        if (!sessions.beginFinishing(sessionId)) return

        val pending = work
        work = scope.launch {
            var audio = FloatArray(0)
            try {
                pending?.join() // stop may arrive while the model is still loading
                if (!sessions.isCurrent(sessionId)) return@launch

                audio = recorder.stop()
                if (!sessions.isCurrent(sessionId)) return@launch
                sendState(sessionId, VoiceInput.State.Transcribing)

                when (val result = transcriber.transcribe(audio)) {
                    is WhisperTranscriber.Result.Text -> sendResultIfCurrent(sessionId, result.value)
                    WhisperTranscriber.Result.NoSpeech -> sendResultIfCurrent(sessionId, "")
                    is WhisperTranscriber.Result.Failed -> sendErrorIfCurrent(sessionId, result.reason)
                }
            } finally {
                // WhisperTranscriber also wipes this copy. Keep the service boundary defensive if
                // its implementation changes or cancellation happens before it is entered.
                PcmBuffers.wipe(audio)
                if (sessions.finish(sessionId)) {
                    work = null
                    sendState(sessionId, VoiceInput.State.Idle)
                }
            }
        }
    }

    private fun cancel(sessionId: Long) {
        if (sessions.isCurrent(sessionId)) abandonSession(sessionId)
        // This is the single cancellation acknowledgement. Any later finalizer for this id is
        // generation-guarded and therefore cannot send a second Idle into a replacement session.
        if (sessionId != VoiceInput.NO_SESSION_ID) sendState(sessionId, VoiceInput.State.Idle)
    }

    private fun handleRecorderEnd(sessionId: Long, reason: AudioRecorder.EndReason) {
        if (!sessions.isCurrent(sessionId)) return
        when (reason) {
            AudioRecorder.EndReason.RecordingLimitReached -> stop(sessionId)
            AudioRecorder.EndReason.CaptureFailed ->
                finishWithError(sessionId, "The microphone stopped unexpectedly")
        }
    }

    private fun finishWithError(sessionId: Long, reason: String) {
        if (!sessions.finish(sessionId)) return
        val abandoned = work
        work = null
        abandoned?.cancel()
        recorder.cancel()
        sendError(sessionId, reason)
        sendState(sessionId, VoiceInput.State.Idle)
    }

    /** Invalidates the id before cancellation so old finally blocks become harmless. */
    private fun abandonSession(sessionId: Long) {
        if (!sessions.finish(sessionId)) return
        if (transcriberDelegate.isInitialized()) transcriber.cancelTranscription()
        val abandoned = work
        work = null
        abandoned?.cancel()
        recorder.cancel()
    }

    private fun sendStateIfCurrent(sessionId: Long, state: VoiceInput.State) {
        if (sessions.isCurrent(sessionId)) sendState(sessionId, state)
    }

    private fun sendResultIfCurrent(sessionId: Long, text: String) {
        if (sessions.isCurrent(sessionId)) sendResult(sessionId, text)
    }

    private fun sendErrorIfCurrent(sessionId: Long, reason: String) {
        if (sessions.isCurrent(sessionId)) sendError(sessionId, reason)
    }

    private fun sendState(sessionId: Long, state: VoiceInput.State) =
        send(sessionId, Message.obtain(null, VoiceInput.MSG_STATE, state.ordinal, 0))

    private fun sendLevel(sessionId: Long, level: Float) = send(
        sessionId,
        Message.obtain(null, VoiceInput.MSG_LEVEL, (level * VoiceInput.LEVEL_SCALE).toInt(), 0),
    )

    private fun sendResult(sessionId: Long, text: String) = send(
        sessionId,
        Message.obtain(null, VoiceInput.MSG_RESULT).apply {
            data = Bundle().apply { putString(VoiceInput.KEY_TEXT, text) }
        },
    )

    private fun sendError(sessionId: Long, reason: String) {
        Log.w(TAG, reason)
        send(
            sessionId,
            Message.obtain(null, VoiceInput.MSG_ERROR).apply {
                data = Bundle().apply { putString(VoiceInput.KEY_REASON, reason) }
            },
        )
    }

    /** Called only on the service main thread. */
    private fun send(sessionId: Long, message: Message) {
        val payload = message.data
        payload.putLong(VoiceInput.KEY_SESSION_ID, sessionId)
        message.data = payload
        try {
            client?.send(message)
        } catch (e: RemoteException) {
            Log.i(TAG, "Keyboard is gone; abandoning dictation", e)
            client = null
            if (sessions.isCurrent(sessionId)) abandonSession(sessionId)
        }
    }

    private companion object {
        const val TAG = "SlideAsr"
    }
}
