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
    private lateinit var senderInfo: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(24))
        }
        setContentView(ScrollView(this).apply { addView(root) })

        root.addView(TextView(this).apply {
            text = "Notification"
            textSize = 27f
            setPadding(0, 0, 0, dp(10))
        })

        val modeGroup = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        senderRadio = RadioButton(this).apply { text = "Sender" }
        receiverRadio = RadioButton(this).apply { text = "Receiver" }
        modeGroup.addView(senderRadio)
        modeGroup.addView(receiverRadio)
        root.addView(modeGroup)

        if (Prefs.mode(this) == Prefs.MODE_RECEIVER) receiverRadio.isChecked = true
        else senderRadio.isChecked = true

        root.addView(sectionTitle("Pair Code"))
        pairInput = EditText(this).apply {
            hint = "6-digit code"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(Prefs.pairCode(this@MainActivity))
        }
        root.addView(pairInput)
        root.addView(Button(this).apply {
            text = "Generate"
            setOnClickListener {
                pairInput.setText(CryptoBox.generatePairCode())
                pairInput.setSelection(pairInput.text.length)
            }
        })
        root.addView(Button(this).apply {
            text = "Save"
            setOnClickListener { saveSettings(true) }
        })

        root.addView(sectionTitle("Sender"))
        root.addView(Button(this).apply {
            text = "Notification Access"
            setOnClickListener { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        })
        root.addView(Button(this).apply {
            text = "Apps"
            setOnClickListener {
                startActivity(Intent(this@MainActivity, AppSelectionActivity::class.java))
            }
        })
        root.addView(Button(this).apply {
            text = "Send Test"
            setOnClickListener {
                if (!saveSettings(false)) return@setOnClickListener
                if (Prefs.mode(this@MainActivity) != Prefs.MODE_SENDER) {
                    toast("Select Sender")
                    return@setOnClickListener
                }
                val now = System.currentTimeMillis()
                RelayClient.publish(
                    applicationContext,
                    MirrorPayload(
                        packageName = packageName,
                        appName = "Notification",
                        title = "Test",
                        text = "Test message",
                        postTime = now,
                        notificationKey = "test-$now"
                    )
                )
                toast("Sent")
            }
        })
        senderInfo = TextView(this).apply { setPadding(0, dp(4), 0, 0) }
        root.addView(senderInfo)

        root.addView(sectionTitle("Receiver"))
        root.addView(Button(this).apply {
            text = "Start"
            setOnClickListener {
                if (!saveSettings(false)) return@setOnClickListener
                if (Prefs.mode(this@MainActivity) != Prefs.MODE_RECEIVER) {
                    toast("Select Receiver")
                    return@setOnClickListener
                }
                startForegroundService(Intent(this@MainActivity, ReceiverService::class.java))
                toast("Started")
            }
        })
        root.addView(Button(this).apply {
            text = "Stop"
            setOnClickListener {
                stopService(Intent(this@MainActivity, ReceiverService::class.java))
                toast("Stopped")
            }
        })

        updateInfo()
    }

    override fun onResume() {
        super.onResume()
        if (::senderInfo.isInitialized) updateInfo()
    }

    private fun saveSettings(showToast: Boolean): Boolean {
        val code = pairInput.text.toString().trim()
        if (!CryptoBox.isValidPairCode(code)) {
            pairInput.error = "6 digits"
            if (showToast) toast("Enter 6 digits")
            return false
        }

        pairInput.error = null
        val oldMode = Prefs.mode(this)
        val mode = if (receiverRadio.isChecked) Prefs.MODE_RECEIVER else Prefs.MODE_SENDER
        Prefs.setMode(this, mode)
        Prefs.setPairCode(this, code)
        if (oldMode == Prefs.MODE_RECEIVER && mode == Prefs.MODE_SENDER) {
            stopService(Intent(this, ReceiverService::class.java))
        }
        if (showToast) toast("Saved")
        updateInfo()
        return true
    }

    private fun updateInfo() {
        val access = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
            ?.contains(packageName) == true
        val apps = if (Prefs.forwardAllApps(this)) "All apps" else "${Prefs.selectedApps(this).size} apps"
        senderInfo.text = "Access: ${if (access) "ON" else "OFF"} • $apps"
    }

    private fun sectionTitle(value: String) = TextView(this).apply {
        text = value
        textSize = 18f
        setPadding(0, dp(14), 0, dp(5))
    }

    private fun toast(value: String) = Toast.makeText(this, value, Toast.LENGTH_SHORT).show()

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
