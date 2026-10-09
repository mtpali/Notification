package com.mtpali.notification

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.content.pm.PackageManager
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
import com.google.firebase.FirebaseApp

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
    private lateinit var relayCheckStatus: TextView
    private lateinit var relayCheckDetails: Button
    private lateinit var senderPanel: LinearLayout
    private lateinit var receiverPanel: LinearLayout
    private lateinit var advancedPanel: LinearLayout
    private lateinit var fcmSettingsPanel: LinearLayout
    private lateinit var advancedButton: Button
    private lateinit var senderInfo: TextView
    private lateinit var receiverInfo: TextView
    private var editingRelayKey = false
    private lateinit var deliveryStatus: TextView
    private val statusHandler = Handler(Looper.getMainLooper())
    private val statusRefresh = object : Runnable {
        override fun run() {
            if (::deliveryStatus.isInitialized) deliveryStatus.text = Diagnostics.summary(this@MainActivity)
            statusHandler.postDelayed(this, 2_000)
        }
    }

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

        root.addView(sectionTitle("1. Choose this phone"))
        val modeGroup = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        senderRadio = RadioButton(this).apply { text = "Sender" }
        receiverRadio = RadioButton(this).apply { text = "Receiver" }
        modeGroup.addView(senderRadio)
        modeGroup.addView(receiverRadio)
        root.addView(modeGroup)
        root.addView(TextView(this).apply {
            text = "Sender: shares notifications. Receiver: shows them on your second phone."
            textSize = 14f
        })

        if (Prefs.mode(this) == Prefs.MODE_RECEIVER) receiverRadio.isChecked = true
        else senderRadio.isChecked = true

        root.addView(sectionTitle("2. Connect the phones"))
        pairInput = EditText(this).apply {
            hint = "Pair code (same on both phones)"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setText(Prefs.pairCode(this@MainActivity))
        }
        root.addView(pairInput)
        root.addView(Button(this).apply {
            text = "Generate pair code"
            setOnClickListener {
                pairInput.setText(CryptoBox.generatePairCode())
                pairInput.setSelection(pairInput.text.length)
            }
        })

        root.addView(Button(this).apply {
            text = "Save"
            setOnClickListener { saveSettings(true) }
        })
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(Button(this@MainActivity).apply {
                text = "Copy setup"
                setOnClickListener { copySetup() }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(Button(this@MainActivity).apply {
                text = "Paste setup"
                setOnClickListener { pasteSetup() }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        })
        root.addView(TextView(this).apply {
            text = "Copy includes the pair code and private key. Transfer it to your second phone, then tap Paste setup."
            textSize = 13f
        })

        advancedButton = Button(this).apply {
            text = "Connection settings"
            setOnClickListener {
                advancedPanel.visibility =
                    if (advancedPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                text = if (advancedPanel.visibility == View.VISIBLE) "Hide connection settings" else "Connection settings"
            }
        }
        root.addView(advancedButton)

        advancedPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (Prefs.receiverTransport(this@MainActivity) == Prefs.RECEIVER_PUSH &&
                !Prefs.relayConfigured(this@MainActivity)) View.VISIBLE else View.GONE
            setPadding(0, dp(4), 0, dp(8))
        }
        if (advancedPanel.visibility == View.VISIBLE) advancedButton.text = "Hide connection settings"
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
            hint = "Private key (at least 24 ASCII characters)"
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
        relayCheckStatus = TextView(this)
        fcmSettingsPanel.addView(Button(this).apply {
            text = "Check Worker"
            setOnClickListener {
                // Connectivity can be checked before pairing or starting either phone.
                if (!saveRelaySettings()) return@setOnClickListener
                isEnabled = false
                relayCheckStatus.text = "Checking Worker and Firebase…"
                relayCheckDetails.isEnabled = false
                val base = Prefs.relayUrl(this@MainActivity)
                val token = Prefs.relayToken(this@MainActivity)
                val project = runCatching { FirebaseApp.getInstance().options.projectId.orEmpty() }.getOrDefault("")
                RelayDiagnostics.check(base, token, project) { result ->
                    val current = base == Prefs.relayUrl(applicationContext) && token == Prefs.relayToken(applicationContext)
                    if (current) Diagnostics.relayCheck(applicationContext, result)
                    android.util.Log.i("NotificationRelay", result.report)
                    runOnUiThread {
                        if (!isDestroyed) {
                            isEnabled = true
                            renderRelayCheck()
                            if (!current) relayCheckStatus.text = "Connection settings changed; check again"
                        }
                    }
                }
            }
        })
        fcmSettingsPanel.addView(relayCheckStatus)
        relayCheckDetails = Button(this).apply {
            text = "Details / copy report"
            setOnClickListener { showRelayReport() }
        }
        fcmSettingsPanel.addView(relayCheckDetails)
        renderRelayCheck()
        advancedPanel.addView(fcmSettingsPanel)
        root.addView(advancedPanel)

        root.addView(sectionTitle("3. Start sharing"))

        senderPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        senderPanel.addView(Button(this).apply {
            text = "Enable notification access"
            setOnClickListener { openNotificationAccess() }
        })
        senderPanel.addView(Button(this).apply {
            text = "Choose apps"
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
                    runOnUiThread { toast(if (ok) "Queued for sending" else "Could not queue test") }
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

                if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
                    return@setOnClickListener
                }
                startReceiver()
            }
        })
        receiverPanel.addView(Button(this).apply {
            text = "Sync now"
            setOnClickListener {
                if (!saveSettings(false)) return@setOnClickListener
                if (!Prefs.receiverEnabled(this@MainActivity)) { toast("Start Receiver first"); return@setOnClickListener }
                RelayClient.requestSync(this@MainActivity)
                toast("Sync requested")
            }
        })
        receiverPanel.addView(Button(this).apply {
            text = "Notification settings"
            setOnClickListener {
                runCatching { startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)) }
                    .onFailure { toast("Open notification settings for this app") }
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

        deliveryStatus = TextView(this).apply { textSize = 13f; setPadding(0, dp(12), 0, dp(8)) }
        root.addView(deliveryStatus)
        root.addView(Button(this).apply {
            text = "Battery settings"
            setOnClickListener {
                runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                    .onFailure { toast("Open battery settings for this app") }
            }
        })

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
        DeliveryRuntime.sync(this)
        statusHandler.removeCallbacks(statusRefresh)
        statusRefresh.run()

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

    override fun onPause() {
        statusHandler.removeCallbacks(statusRefresh)
        super.onPause()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED && Prefs.mode(this) == Prefs.MODE_RECEIVER) startReceiver()
            else toast("Allow notifications in Notification settings")
        }
    }

    private fun startReceiver() {
        Prefs.setReceiverEnabled(this, true)
        applyReceiverRuntime()
        RelayClient.requestSync(this)
        updateInfo()
    }

    private fun saveSettings(showToast: Boolean): Boolean {
        val code = pairInput.text.toString().trim()
        if (!CryptoBox.isValidPairCode(code)) {
            pairInput.error = "Use a generated pair key or 6-digit legacy code"
            if (showToast) toast("Enter the same pair key on both phones")
            return false
        }

        pairInput.error = null
        val relayBase = runCatching { RelayProtocol.baseUrl(relayUrlInput.text.toString().trim().ifBlank { Prefs.DEFAULT_RELAY_URL }) }
            .getOrNull()
        if (relayBase == null && !compatibilityRadio.isChecked) {
            relayUrlInput.error = "Use a valid HTTPS Worker URL"; return false
        }
        relayUrlInput.error = null
        if (relayBase != null) relayUrlInput.setText(relayBase)
        val oldMode = Prefs.mode(this)
        val mode = if (receiverRadio.isChecked) Prefs.MODE_RECEIVER else Prefs.MODE_SENDER
        val transport = if (compatibilityRadio.isChecked) Prefs.RECEIVER_STABLE else Prefs.RECEIVER_PUSH

        if (oldMode != mode && mode == Prefs.MODE_RECEIVER) {
            Prefs.setReceiverEnabled(this, false)
        }

        Prefs.setMode(this, mode)
        Prefs.setPairCode(this, code)
        resetRelayCheckIfChanged(relayBase, relayTokenInput.text.toString().trim())
        Prefs.setRelayToken(this, relayTokenInput.text.toString())
        if (relayBase != null) Prefs.setRelayUrl(this, relayBase)
        Prefs.setReceiverTransport(this, transport)
        editingRelayKey = false

        if (mode == Prefs.MODE_SENDER) {
            Prefs.setReceiverEnabled(this, false)
            DeliveryRuntime.sync(this)
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

        if (!DeliveryRuntime.sync(this)) toast("Compatibility could not start")
        if (Prefs.receiverTransport(this) == Prefs.RECEIVER_PUSH) {
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
        val saved = RelayProtocol.privateKeyError(Prefs.relayToken(this)).isEmpty()
        relayKeyStatus.text = if (saved) "Private key saved ✓" else "Private key"
        relayTokenInput.visibility = if (!saved || editingRelayKey) View.VISIBLE else View.GONE
        relayKeyButton.visibility = if (saved) View.VISIBLE else View.GONE
        relayKeyButton.text = if (editingRelayKey) "Cancel" else "Change key"
    }

    private fun showRelaySetup() {
        advancedPanel.visibility = View.VISIBLE
        advancedButton.text = "Hide connection settings"
        editingRelayKey = true
        updateDeliveryOptions()
        updateRelayKeyUi()
        relayTokenInput.requestFocus()
    }

    private fun resetRelayCheckIfChanged(base: String?, token: String) {
        if ((base != null && base != Prefs.relayUrl(this)) || token != Prefs.relayToken(this)) {
            Diagnostics.clearRelayCheck(this)
            renderRelayCheck()
        }
    }

    private fun saveRelaySettings(): Boolean {
        val base = runCatching { RelayProtocol.baseUrl(relayUrlInput.text.toString().ifBlank { Prefs.DEFAULT_RELAY_URL }) }.getOrNull()
        if (base == null) {
            relayUrlInput.error = "Use a valid HTTPS Worker URL"
            relayCheckStatus.text = "Use a valid HTTPS Worker URL"
            relayCheckDetails.isEnabled = false
            relayUrlInput.requestFocus()
            return false
        }
        val token = relayTokenInput.text.toString().trim()
        val keyError = RelayProtocol.privateKeyError(token)
        if (keyError.isNotEmpty()) {
            showRelaySetup()
            relayTokenInput.error = keyError
            relayCheckStatus.text = keyError
            relayCheckDetails.isEnabled = false
            return false
        }
        relayUrlInput.error = null
        relayTokenInput.error = null
        resetRelayCheckIfChanged(base, token)
        Prefs.setRelayUrl(this, base)
        Prefs.setRelayToken(this, token)
        relayUrlInput.setText(base)
        editingRelayKey = false
        updateRelayKeyUi()
        updateInfo()
        return true
    }

    private fun copySetup() {
        if (!saveSettings(false)) return
        val encoded = runCatching {
            ConnectionSetup.create(Prefs.pairCode(this), Prefs.relayUrl(this),
                Prefs.relayToken(this), Prefs.receiverTransport(this)).encode()
        }.getOrElse {
            if (Prefs.receiverTransport(this) == Prefs.RECEIVER_PUSH && !Prefs.relayConfigured(this)) showRelaySetup()
            toast(it.message ?: "Complete connection settings first")
            return
        }
        copyText("Notification setup", encoded, sensitive = true)
    }

    private fun pasteSetup() {
        val clipboard = getSystemService(ClipboardManager::class.java)
        val raw = clipboard.primaryClip?.let { if (it.itemCount > 0) it.getItemAt(0).text?.toString() else null }.orEmpty()
        val setup = runCatching { ConnectionSetup.decode(raw) }.getOrElse {
            toast(it.message ?: "Use Copy setup on the first phone")
            return
        }
        // Parse and validate everything before changing any setting. Keep this phone's role.
        pairInput.setText(setup.pairCode)
        relayUrlInput.setText(setup.relayUrl)
        relayTokenInput.setText(setup.privateKey)
        if (setup.transport == Prefs.RECEIVER_STABLE) compatibilityRadio.isChecked = true else pushRadio.isChecked = true
        if (saveSettings(false)) toast("Setup imported. Choose this phone's role, then start.")
    }

    private fun copyText(label: String, value: String, sensitive: Boolean = false) {
        val clip = ClipData.newPlainText(label, value)
        if (sensitive) clip.description.extras = PersistableBundle().apply {
            putBoolean("android.content.extra.IS_SENSITIVE", true)
        }
        getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
        if (Build.VERSION.SDK_INT < 33) toast("Copied")
    }

    private fun renderRelayCheck() {
        if (!::relayCheckStatus.isInitialized || !::relayCheckDetails.isInitialized) return
        relayCheckStatus.text = Diagnostics.lastRelaySummary(this)
        relayCheckDetails.isEnabled = Diagnostics.lastRelayCheck(this).isNotBlank()
    }

    private fun showRelayReport() {
        val report = Diagnostics.lastRelayCheck(this)
        if (report.isBlank()) return
        val content = TextView(this).apply {
            text = report
            textSize = 14f
            setTextIsSelectable(true)
            setPadding(dp(20), dp(12), dp(20), dp(12))
        }
        AlertDialog.Builder(this).setTitle("Connection report")
            .setView(ScrollView(this).apply { addView(content) })
            .setPositiveButton("Copy report") { _, _ -> copyText("Notification connection report", report) }
            .setNegativeButton("Close", null).show()
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
        runCatching { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
            .onFailure { toast("Open Notification Access in Android settings") }
    }

    private fun sectionTitle(value: String) = TextView(this).apply {
        text = value
        textSize = 18f
        setPadding(0, dp(12), 0, dp(4))
    }

    private fun toast(value: String) = Toast.makeText(this, value, Toast.LENGTH_SHORT).show()

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
