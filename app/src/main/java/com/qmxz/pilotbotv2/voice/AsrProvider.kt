package com.qmxz.pilotbotv2.voice

/**
 * Callbacks for one listening session. Partial results are for live display only;
 * [onFinal] is the only result the app acts on.
 */
interface AsrListener {
    fun onPartial(text: String)
    fun onFinal(text: String)

    /**
     * [recoverable] tells the UI whether "tap the mic and try again" makes sense
     * (no match / timeout / network) versus a hard problem (no permission / no service).
     */
    fun onError(message: String, recoverable: Boolean)
}

/**
 * Minimal contract any speech-recognition engine must satisfy.
 * v1 lesson: ASR errors were silent or cryptic. Every error must map to a
 * message the driver can understand (see [AsrErrorMapper]).
 */
interface AsrProvider {
    fun isAvailable(): Boolean
    fun availabilityMessage(): String

    /** Start one listening session. Only one session at a time. */
    fun startListening(listener: AsrListener)

    /**
     * Discard the current session. Implemented as cancel() rather than
     * stopListening() so no stale partial result is delivered after the user
     * has already moved on.
     */
    fun stopListening()

    /** Release the recogniser. Safe to call repeatedly. */
    fun destroy()
}
