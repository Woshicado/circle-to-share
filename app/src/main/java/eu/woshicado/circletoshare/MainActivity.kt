package eu.woshicado.circletoshare

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
import eu.woshicado.circletoshare.assist.AssistInteractionService
import eu.woshicado.circletoshare.capture.ScreenshotAccessibilityService

class MainActivity : AppCompatActivity() {

    // True while updateStatus() writes the switches, so the listeners can tell
    // programmatic sync from real input. (Checking view.isPressed instead would
    // also drop toggles made via TalkBack or a keyboard.)
    private var syncingSwitches = false

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
            if (syncingSwitches) return@setOnCheckedChangeListener
            if (checked && ScreenshotAccessibilityService.instance == null) {
                view.isChecked = false
                Toast.makeText(this, R.string.error_bubble_needs_accessibility, Toast.LENGTH_LONG).show()
                return@setOnCheckedChangeListener
            }
            ScreenshotAccessibilityService.setBubblePref(this, checked)
        }

        findViewById<MaterialSwitch>(R.id.switch_freeform).setOnCheckedChangeListener { _, checked ->
            if (syncingSwitches) return@setOnCheckedChangeListener
            Prefs.setFreeformCrop(this, checked)
        }

        findViewById<MaterialSwitch>(R.id.switch_snap).setOnCheckedChangeListener { view, checked ->
            if (syncingSwitches) return@setOnCheckedChangeListener
            if (checked && ScreenshotAccessibilityService.instance == null) {
                view.isChecked = false
                Toast.makeText(this, R.string.error_snap_needs_accessibility, Toast.LENGTH_LONG).show()
                return@setOnCheckedChangeListener
            }
            Prefs.setSnapEnabled(this, checked)
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

        syncingSwitches = true
        findViewById<MaterialSwitch>(R.id.switch_bubble).isChecked =
            ScreenshotAccessibilityService.isBubbleEnabled(this)

        findViewById<MaterialSwitch>(R.id.switch_freeform).isChecked =
            Prefs.isFreeformCrop(this)

        findViewById<MaterialSwitch>(R.id.switch_snap).isChecked =
            Prefs.isSnapEnabled(this)
        syncingSwitches = false
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
