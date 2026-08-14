package com.mtpali.notification

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var senderRadio: RadioButton
    private lateinit var receiverRadio: RadioButton
    private lateinit var pairInput: EditText
    private lateinit var statusText: TextView
    private lateinit var filterText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(28))
        }

        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)

        root.addView(TextView(this).apply {
            text = "Notification"
            textSize = 28f
        })
        root.addView(TextView(this).apply {
            text = "Encrypted notification bridge for two Android phones"
            textSize = 15f
            setPadding(0, dp(4), 0, dp(18))
        })

        root.addView(sectionTitle("1. Choose this phone's role"))
        val modeGroup = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
        }
        senderRadio = RadioButton(this).apply { text = "Sender" }
        receiverRadio = RadioButton(this).apply { text = "Receiver" }
        modeGroup.addView(senderRadio)
        modeGroup.addView(receiverRadio)
        root.addView(modeGroup)

        if (Prefs.mode(this) == Prefs.MODE_RECEIVER) receiverRadio.isChecked = true
        else senderRadio.isChecked = true

        root.addView(sectionTitle("2. Pair Code"))
        root.addView(TextView(this).apply {
            text = "Use the same simple 6-digit code on both phones."
        })

        pairInput = EditText(this).apply {
            hint = "6-digit Pair Code"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(Prefs.pairCode(this@MainActivity))
        }
        root.addView(pairInput)

        root.addView(Button(this).apply {
            text = "Generate 6-digit Pair Code"
            setOnClickListener {
                pairInput.setText(CryptoBox.generatePairCode())
                pairInput.setSelection(pairInput.text.length)
            }
        })

        root.addView(Button(this).apply {
            text = "Save settings"
            setOnClickListener { saveSettings(showToast = true) }
        })

        root.addView(sectionTitle("3. Sender setup"))
        root.addView(Button(this).apply {
            text = "Open Notification Access"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
        })

        filterText = TextView(this)
        root.addView(filterText)
        root.addView(Button(this).apply {
            text = "Choose apps to forward"
            setOnClickListener {
                startActivity(Intent(this@MainActivity, AppSelectionActivity::class.java))
            }
        })

        root.addView(sectionTitle("4. Receiver setup"))
        root.addView(TextView(this).apply {
            text = "Receiver mode keeps a small foreground status notification visible so Android 9/10 can maintain the Internet stream."
        })
        root.addView(Button(this).apply {
            text = "Start Receiver"
            setOnClickListener {
                if (!saveSettings(showToast = false)) return@setOnClickListener

                if (Prefs.mode(this@MainActivity) != Prefs.MODE_RECEIVER) {
                    Toast.makeText(this@MainActivity, "Select Receiver first", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                if (!CryptoBox.isValidPairCode(Prefs.pairCode(this@MainActivity))) {
                    Toast.makeText(this@MainActivity, "Enter a 6-digit Pair Code first", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                startForegroundService(Intent(this@MainActivity, ReceiverService::class.java))
                Toast.makeText(this@MainActivity, "Receiver started", Toast.LENGTH_SHORT).show()
                updateStatus()
            }
        })
        root.addView(Button(this).apply {
            text = "Stop Receiver"
            setOnClickListener {
                stopService(Intent(this@MainActivity, ReceiverService::class.java))
                Toast.makeText(this@MainActivity, "Receiver stopped", Toast.LENGTH_SHORT).show()
            }
        })

        root.addView(sectionTitle("Status"))
        statusText = TextView(this).apply { textSize = 15f }
        root.addView(statusText)
        updateStatus()
    }

    override fun onResume() {
        super.onResume()
        if (::statusText.isInitialized) updateStatus()
    }

    private fun saveSettings(showToast: Boolean): Boolean {
        val pairCode = pairInput.text.toString().trim()

        if (pairCode.isNotEmpty() && !CryptoBox.isValidPairCode(pairCode)) {
            pairInput.error = "Pair Code must be exactly 6 digits"
            if (showToast) {
                Toast.makeText(this, "Pair Code must be exactly 6 digits", Toast.LENGTH_SHORT).show()
            }
            return false
        }

        pairInput.error = null
        val previousMode = Prefs.mode(this)
        val mode = if (receiverRadio.isChecked) Prefs.MODE_RECEIVER else Prefs.MODE_SENDER
        Prefs.setMode(this, mode)
        Prefs.setPairCode(this, pairCode)

        if (previousMode == Prefs.MODE_RECEIVER && mode == Prefs.MODE_SENDER) {
            stopService(Intent(this, ReceiverService::class.java))
        }

        if (showToast) Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
        updateStatus()
        return true
    }

    private fun updateStatus() {
        val notificationAccess = Settings.Secure.getString(
            contentResolver,
            "enabled_notification_listeners"
        )?.contains(packageName) == true

        filterText.text = if (Prefs.forwardAllApps(this)) {
            "App filter: forwarding all apps"
        } else {
            "App filter: ${Prefs.selectedApps(this).size} selected app(s)"
        }

        val role = if (Prefs.mode(this) == Prefs.MODE_RECEIVER) "Receiver" else "Sender"
        val pairCode = Prefs.pairCode(this)
        val paired = when {
            pairCode.isBlank() -> "not configured"
            CryptoBox.isValidPairCode(pairCode) -> "6-digit code configured"
            else -> "old code - generate a new 6-digit code"
        }
        val access = if (notificationAccess) "enabled" else "not enabled"

        statusText.text = "Role: $role\nPair Code: $paired\nNotification Access: $access"
    }

    private fun sectionTitle(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 19f
        setPadding(0, dp(18), 0, dp(8))
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
