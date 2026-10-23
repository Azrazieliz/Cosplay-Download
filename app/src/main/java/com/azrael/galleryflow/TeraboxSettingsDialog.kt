package com.azrael.galleryflow

import android.app.Activity
import android.app.AlertDialog
import android.text.InputType
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Local opt-in cloud settings: never request TeraBox password or website cookies. */
object TeraboxSettingsDialog {
    fun show(activity: Activity, onSaved: () -> Unit = {}) {
        val padding = (18 * activity.resources.displayMetrics.density).toInt()
        val panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding / 2, padding, padding / 2)
        }
        val active = CheckBox(activity).apply {
            text = "Upload downloaded media to TeraBox"
            isChecked = TeraboxSettings.enabled(activity)
        }
        panel.addView(active)
        panel.addView(TextView(activity).apply {
            text = "Official TeraBox Open Platform OAuth access token"
            setPadding(0, padding, 0, 0)
        })
        val token = EditText(activity).apply {
            hint = if (TeraboxSettings.accessToken(activity) == null) {
                "Enter authorized access token"
            } else {
                "Token securely saved — leave blank to keep"
            }
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSingleLine = true
        }
        panel.addView(token)
        panel.addView(TextView(activity).apply {
            text = "One shared cloud folder for ALL source albums"
            setPadding(0, padding, 0, 0)
        })
        val destination = EditText(activity).apply {
            hint = "/From: Other Applications/YourApp-.../Kyora"
            setText(TeraboxSettings.remoteDirectory(activity))
            isSingleLine = true
        }
        panel.addView(destination)
        panel.addView(TextView(activity).apply {
            text = "Use an existing folder inside the application's allotted TeraBox storage. Kyora uploads everything directly here, without album subfolders. Local album folders stay unchanged. Cloud retries run after periodic Live Sync (~15 minutes, inexact under Android battery restrictions)."
            setPadding(0, padding, 0, 0)
        })
        val scroller = ScrollView(activity).apply { addView(panel) }
        val dialog = AlertDialog.Builder(activity)
            .setTitle("Kyora · TeraBox")
            .setView(scroller)
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Disconnect") { _, _ ->
                TeraboxSettings.clearAccessToken(activity)
                onSaved()
            }
            .setPositiveButton("Save", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    val dest = destination.text.toString().trim()
                    if (dest.isNotBlank()) TeraboxSettings.setRemoteDirectory(activity, dest)
                    val newToken = token.text.toString().trim()
                    if (newToken.isNotBlank()) TeraboxSettings.saveAccessToken(activity, newToken)
                    if (active.isChecked && !TeraboxSettings.isConfigured(activity)) {
                        throw IllegalArgumentException("Authorized token and shared app folder are required")
                    }
                    TeraboxSettings.setEnabled(activity, active.isChecked)
                    dialog.dismiss()
                    onSaved()
                    Toast.makeText(activity, "TeraBox destination saved", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(
                        activity, e.message ?: "Cloud configuration invalid",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
        dialog.show()
    }
}
