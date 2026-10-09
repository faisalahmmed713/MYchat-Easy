package com.digitalaidit.mychateasy

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.os.Build
import java.io.PrintWriter
import java.io.StringWriter

/** If the app crashes, keep the reason and show it the next time the app opens, so it can be screenshotted and fixed. */
object CrashReport {
    private const val PREFS = "mychat_crash"
    private var installed = false

    fun install(ctx: Context) {
        if (installed) return
        installed = true
        val app = ctx.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            try {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                val version = try { app.packageManager.getPackageInfo(app.packageName, 0).versionName } catch (_: Exception) { "?" }
                val text = "MYchat Easy $version · Android ${Build.VERSION.RELEASE} · ${Build.MANUFACTURER} ${Build.MODEL}\n" +
                    "Thread: ${thread.name}\n\n" + sw.toString().take(6000)
                app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("last", text).commit()
            } catch (_: Throwable) { }
            previous?.uncaughtException(thread, e)
        }
    }

    fun showIfAny(act: Activity) {
        val sp = act.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val text = sp.getString("last", null) ?: return
        sp.edit().remove("last").apply()
        AlertDialog.Builder(act)
            .setTitle("MYchat Easy closed unexpectedly")
            .setMessage("Please take a screenshot of this and send it to support@digitalaidit.com.\n\n" + text.take(1800))
            .setPositiveButton("Copy details") { _, _ -> copyText(act, text) }
            .setNegativeButton("Close", null)
            .show()
    }
}
