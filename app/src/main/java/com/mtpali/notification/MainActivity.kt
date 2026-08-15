package com.mtpali.notification

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
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
    private lateinit var pushRadio: RadioButton
    private lateinit var compatibilityRadio: RadioButton
    private lateinit var pairInput: EditText
    private lateinit var relayUrlInput: EditText
    private lateinit var relayTokenInput: EditText
    private lateinit var relayKeyStatus: TextView
    private lateinit var relayKeyButton: Button
    private lateinit var senderPanel: LinearLayout
    private lateinit var receiverPanel: LinearLayout
    private lateinit var advancedPanel: LinearLayout
    private lateinit var fcmSettingsPanel: LinearLayout
    private lateinit var advancedButton: Button
    private lateinit var senderInfo: TextView
    private lateinit var receiverInfo: TextView
    private var editingRelayKey = false

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

        root.addView(sectionTitle("This phone"))
        val modeGroup = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        senderRadio = RadioButton(this).apply { text = "Sender" }
        receiverRadio = RadioButton(this).apply { text = "Receiver" }
        modeGroup.addView(senderRadio)
        modeGroup.addView(receiverRadio)
        root.addView(modeGroup)

        if (Prefs.mode(this) == Prefs.MODE_RECEIVER) receiverRadio.isChecked = true
        else senderRadio.isChecked = true

        root.addView(sectionTitle("Pair"))
        pairInput = EditText(this).apply {
            hint = "6-digit Pair Code"
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

        advancedButton = Button(this).apply {
            text = "Advanced"
            setOnClickListener {
                advancedPanel.visibility =
                    if (advancedPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                text = if (advancedPanel.visibility == View.VISIBLE) "Hide Advanced" else "Advanced"
            }
        }
        root.addView(advancedButton)

        advancedPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, dp(4), 0, dp(8))
        }
        advancedPanel.addView(sectionTitle("Delivery"))

        val receiverTypeGroup = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        pushRadio = RadioButton(this).apply { text = "Push (FCM) • recommended" }
        compatibilityRadio = RadioButton(this).apply { text = "Compatibility (v0.5)" }
        receiverTypeGroup.addView(pushRadio)
        receiverTypeGroup.addView(compatibilityRadio)
        advancedPanel.addView(receiverTypeGroup)

        if (Prefs.receiverTransport(this) == Prefs.RECEIVER_STABLE) {
            compatibilityRadio.isChecked = true
        } else {
            pushRadio.isChecked = true
        }

        fcmSettingsPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, 0)
        }

        relayKeyStatus = TextView(this)
        fcmSettingsPanel.addView(relayKeyStatus)

        relayTokenInput = EditText(this).apply {
            hint = "Private key"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(Prefs.relayToken(this@MainActivity))
        }
        fcmSettingsPanel.addView(relayTokenInput)

        relayKeyButton = Button(this).apply {
            setOnClickListener {
                if (editingRelayKey) {
                    relayTokenInput.setText(Prefs.relayToken(this@MainActivity))
                    editingRelayKey = false
                } else {
                    editingRelayKey = true
                    relayTokenInput.visibility = View.VISIBLE
                    relayTokenInput.requestFocus()
                    relayTokenInput.selectAll()
                }
                updateRelayKeyUi()
            }
        }
        fcmSettingsPanel.addView(relayKeyButton)

        fcmSettingsPanel.addView(TextView(this).apply {
            text = "Relay URL"
            setPadding(0, dp(6), 0, 0)
        })
        relayUrlInput = EditText(this).apply {
            hint = Prefs.DEFAULT_RELAY_URL
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(Prefs.relayUrl(this@MainActivity))
        }
        fcmSettingsPanel.addView(relayUrlInput)
        advancedPanel.addView(fcmSettingsPanel)
        root.addView(advancedPanel)

        senderPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        senderPanel.addView(Button(this).apply {
            text = "Notification Access"
            setOnClickListener { openNotificationAccess() }
        })
        senderPanel.addView(Button(this).apply {
            text = "Apps"
            setOnClickListener {
                startActivity(Intent(this@MainActivity, AppSelectionActivity::class.java))
            }
        })
        senderPanel.addView(Button(this).apply {
            text = "Send Test"
            setOnClickListener {
                if (!saveSettings(false)) return@setOnClickListener
                if (Prefs.mode(this@MainActivity) != Prefs.MODE_SENDER) {
                    toast("Select Sender")
                    return@setOnClickListener
                }
                if (Prefs.receiverTransport(this@MainActivity) == Prefs.RECEIVER_PUSH &&
                    !Prefs.relayConfigured(this@MainActivity)
                ) {
                    showRelaySetup()
                    toast("Enter private key")
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
                ) { ok ->
                    runOnUiThread { toast(if (ok) "Sent" else "Send failed") }
                }
            }
        })
        senderInfo = TextView(this).apply { setPadding(0, dp(5), 0, dp(6)) }
        senderPanel.addView(senderInfo)
        root.addView(senderPanel)

        receiverPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        receiverPanel.addView(Button(this).apply {
            text = "Start"
            setOnClickListener {
                if (!saveSettings(false)) return@setOnClickListener
                if (Prefs.mode(this@MainActivity) != Prefs.MODE_RECEIVER) {
                    toast("Select Receiver")
                    return@setOnClickListener
                }
                if (Prefs.receiverTransport(this@MainActivity) == Prefs.RECEIVER_PUSH &&
                    !Prefs.relayConfigured(this@MainActivity)
                ) {
                    showRelaySetup()
                    toast("Enter private key")
                    return@setOnClickListener
                }

                Prefs.setReceiverEnabled(this@MainActivity, true)
                applyReceiverRuntime()
                toast(if (Prefs.receiverTransport(this@MainActivity) == Prefs.RECEIVER_STABLE) "Compatibility started" else "Receiver started")
                updateInfo()
            }
        })
        receiverPanel.addView(Button(this).apply {
            text = "Stop"
            setOnClickListener {
                Prefs.setReceiverEnabled(this@MainActivity, false)
                stopService(Intent(this@MainActivity, ReceiverService::class.java))
                FcmTransport.sync(this@MainActivity)
                toast("Stopped")
                updateInfo()
            }
        })
        receiverInfo = TextView(this).apply { setPadding(0, dp(5), 0, dp(6)) }
        receiverPanel.addView(receiverInfo)
        root.addView(receiverPanel)

        modeGroup.setOnCheckedChangeListener { _, _ -> updatePanels() }
        receiverTypeGroup.setOnCheckedChangeListener { _, _ ->
            updateDeliveryOptions()
            updateInfo()
        }

        updatePanels()
        updateDeliveryOptions()
        updateRelayKeyUi()
        updateInfo()

        if (Prefs.mode(this) == Prefs.MODE_SENDER ||
            Prefs.receiverTransport(this) == Prefs.RECEIVER_PUSH
        ) {
            FcmTransport.refreshToken(this) { runOnUiThread { updateInfo() } }
        }
    }

    override fun onResume() {
        super.onResume()

        when (Prefs.mode(this)) {
            Prefs.MODE_SENDER -> {
                FcmTransport.sync(this) { runOnUiThread { updateInfo() } }
                FcmTransport.refreshToken(this) { runOnUiThread { updateInfo() } }
                MirrorNotificationListener.refresh(this)
            }

            Prefs.MODE_RECEIVER -> if (Prefs.receiverEnabled(this) &&
                Prefs.receiverTransport(this) == Prefs.RECEIVER_PUSH
            ) {
                FcmTransport.sync(this) { runOnUiThread { updateInfo() } }
                FcmTransport.refreshToken(this) { runOnUiThread { updateInfo() } }
            }
        }

        if (::senderInfo.isInitialized) {
            updatePanels()
            updateDeliveryOptions()
            updateRelayKeyUi()
            updateInfo()
        }
    }

    private fun saveSettings(showToast: Boolean): Boolean {
        val code = pairInput.text.toString().trim()
        if (!CryptoBox.isValidPairCode(code)) {
            pairInput.error = "6 digits"
            if (showToast) toast("Enter a 6-digit Pair Code")
            return false
        }

        pairInput.error = null
        val oldMode = Prefs.mode(this)
        val mode = if (receiverRadio.isChecked) Prefs.MODE_RECEIVER else Prefs.MODE_SENDER
        val transport = if (compatibilityRadio.isChecked) Prefs.RECEIVER_STABLE else Prefs.RECEIVER_PUSH

        if (oldMode != mode && mode == Prefs.MODE_RECEIVER) {
            Prefs.setReceiverEnabled(this, false)
        }

        Prefs.setMode(this, mode)
        Prefs.setPairCode(this, code)
        Prefs.setRelayToken(this, relayTokenInput.text.toString())
        Prefs.setRelayUrl(this, relayUrlInput.text.toString())
        Prefs.setReceiverTransport(this, transport)
        editingRelayKey = false

        if (mode == Prefs.MODE_SENDER) {
            Prefs.setReceiverEnabled(this, false)
            stopService(Intent(this, ReceiverService::class.java))
            FcmTransport.sync(this)
            MirrorNotificationListener.refresh(this)
        } else if (Prefs.receiverEnabled(this)) {
            applyReceiverRuntime()
        } else {
            stopService(Intent(this, ReceiverService::class.java))
            FcmTransport.sync(this)
        }

        if (showToast) toast("Saved")
        updatePanels()
        updateDeliveryOptions()
        updateRelayKeyUi()
        updateInfo()
        return true
    }

    private fun applyReceiverRuntime() {
        if (Prefs.mode(this) != Prefs.MODE_RECEIVER || !Prefs.receiverEnabled(this)) return

        if (Prefs.receiverTransport(this) == Prefs.RECEIVER_STABLE) {
            FcmTransport.sync(this)
            stopService(Intent(this, ReceiverService::class.java))
            startForegroundService(Intent(this, ReceiverService::class.java))
        } else {
            stopService(Intent(this, ReceiverService::class.java))
            FcmTransport.sync(this) { ok ->
                runOnUiThread {
                    if (!ok) toast("FCM setup failed")
                    updateInfo()
                }
            }
        }
    }

    private fun updatePanels() {
        if (!::senderPanel.isInitialized || !::receiverPanel.isInitialized) return
        val sender = senderRadio.isChecked
        senderPanel.visibility = if (sender) View.VISIBLE else View.GONE
        receiverPanel.visibility = if (sender) View.GONE else View.VISIBLE
    }

    private fun updateDeliveryOptions() {
        if (!::fcmSettingsPanel.isInitialized) return
        fcmSettingsPanel.visibility = if (compatibilityRadio.isChecked) View.GONE else View.VISIBLE
    }

    private fun updateRelayKeyUi() {
        if (!::relayTokenInput.isInitialized || !::relayKeyStatus.isInitialized || !::relayKeyButton.isInitialized) return
        val saved = Prefs.relayToken(this).length >= 24
        relayKeyStatus.text = if (saved) "Private key saved ✓" else "Private key"
        relayTokenInput.visibility = if (!saved || editingRelayKey) View.VISIBLE else View.GONE
        relayKeyButton.visibility = if (saved) View.VISIBLE else View.GONE
        relayKeyButton.text = if (editingRelayKey) "Cancel" else "Change key"
    }

    private fun showRelaySetup() {
        advancedPanel.visibility = View.VISIBLE
        advancedButton.text = "Hide Advanced"
        editingRelayKey = true
        updateDeliveryOptions()
        updateRelayKeyUi()
        relayTokenInput.requestFocus()
    }

    private fun updateInfo() {
        if (!::senderInfo.isInitialized || !::receiverInfo.isInitialized) return

        val access = hasNotificationAccess()
        val apps = if (Prefs.forwardAllApps(this)) "All apps" else "${Prefs.selectedApps(this).size} apps"
        val compatibility = compatibilityRadio.isChecked
        val delivery = if (compatibility) "Compatibility" else "FCM"
        val setup = if (!compatibility && !Prefs.relayConfigured(this)) " • setup needed" else ""

        senderInfo.text = "Access ${if (access) "ON" else "OFF"} • $apps • $delivery$setup"

        val enabled = Prefs.mode(this) == Prefs.MODE_RECEIVER && Prefs.receiverEnabled(this)
        receiverInfo.text = "$delivery • ${if (enabled) "ON" else "OFF"}$setup"
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
        setPadding(0, dp(12), 0, dp(4))
    }

    private fun toast(value: String) = Toast.makeText(this, value, Toast.LENGTH_SHORT).show()

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
