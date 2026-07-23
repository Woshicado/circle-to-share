package com.joshua.circletoshare

import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.materialswitch.MaterialSwitch
import com.joshua.circletoshare.assist.AssistInteractionService
import com.joshua.circletoshare.capture.ScreenshotAccessibilityService

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.btn_assistant_settings).setOnClickListener {
            openFirstAvailable(
                Intent(Settings.ACTION_VOICE_INPUT_SETTINGS),
                Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
            )
        }
        findViewById<Button>(R.id.btn_accessibility_settings).setOnClickListener {
            openFirstAvailable(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.btn_test).setOnClickListener {
            val service = AssistInteractionService.instance
            if (service == null) {
                Toast.makeText(this, R.string.error_not_assistant, Toast.LENGTH_LONG).show()
            } else {
                service.launchCaptureSession()
            }
        }

        findViewById<MaterialSwitch>(R.id.switch_bubble).setOnCheckedChangeListener { view, checked ->
            if (!view.isPressed) return@setOnCheckedChangeListener // ignore programmatic sync
            if (checked && ScreenshotAccessibilityService.instance == null) {
                view.isChecked = false
                Toast.makeText(this, R.string.error_bubble_needs_accessibility, Toast.LENGTH_LONG).show()
                return@setOnCheckedChangeListener
            }
            ScreenshotAccessibilityService.setBubblePref(this, checked)
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun updateStatus() {
        val roleManager = getSystemService(RoleManager::class.java)
        val isAssistant = roleManager?.isRoleHeld(RoleManager.ROLE_ASSISTANT) == true ||
            Settings.Secure.getString(contentResolver, "voice_interaction_service")
                ?.contains(packageName) == true

        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: ""
        val fallbackEnabled = enabledServices.contains(packageName)

        findViewById<TextView>(R.id.status_assistant).text = getString(
            if (isAssistant) R.string.status_assistant_on else R.string.status_assistant_off
        )
        findViewById<TextView>(R.id.status_accessibility).text = getString(
            if (fallbackEnabled) R.string.status_fallback_on else R.string.status_fallback_off
        )

        findViewById<MaterialSwitch>(R.id.switch_bubble).isChecked =
            ScreenshotAccessibilityService.isBubbleEnabled(this)
    }

    private fun openFirstAvailable(vararg intents: Intent) {
        for (intent in intents) {
            try {
                startActivity(intent)
                return
            } catch (_: ActivityNotFoundException) {
            }
        }
        Toast.makeText(this, R.string.error_no_settings, Toast.LENGTH_SHORT).show()
    }
}
