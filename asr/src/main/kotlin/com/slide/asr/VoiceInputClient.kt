package com.slide.asr

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import android.util.Log

/**
 * The keyboard's handle on [VoiceInputService].
 *
 * Binding is asynchronous, and the user taps the microphone at the moment they want to speak, not
 * a second later. So a start requested before the connection is up is remembered and sent the
 * instant it arrives, rather than being dropped or made to wait behind a callback.
 *
 * All callbacks arrive on the main thread.
 */
class VoiceInputClient(private val context: Context) {

    interface Listener {
        fun onVoiceState(state: VoiceInput.State)

        /** Microphone loudness, 0..1, roughly ten times a second while listening. */
        fun onVoiceLevel(level: Float)

        /** The finished transcript. Empty when the user said nothing. */
        fun onVoiceResult(text: String)

        fun onVoiceError(reason: String)
    }

    var listener: Listener? = null

    private var service: Messenger? = null
    private var bound = false
    private var pendingStart: WhisperModel? = null

    private val incoming = Messenger(Handler(Looper.getMainLooper()) { message ->
        when (message.what) {
            VoiceInput.MSG_STATE -> {
                listener?.onVoiceState(VoiceInput.State.fromOrdinal(message.arg1))
                true
            }

            VoiceInput.MSG_LEVEL -> {
                listener?.onVoiceLevel(message.arg1.toFloat() / VoiceInput.LEVEL_SCALE)
                true
            }

            VoiceInput.MSG_RESULT -> {
                listener?.onVoiceResult(message.data?.getString(VoiceInput.KEY_TEXT).orEmpty())
                true
            }

            VoiceInput.MSG_ERROR -> {
                listener?.onVoiceError(
                    message.data?.getString(VoiceInput.KEY_REASON) ?: "Voice typing failed",
                )
                true
            }

            else -> false
        }
    })

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = binder?.let(::Messenger)
            pendingStart?.let { model ->
                pendingStart = null
                start(model)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            // The speech process was killed, most likely for memory. Rebinding happens on the next
            // start; reporting it now would put an error in front of a user who is not dictating.
            service = null
            listener?.onVoiceState(VoiceInput.State.Idle)
        }
    }

    /** Connects to the speech process. Cheap: no model is loaded until the first [start]. */
    fun bind() {
        if (bound) return
        bound = context.bindService(
            Intent(context, VoiceInputService::class.java),
            connection,
            Context.BIND_AUTO_CREATE,
        )
        if (!bound) Log.e(TAG, "Could not bind the speech service")
    }

    /** Disconnects, which lets the speech process and its model be reclaimed. */
    fun unbind() {
        if (!bound) return
        cancel()
        context.unbindService(connection)
        bound = false
        service = null
        pendingStart = null
    }

    fun start(model: WhisperModel) {
        val target = service
        if (target == null) {
            pendingStart = model
            bind()
            return
        }
        send(
            Message.obtain(null, VoiceInput.MSG_START).apply {
                replyTo = incoming
                data = Bundle().apply { putString(VoiceInput.KEY_MODEL, model.name) }
            },
        )
    }

    /** Stops recording and asks for the transcript. */
    fun stop() {
        pendingStart = null
        send(Message.obtain(null, VoiceInput.MSG_STOP))
    }

    /** Stops recording and throws the audio away. */
    fun cancel() {
        pendingStart = null
        send(Message.obtain(null, VoiceInput.MSG_CANCEL))
    }

    private fun send(message: Message) {
        try {
            service?.send(message)
        } catch (e: RemoteException) {
            Log.w(TAG, "Speech process is gone", e)
            service = null
            listener?.onVoiceState(VoiceInput.State.Idle)
        }
    }

    private companion object {
        const val TAG = "SlideAsr"
    }
}
