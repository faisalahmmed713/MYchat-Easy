package com.digitalaidit.mychateasy

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.speech.RecognizerIntent
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView

/** Opens as a bottom sheet over any app when you pick "MYchat Easy" from the text selection menu or the share menu. */
class ProcessTextActivity : Activity(), VoiceHost {
    private var voiceCb: ((String) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        C.init(this)
        val s = Store(this)
        Account.appVersion = try { packageManager.getPackageInfo(packageName, 0).versionName ?: "" } catch (e: Exception) { "" }
        CrashReport.install(this)

        val action = intent?.action
        val fromBubble = action == ACTION_BUBBLE
        if (fromBubble) BubbleService.instance?.panelOpened()
        val text = when (action) {
            Intent.ACTION_PROCESS_TEXT -> intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            ACTION_BUBBLE -> intent.getStringExtra(EXTRA_TEXT)
            else -> null
        } ?: ""
        val canReplace = fromBubble ||
            (action == Intent.ACTION_PROCESS_TEXT && !intent.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false))

        val root = FrameLayout(this).apply {
            setBackgroundColor(0x80000000.toInt())
            isClickable = true
            setOnClickListener { finish() }
        }

        val sheet = MaxHeightBox(this, 0.9f).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true // keep taps inside the sheet from closing it
            val r = dp(22).toFloat()
            background = GradientDrawable().apply {
                setColor(C.bg)
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
        }

        // handle + header
        val handle = View(this).apply { background = rounded(this@ProcessTextActivity, C.line, 99) }
        sheet.addView(handle, LinearLayout.LayoutParams(dp(40), dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(10) })
        val header = hbox(this).apply { setPadding(dp(18), dp(10), dp(10), dp(4)) }
        val logo = ImageView(this).apply { setImageResource(applicationInfo.icon) }
        header.addView(logo, LinearLayout.LayoutParams(dp(30), dp(30)))
        header.add(text(this, "MYchat Easy", 16.5f, C.ink, true), 10, 0, 1f)
        header.add(link(this, "Settings") {
            startActivity(Intent(this, MainActivity::class.java).putExtra("tab", "ai").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
        }, 0, ViewGroup.LayoutParams.WRAP_CONTENT)
        header.add(link(this, "✕") { finish() }, 2, ViewGroup.LayoutParams.WRAP_CONTENT)
        sheet.add(header)

        val scroll = ScrollView(this)
        val body = vbox(this).apply { setPadding(dp(16), dp(8), dp(16), dp(20)) }

        if (Config.ORDER.none { s.hasKey(it) }) {
            val warn = card(this)
            warn.add(text(this, "Add a free API key first", 15.5f, C.ink, true))
            warn.add(text(this, "Open MYchat Easy, choose Gemini or Groq in the AI tab and paste a free key. Then come back here.", 13.5f, C.muted), 4)
            warn.add(primary(this, "Open settings") {
                startActivity(Intent(this, MainActivity::class.java).putExtra("tab", "ai").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                finish()
            }, 10)
            body.add(warn)
        }

        val panel = ToolPanel(
            this, s, text,
            if (!canReplace) null
            else if (fromBubble) { result ->
                val svc = BubbleService.instance
                if (svc != null) svc.replaceText(result) else { copyText(this, result); toast(this, "Copied. Long-press the text box and tap Paste.") }
                finish()
            }
            else { result -> setResult(RESULT_OK, Intent().putExtra(Intent.EXTRA_PROCESS_TEXT, result)); finish() },
            this
        ) { v -> scroll.post { scroll.smoothScrollTo(0, (v.top + (v.parent as View).top - dp(12)).coerceAtLeast(0)) } }
        body.add(panel.view, 4)
        if (!canReplace && text.isNotBlank()) {
            body.add(text(this, "This app doesn't allow replacing its text, so use Copy and paste it where you need it.", 12f, C.muted), 10)
        }
        scroll.addView(body, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        sheet.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        root.addView(sheet, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        setContentView(root)
        edgeToEdge(this, root)
        window.statusBarColor = Color.TRANSPARENT
    }

    override fun onDestroy() {
        Speaker.stop()
        super.onDestroy()
    }

    companion object {
        const val ACTION_BUBBLE = "com.digitalaidit.mychateasy.BUBBLE"
        const val EXTRA_TEXT = "text"
    }

    override fun startVoice(langName: String, onText: (String) -> Unit) {
        voiceCb = onText
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Config.langCode(langName) ?: "en-US")
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak in $langName")
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(i, MainActivity.REQ_VOICE)
        } catch (e: ActivityNotFoundException) {
            toast(this, "Voice typing needs the Google app or another speech service on your phone.")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == MainActivity.REQ_VOICE && resultCode == RESULT_OK) {
            val said = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!said.isNullOrBlank()) voiceCb?.invoke(said)
        }
    }
}
