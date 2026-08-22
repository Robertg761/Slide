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
 * a second later. A start requested before the connection is up is therefore remembered and sent
 * as soon as it arrives. Every request also carries a session id: native cancellation is
 * asynchronous, so callbacks from a canceled decode must never be mistaken for its replacement.
 *
 * All callbacks and public methods run on the main thread.
 */
class VoiceInputClient(private val context: Context) {

    interface Listener {
        fun onVoiceState(state: VoiceInput.State)

        /** Microphone loudness, 0..1, roughly ten times a second while listening. */
        fun onVoiceLevel(level: Float)

        /** The transcript so far, while the decode is still running. Superseded by [onVoiceResult]. */
        fun onVoicePartial(text: String)

        /** The finished transcript. Empty when the user said nothing. */
        fun onVoiceResult(text: String)

        /** The session ended without a transcript; the code says why, the listener owns the words. */
        fun onVoiceError(error: VoiceInput.Error)
    }

    var listener: Listener? = null

    private data class PendingStart(val sessionId: Long, val model: WhisperModel)

    private var service: Messenger? = null

    /**
     * Whether the system holds a registration for [connection].
     *
     * This is not "the bind succeeded": `bindService` registers the connection even when it returns
     * false, and only `unbindService` removes it. Tracking the request rather than its result is
     * what lets a refused bind be cleaned up instead of leaking until the process dies.
     */
    private var registered = false
    private var pendingStart: PendingStart? = null
    private val session = VoiceSessionTracker()

