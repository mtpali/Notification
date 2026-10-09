package com.mtpali.notification

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.app.AlertDialog

/** Keeps every action reachable even when System UI has only three action slots. */
class NotificationActionsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tag = intent.getStringExtra(ActionCommandReceiver.EXTRA_LOCAL_TAG).orEmpty()
        val generation = intent.getStringExtra(ActionCommandReceiver.EXTRA_GENERATION).orEmpty()
        val payload = MirrorNotifier.currentPayload(this, tag)
        if (payload == null || payload.generation != generation || !SyncRepository.isCurrent(this, tag, generation) ||
            Prefs.mode(this) != Prefs.MODE_RECEIVER || !Prefs.receiverEnabled(this)) {
            Toast.makeText(this, "Original notification changed or disappeared", Toast.LENGTH_LONG).show()
            finish(); return
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (20 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }
        setContentView(ScrollView(this).apply { addView(root) })
        root.addView(TextView(this).apply { text = "${payload.appName} • ${payload.title}"; textSize = 22f })
        root.addView(TextView(this).apply { text = payload.text })
        root.addView(TextView(this).apply { text = "Actions run on Sender. Calls use Sender's phone app." })
        val selectedId = intent.getStringExtra(ActionCommandReceiver.EXTRA_ACTION_ID).orEmpty()
        val actions = if (selectedId.isBlank()) payload.actions else payload.actions.filter { it.id == selectedId }
        actions.forEachIndexed { index, action ->
            root.addView(TextView(this).apply { text = action.title.ifBlank { "Action ${index + 1}" }; textSize = 18f })
            if (action.unsupportedReason.isNotBlank()) {
                root.addView(TextView(this).apply { text = action.unsupportedReason })
                return@forEachIndexed
            }
            val fields = action.inputs.associate { input ->
                val edit = EditText(this).apply {
                    hint = input.label.ifBlank { action.title }
                    if (!input.freeForm) {
                        isFocusable = false
                        isCursorVisible = false
                        setOnClickListener { choose(input.choices, this) }
                    }
                }
                root.addView(edit)
                if (input.freeForm && input.choices.isNotEmpty()) root.addView(Button(this).apply {
                    text = "Choose suggested text"
                    setOnClickListener { choose(input.choices, edit) }
                })
                input.key to edit
            }
            root.addView(Button(this).apply {
                text = action.title.ifBlank { "Run action" }
                setOnClickListener {
                    val values = fields.mapValues { it.value.text.toString() }
                    if (!ActionPolicy.validResults(action.inputs, values)) {
                        Toast.makeText(this@NotificationActionsActivity, "Each input needs 1–1000 characters or a supplied choice", Toast.LENGTH_LONG).show()
                        return@setOnClickListener
                    }
                    isEnabled = false
                    val command = CommandPayload(CommandPayload.TYPE_ACTION, payload.packageName, payload.notificationKey,
                        sourcePostTime = payload.postTime, generation = generation, actionId = action.id,
                        sourceUserId = payload.sourceUserId, inputResults = values)
                    ActionCommandReceiver.queue(this@NotificationActionsActivity, command, tag) { accepted ->
                        runOnUiThread {
                            isEnabled = true
                            Toast.makeText(this@NotificationActionsActivity,
                                if (accepted) "Action queued" else "Action could not be queued; see connection status", Toast.LENGTH_LONG).show()
                            if (accepted) finish()
                        }
                    }
                }
            })
        }
    }

    private fun choose(choices: List<String>, edit: EditText) {
        AlertDialog.Builder(this).setItems(choices.toTypedArray()) { _, which -> edit.setText(choices[which]) }.show()
    }
}
