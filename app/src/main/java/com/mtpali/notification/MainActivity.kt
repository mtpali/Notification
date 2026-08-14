package com.mtpali.notification

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
    private lateinit var pushRadio: RadioButton
    private lateinit var stableRadio: RadioButton
    private lateinit var hiddenRadio: RadioButton
    private lateinit var pairInput: EditText
    private lateinit var relayUrlInput: EditText
    private lateinit var relayTokenInput: EditText
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

        root.addView(sectionTitle("Firebase Relay"))
        root.addView(TextView(this).apply {
            text = "Firebase Function URL is preconfigured. Use the same private Relay key on both phones."
            setPadding(0, 0, 0, dp(4))
        })
        relayUrlInput = EditText(this).apply {
            hint = Prefs.DEFAULT_RELAY_URL
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(Prefs.relayUrl(this@MainActivity))
        }
        root.addView(relayUrlInput)
        relayTokenInput = EditText(this).apply {
            hint = "Relay key"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(Prefs.relayToken(this@MainActivity))
        }
        root.addView(relayTokenInput)
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
                if (!Prefs.relayConfigured(this@MainActivity)) {
                    toast("Configure Firebase Relay")
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
                    runOnUiThread { toast(if (ok) "Sent" else "Relay failed") }
                }
            }
        })
        senderInfo = TextView(this).apply { setPadding(0, dp(4), 0, 0) }
        root.addView(senderInfo)

        root.addView(sectionTitle("Receiver"))
        val receiverTypeGroup = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        pushRadio = RadioButton(this).apply { text = "Push (FCM) • lowest battery" }
        stableRadio = RadioButton(this).apply { text = "Stable • persistent status" }
        hiddenRadio = RadioButton(this).apply { text = "Hidden • experimental background service" }
        receiverTypeGroup.addView(pushRadio)
        receiverTypeGroup.addView(stableRadio)
        receiverTypeGroup.addView(hiddenRadio)
        root.addView(receiverTypeGroup)

        when (Prefs.receiverTransport(this)) {
            Prefs.RECEIVER_PUSH -> pushRadio.isChecked = true
            Prefs.RECEIVER_HIDDEN -> hiddenRadio.isChecked = true
            else -> stableRadio.isChecked = true
        }

        root.addView(TextView(this).apply {
            text = "Push is recommended for lowest battery use. Notification Access is only required for Hidden."
            setPadding(0, 0, 0, dp(4))
        })
        root.addView(Button(this).apply {
            text = "Notification Access (Hidden only)"
            setOnClickListener { openNotificationAccess() }
        })
        root.addView(Button(this).apply {
            text = "Copy FCM token"
            setOnClickListener { copyFcmToken() }
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
                    when (Prefs.receiverTransport(this@MainActivity)) {
                        Prefs.RECEIVER_PUSH -> "Push started"
                        Prefs.RECEIVER_HIDDEN -> "Hidden started"
                        else -> "Started"
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
                FcmTransport.sync(this@MainActivity)
                MirrorNotificationListener.refresh(this@MainActivity)
                toast("Stopped")
                updateInfo()
            }
        })
        receiverInfo = TextView(this).apply { setPadding(0, dp(4), 0, 0) }
        root.addView(receiverInfo)

        updateInfo()
        FcmTransport.refreshToken(this) { runOnUiThread { updateInfo() } }
    }

    override fun onResume() {
        super.onResume()

        when (Prefs.mode(this)) {
            Prefs.MODE_SENDER -> {
                FcmTransport.sync(this) { runOnUiThread { updateInfo() } }
                FcmTransport.refreshToken(this) { runOnUiThread { updateInfo() } }
                MirrorNotificationListener.refresh(this)
            }

            Prefs.MODE_RECEIVER -> if (Prefs.receiverEnabled(this)) {
                when (Prefs.receiverTransport(this)) {
                    Prefs.RECEIVER_PUSH -> {
                        FcmTransport.sync(this) { runOnUiThread { updateInfo() } }
                        FcmTransport.refreshToken(this) { runOnUiThread { updateInfo() } }
                    }

                    Prefs.RECEIVER_HIDDEN -> {
                        if (hasNotificationAccess()) {
                            startHiddenReceiverService()
                            MirrorNotificationListener.refresh(this)
                        }
                    }
                }
            }
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
        val transport = when {
            pushRadio.isChecked -> Prefs.RECEIVER_PUSH
            hiddenRadio.isChecked -> Prefs.RECEIVER_HIDDEN
            else -> Prefs.RECEIVER_STABLE
        }

        if (oldMode != mode && mode == Prefs.MODE_RECEIVER) {
            Prefs.setReceiverEnabled(this, false)
        }

        Prefs.setMode(this, mode)
        Prefs.setPairCode(this, code)
        Prefs.setRelayUrl(this, relayUrlInput.text.toString())
        Prefs.setRelayToken(this, relayTokenInput.text.toString())
        Prefs.setReceiverTransport(this, transport)

        if (mode == Prefs.MODE_SENDER) {
            Prefs.setReceiverEnabled(this, false)
            stopService(Intent(this, ReceiverService::class.java))
            stopService(Intent(this, HiddenReceiverService::class.java))
            FcmTransport.sync(this)
            MirrorNotificationListener.refresh(this)
        } else if (transport != Prefs.RECEIVER_PUSH) {
            FcmTransport.sync(this)
        }

        if (showToast) toast("Saved")
        updateInfo()
        return true
    }

    private fun applyReceiverRuntime() {
        if (Prefs.mode(this) != Prefs.MODE_RECEIVER || !Prefs.receiverEnabled(this)) return

        when (Prefs.receiverTransport(this)) {
            Prefs.RECEIVER_PUSH -> {
                stopService(Intent(this, ReceiverService::class.java))
                stopService(Intent(this, HiddenReceiverService::class.java))
                MirrorNotificationListener.refresh(this)
                FcmTransport.sync(this) { ok ->
                    runOnUiThread {
                        if (!ok) toast("FCM setup failed")
                        updateInfo()
                    }
                }
            }

            Prefs.RECEIVER_HIDDEN -> {
                FcmTransport.sync(this)
                stopService(Intent(this, ReceiverService::class.java))
                startHiddenReceiverService()
                MirrorNotificationListener.refresh(this)
            }

            else -> {
                FcmTransport.sync(this)
                stopService(Intent(this, HiddenReceiverService::class.java))
                MirrorNotificationListener.refresh(this)
                stopService(Intent(this, ReceiverService::class.java))
                startForegroundService(Intent(this, ReceiverService::class.java))
            }
        }
    }

    private fun startHiddenReceiverService() {
        try {
            startService(Intent(this, HiddenReceiverService::class.java))
        } catch (_: IllegalStateException) {
            MirrorNotificationListener.refresh(this)
        }
    }

    private fun copyFcmToken() {
        FcmTransport.refreshToken(this) { token ->
            runOnUiThread {
                if (token.isBlank()) {
                    toast("FCM token not ready")
                    return@runOnUiThread
                }
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("FCM token", token))
                toast("FCM token copied")
                updateInfo()
            }
        }
    }

    private fun updateInfo() {
        val access = hasNotificationAccess()
        val apps = if (Prefs.forwardAllApps(this)) "All apps" else "${Prefs.selectedApps(this).size} apps"
        val relay = if (Prefs.relayConfigured(this)) "relay ready" else "relay pending"
        val fcmTopic = if (Prefs.fcmSubscribedTopic(this).isBlank()) "push pending" else "push ready"
        senderInfo.text = "Access: ${if (access) "ON" else "OFF"} • $apps • $relay • $fcmTopic"

        val transport = when (Prefs.receiverTransport(this)) {
            Prefs.RECEIVER_PUSH -> "Push"
            Prefs.RECEIVER_HIDDEN -> "Hidden"
            else -> "Stable"
        }
        val enabled = Prefs.mode(this) == Prefs.MODE_RECEIVER && Prefs.receiverEnabled(this)
        val pushStatus = if (Prefs.receiverTransport(this) == Prefs.RECEIVER_PUSH) {
            val token = if (Prefs.fcmToken(this).isBlank()) "token pending" else "token ready"
            val topic = if (Prefs.fcmSubscribedTopic(this).isBlank()) "topic pending" else "topic ready"
            " • $token • $topic • $relay"
        } else ""
        receiverInfo.text = "$transport • ${if (enabled) "ON" else "OFF"}$pushStatus"
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
