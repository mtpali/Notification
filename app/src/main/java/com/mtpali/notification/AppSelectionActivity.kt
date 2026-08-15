package com.mtpali.notification

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class AppSelectionActivity : Activity() {
    private val appChecks = linkedMapOf<String, CheckBox>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
        }
        setContentView(outer)

        outer.addView(TextView(this).apply {
            text = "Apps"
            textSize = 24f
            setPadding(0, 0, 0, dp(8))
        })

        val allApps = CheckBox(this).apply {
            text = "All apps"
            isChecked = Prefs.forwardAllApps(this@AppSelectionActivity)
        }
        outer.addView(allApps)

        val listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val scroll = ScrollView(this).apply {
            addView(listContainer)
        }
        outer.addView(
            scroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolveInfos = packageManager.queryIntentActivities(launcherIntent, 0)
        val apps = linkedMapOf<String, String>()

        resolveInfos.forEach { info ->
            val pkg = info.activityInfo?.packageName ?: return@forEach
            if (pkg == packageName) return@forEach
            val label = info.loadLabel(packageManager)?.toString()?.ifBlank { pkg } ?: pkg
            apps[pkg] = label
        }

        val selected = Prefs.selectedApps(this)
        apps.entries.sortedBy { it.value.lowercase() }.forEach { (pkg, label) ->
            val check = CheckBox(this).apply {
                text = label
                contentDescription = "$label ($pkg)"
                isChecked = allApps.isChecked || pkg in selected
                isEnabled = !allApps.isChecked
                setPadding(0, dp(4), 0, dp(4))
            }
            appChecks[pkg] = check
            listContainer.addView(check)
        }

        allApps.setOnCheckedChangeListener { _, checked ->
            appChecks.values.forEach { check ->
                check.isEnabled = !checked
                if (checked) check.isChecked = true
            }
        }

        outer.addView(Button(this).apply {
            text = "Save"
            setOnClickListener {
                if (allApps.isChecked) {
                    Prefs.setForwardAllApps(this@AppSelectionActivity, true)
                    Prefs.setSelectedApps(this@AppSelectionActivity, emptySet())
                } else {
                    val packages = appChecks.filterValues { it.isChecked }.keys
                    Prefs.setForwardAllApps(this@AppSelectionActivity, false)
                    Prefs.setSelectedApps(this@AppSelectionActivity, packages)
                }
                Toast.makeText(this@AppSelectionActivity, "Saved", Toast.LENGTH_SHORT).show()
                finish()
            }
        })
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
