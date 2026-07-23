package com.joshua.circletoshare.assist

import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession

/**
 * Registered as the device's digital assistant. Holding the gesture bar
 * (or long-pressing home) makes the system show our session.
 */
class AssistInteractionService : VoiceInteractionService() {

    companion object {
        var instance: AssistInteractionService? = null
            private set
    }

    override fun onReady() {
        super.onReady()
        instance = this
    }

    override fun onShutdown() {
        instance = null
        super.onShutdown()
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    /** Used by MainActivity's "Test" button to trigger the overlay manually. */
    fun launchCaptureSession() {
        showSession(
            Bundle(),
            VoiceInteractionSession.SHOW_WITH_SCREENSHOT or VoiceInteractionSession.SHOW_WITH_ASSIST
        )
    }
}