    private val incoming = Messenger(Handler(Looper.getMainLooper()) { message ->
        val sessionId = message.data.getLong(
            VoiceInput.KEY_SESSION_ID,
            VoiceInput.NO_SESSION_ID,
        )
        when (message.what) {
            VoiceInput.MSG_STATE -> {
                val state = VoiceInput.State.fromOrdinal(message.arg1)
                if (state == VoiceInput.State.Idle && session.acknowledgeCancellation(sessionId)) {
                    listener?.onVoiceState(state)
                    return@Handler true
                }
                if (!session.accepts(sessionId)) return@Handler true
                if (state == VoiceInput.State.Idle) session.finish(sessionId)
                listener?.onVoiceState(state)
                true
            }

            VoiceInput.MSG_LEVEL -> {
                if (session.accepts(sessionId)) {
                    listener?.onVoiceLevel(message.arg1.toFloat() / VoiceInput.LEVEL_SCALE)
                }
                true
            }

            VoiceInput.MSG_PARTIAL -> {
                if (session.accepts(sessionId)) {
                    listener?.onVoicePartial(message.data.getString(VoiceInput.KEY_TEXT).orEmpty())
                }
                true
            }

            VoiceInput.MSG_RESULT -> {
                if (session.finish(sessionId)) {
                    listener?.onVoiceResult(message.data.getString(VoiceInput.KEY_TEXT).orEmpty())
                }
                true
            }

            VoiceInput.MSG_ERROR -> {
                if (session.finish(sessionId)) {
                    listener?.onVoiceError(VoiceInput.Error.fromOrdinal(message.arg1))
                }
                true
            }

            else -> false
        }
    })

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder == null) {
                clearBindingRegistration()
                reportActiveFailure(VoiceInput.Error.ServiceUnavailable)
                return
            }
            service = Messenger(binder)
            val pending = pendingStart
            pendingStart = null
            if (pending != null && session.accepts(pending.sessionId)) sendStart(pending)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            reportActiveFailure(VoiceInput.Error.ProcessDied)
        }

        override fun onBindingDied(name: ComponentName?) {
            service = null
            clearBindingRegistration()
            reportActiveFailure(VoiceInput.Error.ServiceUnavailable)
        }

        override fun onNullBinding(name: ComponentName?) {
            service = null
            clearBindingRegistration()
            reportActiveFailure(VoiceInput.Error.ServiceUnavailable)
        }
    }

    /** Connects to the speech process. Cheap: no model is loaded until the first [start]. */
    fun bind() {
        if (registered) return
        // Set before the call: a bindService that throws may still have registered the connection,
        // and an unbindService for a registration that was never made is harmless (it throws
        // IllegalArgumentException, which clearBindingRegistration expects).
        registered = true
        val started = try {
            context.bindService(
                Intent(context, VoiceInputService::class.java),
                connection,
                Context.BIND_AUTO_CREATE,
            )
        } catch (e: RuntimeException) {
            Log.e(TAG, "Could not bind the speech service", e)
            false
        }
        if (!started) {
            Log.e(TAG, "Could not bind the speech service")
            // No callback will ever arrive for a refused bind, so nothing else would ever drop the
            // registration: unbind() used to skip it and the connection leaked until process death.
            clearBindingRegistration()
            reportActiveFailure(VoiceInput.Error.ServiceUnavailable)
        }
    }

    /** Disconnects, which lets the speech process and its model be reclaimed. */
    fun unbind() {
        if (!registered) {
            session.reset()
            pendingStart = null
            service = null
            return
        }
        cancel()
        clearBindingRegistration()
        service = null
        pendingStart = null
        // An unbound client cannot receive the cancellation acknowledgement; invalidate locally.
        session.reset()
    }

    fun start(model: WhisperModel) {
        val sessionId = session.start()
        if (sessionId == VoiceInput.NO_SESSION_ID) return
        val pending = PendingStart(sessionId, model)
        if (service == null) {
            pendingStart = pending
            bind()
            return
        }
        sendStart(pending)
    }

    /** Stops recording and asks for the transcript. */
    fun stop() {
        val sessionId = session.currentSessionId()
        if (sessionId == VoiceInput.NO_SESSION_ID) return
        pendingStart = null
        if (service == null) {
            reportActiveFailure(VoiceInput.Error.ServiceUnavailable)
            return
        }
        send(request(VoiceInput.MSG_STOP, sessionId))
    }

    /** Stops recording and throws the audio away. */
    fun cancel() {
        pendingStart = null
        val sessionId = session.beginCancellation()
        if (sessionId == VoiceInput.NO_SESSION_ID) {
            // The service may already have reported a terminal error. The overlay still asks the
            // client to close, and its cancellation gate needs a local acknowledgement to reopen.
            listener?.onVoiceState(VoiceInput.State.Idle)
            return
        }
        if (service == null) {
            session.acknowledgeCancellation(sessionId)
            listener?.onVoiceState(VoiceInput.State.Idle)
            return
        }
        send(request(VoiceInput.MSG_CANCEL, sessionId))
    }

    private fun sendStart(pending: PendingStart) {
        send(
            Message.obtain(null, VoiceInput.MSG_START).apply {
                replyTo = incoming
                data = Bundle().apply {
                    putLong(VoiceInput.KEY_SESSION_ID, pending.sessionId)
                    putString(VoiceInput.KEY_MODEL, pending.model.name)
                }
            },
        )
    }

    private fun request(what: Int, sessionId: Long) = Message.obtain(null, what).apply {
        data = Bundle().apply { putLong(VoiceInput.KEY_SESSION_ID, sessionId) }
    }

    private fun send(message: Message): Boolean {
        val target = service ?: return false
        return try {
            target.send(message)
            true
        } catch (e: RemoteException) {
            Log.w(TAG, "Speech process is gone", e)
            service = null
            reportActiveFailure(VoiceInput.Error.ProcessDied)
            false
        }
    }

    private fun reportActiveFailure(error: VoiceInput.Error) {
        pendingStart = null
        if (session.consumeUnexpectedFailure()) listener?.onVoiceError(error)
        // Also acknowledges an expected cancellation whose service died before replying.
        listener?.onVoiceState(VoiceInput.State.Idle)
    }

    private fun clearBindingRegistration() {
        if (!registered) return
        try {
            context.unbindService(connection)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Speech service binding was already gone", e)
        } finally {
            registered = false
        }
    }

    private companion object {
        const val TAG = "SlideAsr"
    }
}
