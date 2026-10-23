package com.azrael.galleryflow

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.view.Gravity
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.text.InputType

/**
 * Optional bridge to a user-configured Round Sync COPY task.
 * Round Sync owns remote authentication (including unofficial TeraBox backends).
 * Starting the task is NOT proof that any cloud upload finished.
 */
object RoundSyncBridge {
    private const val PREFS = "round_sync_bridge"
    private const val ENABLED = "enabled"
    private const val TASK_ID = "task_id"
    private const val LAST_RESULT = "last_result"

    fun enabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ENABLED, false)
    fun taskId(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(TASK_ID, -1)
    fun lastResult(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(LAST_RESULT, "Never requested").orEmpty()

    fun configure(context: Context, use: Boolean, id: Int) {
        require(!use || id >= 0) { "Enter the Round Sync task ID first" }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(ENABLED, use).putInt(TASK_ID, id).apply()
    }

    fun trigger(context: Context): String {
        val id = taskId(context)
        if (!enabled(context) || id < 0) return "Set up and enable Round Sync first."
        // Round Sync documents these external task-service fields.
        val intent = Intent("START_TASK").apply {
            setClassName("de.felixnuesse.extract", "ca.pkay.rcloneexplorer.Services.SyncService")
            putExtra("task", id)
            putExtra("notification", true)
        }
        val result = try {
            context.startForegroundService(intent)
            "Requested Round Sync task #$id. Verify completion in Round Sync."
        } catch (t: Exception) {
            "Round Sync task could not start: ${t.message ?: t.javaClass.simpleName}"
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(LAST_RESULT, result).apply()
        return result
    }

    fun showSettings(activity: Activity) {
        val panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 12, 32, 12)
        }
        panel.addView(TextView(activity).apply {
            text = "Create a COPY task in Round Sync first. Set local source: Downloads/GalleryFlow/ and remote destination: your TeraBox folder. Use COPY, not mirror/sync-delete."
        })
        val check = CheckBox(activity).apply {
            text = "Request backup after downloads"
            isChecked = enabled(activity)
        }
        panel.addView(check)
        panel.addView(TextView(activity).apply { text = "Round Sync task ID" })
        val input = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
            setSingleLine(true)
            if (taskId(activity) >= 0) setText(taskId(activity).toString())
            hint = "Task ID (from Round Sync task menu)"
        }
        panel.addView(input)
        panel.addView(TextView(activity).apply {
            text = "The NDUS cookie, if your TeraBox Round Sync fork needs one, stays in Round Sync; never put it in this app. Starting a task is not cloud-upload verification. " + lastResult(activity)
        })
        AlertDialog.Builder(activity).setTitle("Round Sync / TeraBox")
            .setView(panel)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save", null)
            .create().apply {
                setOnShowListener {
                    getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        try {
                            val id = input.text.toString().trim().toIntOrNull() ?: -1
                            configure(activity, check.isChecked, id)
                            dismiss()
                            Toast.makeText(activity, "Round Sync settings saved", Toast.LENGTH_SHORT).show()
                        } catch (e: IllegalArgumentException) {
                            Toast.makeText(activity, e.message ?: "Invalid task ID", Toast.LENGTH_LONG).show()
                        }
                    }
                }
                show()
            }
    }
}
