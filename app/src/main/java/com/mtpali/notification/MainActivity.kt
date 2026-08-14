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
    private lateinit var stableRadio: RadioButton
    private lateinit var hiddenRadio: RadioButton
    private lateinit var pairInput: EditText
    private lateinit var senderInfo: TextView
    private lateinit var receiverInfo: TextView

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
            setOnClickListener { openNotificationAccess() }
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
        val receiverTypeGroup = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        stableRadio = RadioButton(this).apply { text = "Stable • persistent status" }
        hiddenRadio = RadioButton(this).apply { text = "Hidden • no persistent status" }
        receiverTypeGroup.addView(stableRadio)
        receiverTypeGroup.addView(hiddenRadio)
        root.addView(receiverTypeGroup)

        if (Prefs.receiverTransport(this) == Prefs.RECEIVER_HIDDEN) hiddenRadio.isChecked = true
        else stableRadio.isChecked = true

        root.addView(TextView(this).apply {
            text = "Hidden requires Notification Access on the Receiver."
            setPadding(0, 0, 0, dp(4))
        })
        root.addView(Button(this).apply {
            text = "Notification Access"
            setOnClickListener { openNotificationAccess() }
        })
        root.addView(Button(this).apply {
            text = "Start"
            setOnClickListener {
                if (!saveSettings(false)) return@setOnClickListener
                if (Prefs.mode(this@MainActivity) != Prefs.MODE_RECEIVER) {
                    toast("Select Receiver")
                    return@setOnClickListener
                }

                if (Prefs.receiverTransport(this@MainActivity) == Prefs.RECEIVER_HIDDEN &&
                    !hasNotificationAccess()
                ) {
                    Prefs.setReceiverEnabled(this@MainActivity, false)
                    stopService(Intent(this@MainActivity, HiddenReceiverService::class.java))
                    toast("Enable Notification Access for Hidden")
                    openNotificationAccess()
                    updateInfo()
                    return@setOnClickListener
                }

                Prefs.setReceiverEnabled(this@MainActivity, true)
                applyReceiverRuntime()
                toast(
                    if (Prefs.receiverTransport(this@MainActivity) == Prefs.RECEIVER_HIDDEN) {
                        "Hidden started"
                    } else {
                        "Started"
                    }
                )
                updateInfo()
            }
        })
        root.addView(Button(this).apply {
            text = "Stop"
            setOnClickListener {
                Prefs.setReceiverEnabled(this@MainActivity, false)
                stopService(Intent(this@MainActivity, ReceiverService::class.java))
                stopService(Intent(this@MainActivity, HiddenReceiverService::class.java))
                MirrorNotificationListener.refresh(this@MainActivity)
                toast("Stopped")
                updateInfo()
            }
        })
        receiverInfo = TextView(this).apply { setPadding(0, dp(4), 0, 0) }
        root.addView(receiverInfo)

        updateInfo()
    }

    override fun onResume() {
        super.onResume()
        if (Prefs.mode(this) == Prefs.MODE_RECEIVER &&
            Prefs.receiverTransport(this) == Prefs.RECEIVER_HIDDEN &&
            Prefs.receiverEnabled(this) &&
            hasNotificationAccess()
        ) {
            startHiddenReceiverService()
            MirrorNotificationListener.refresh(this)
        }
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
        val transport = if (hiddenRadio.isChecked) Prefs.RECEIVER_HIDDEN else Prefs.RECEIVER_STABLE

        if (oldMode != mode && mode == Prefs.MODE_RECEIVER) {
            Prefs.setReceiverEnabled(this, false)
        }

        Prefs.setMode(this, mode)
        Prefs.setPairCode(this, code)
        Prefs.setReceiverTransport(this, transport)

        if (mode == Prefs.MODE_SENDER) {
            Prefs.setReceiverEnabled(this, false)
            stopService(Intent(this, ReceiverService::class.java))
            stopService(Intent(this, HiddenReceiverService::class.java))
            MirrorNotificationListener.refresh(this)
        }

        if (showToast) toast("Saved")
        updateInfo()
        return true
    }

    private fun applyReceiverRuntime() {
        if (Prefs.mode(this) != Prefs.MODE_RECEIVER || !Prefs.receiverEnabled(this)) return

        if (Prefs.receiverTransport(this) == Prefs.RECEIVER_HIDDEN) {
            stopService(Intent(this, ReceiverService::class.java))
            startHiddenReceiverService()
            MirrorNotificationListener.refresh(this)
        } else {
            stopService(Intent(this, HiddenReceiverService::class.java))
            MirrorNotificationListener.refresh(this)
            stopService(Intent(this, ReceiverService::class.java))
            startForegroundService(Intent(this, ReceiverService::class.java))
        }
    }

    private fun startHiddenReceiverService() {
        try {
            startService(Intent(this, HiddenReceiverService::class.java))
        } catch (_: IllegalStateException) {
            MirrorNotificationListener.refresh(this)
        }
    }

    private fun updateInfo() {
        val access = hasNotificationAccess()
        val apps = if (Prefs.forwardAllApps(this)) "All apps" else "${Prefs.selectedApps(this).size} apps"
        senderInfo.text = "Access: ${if (access) "ON" else "OFF"} • $apps"

        val transport = if (Prefs.receiverTransport(this) == Prefs.RECEIVER_HIDDEN) "Hidden" else "Stable"
        val enabled = Prefs.mode(this) == Prefs.MODE_RECEIVER && Prefs.receiverEnabled(this)
        receiverInfo.text = "$transport • ${if (enabled) "ON" else "OFF"}"
    }

    private fun hasNotificationAccess(): Boolean =
        Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
            ?.contains(packageName) == true

    private fun openNotificationAccess() {
        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }

    private fun sectionTitle(value: String) = TextView(this).apply {
        text = value
        textSize = 18f
        setPadding(0, dp(14), 0, dp(5))
    }

    private fun toast(value: String) = Toast.makeText(this, value, Toast.LENGTH_SHORT).show()

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
