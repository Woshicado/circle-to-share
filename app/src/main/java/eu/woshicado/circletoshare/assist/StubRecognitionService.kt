package eu.woshicado.circletoshare.assist

import android.content.Intent
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/**
 * The system requires an assistant to declare a recognition service.
 * This app never does voice recognition, so every request is rejected.
 */
class StubRecognitionService : RecognitionService() {

    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        try {
            listener?.error(SpeechRecognizer.ERROR_RECOGNIZER_BUSY)
        } catch (_: Exception) {
        }
    }

    override fun onCancel(listener: Callback?) = Unit

    override fun onStopListening(listener: Callback?) = Unit
}
