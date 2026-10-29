package com.qmxz.pilotbotv2.voice

/**
 * Providers that resolve the real engine on every call.
 *
 * The pipeline holds one TTS/ASR instance for its whole lifetime, but the
 * user may switch engines in Settings at any time. Instead of rebuilding
 * the pipeline (and dropping the conversation), these routers forward each
 * call to whatever [VoiceSettings.effectiveEngine] currently selects:
 *
 * - speak()/startListening() resolve fresh, so the NEXT round uses the new
 *   engine with no restart;
 * - stop()/stopListening() go to the provider that started the current
 *   operation, so switching mid-sentence cannot strand audio playing on
 *   the old engine while the stop goes to the new one;
 * - shutdown()/destroy() release every engine, not just the active one.
 *
 * Availability (the health bar) always reflects the resolved engine.
 */
class SwitchableTtsProvider(
    private val resolve: () -> TtsProvider,
    private val all: List<TtsProvider>
) : TtsProvider {

    private var active: TtsProvider? = null

    override fun isAvailable(): Boolean = resolve().isAvailable()

    override fun availabilityMessage(): String = resolve().availabilityMessage()

    override fun speak(sentence: String, utteranceId: String, listener: TtsListener) {
        val provider = resolve()
        active = provider
        provider.speak(sentence, utteranceId, listener)
    }

    override fun stop() {
        (active ?: resolve()).stop()
    }

    override fun shutdown() {
        all.forEach { provider ->
            try {
                provider.shutdown()
            } catch (_: Exception) {
            }
        }
    }
}

class SwitchableAsrProvider(
    private val resolve: () -> AsrProvider,
    private val all: List<AsrProvider>
) : AsrProvider {

    private var active: AsrProvider? = null

    override fun isAvailable(): Boolean = resolve().isAvailable()

    override fun availabilityMessage(): String = resolve().availabilityMessage()

    override fun startListening(listener: AsrListener) {
        val provider = resolve()
        active = provider
        provider.startListening(listener)
    }

    override fun stopListening() {
        (active ?: resolve()).stopListening()
    }

    override fun destroy() {
        all.forEach { provider ->
            try {
                provider.destroy()
            } catch (_: Exception) {
            }
        }
    }
}
