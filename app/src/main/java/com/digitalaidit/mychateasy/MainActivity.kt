package com.digitalaidit.mychateasy

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.speech.RecognizerIntent
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

class MainActivity : Activity(), VoiceHost {
    private lateinit var s: Store
    private lateinit var scroll: ScrollView
    private lateinit var content: LinearLayout
    private lateinit var nav: LinearLayout
    private lateinit var activeLine: TextView
    private lateinit var header: LinearLayout
    private lateinit var signinBox: LinearLayout
    private var promoClosed = false     // ✕ hides the promo until the app is opened again
    private var tab = "home"
    private var voiceCb: ((String) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        C.init(this)
        s = Store(this)
        Account.appVersion = try { packageManager.getPackageInfo(packageName, 0).versionName ?: "" } catch (e: Exception) { "" }
        CrashReport.install(this)
        tab = intent?.getStringExtra("tab") ?: if (Config.ORDER.none { s.hasKey(it) }) "ai" else "home"

        val root = vbox(this).apply { setBackgroundColor(C.panel) }

        // header
        header = hbox(this).apply { setPadding(dp(18), dp(12), dp(18), dp(12)) }
        val logo = ImageView(this).apply { setImageResource(applicationInfo.icon) }
        header.addView(logo, LinearLayout.LayoutParams(dp(40), dp(40)))
        val titles = vbox(this)
        titles.add(text(this, "MYchat Easy", 18f, C.ink, true))
        activeLine = text(this, "", 12.5f, C.muted)
        titles.add(activeLine, 1)
        header.add(titles, 12, 0, 1f)
        val fb = text(this, "💬 Feedback", 12.5f, C.brand, true).apply {
            background = ripple(rounded(this@MainActivity, C.hover, 10))
            setPadding(dp(10), dp(6), dp(10), dp(6))
            isClickable = true
            setOnClickListener { tab = "more"; render() }
        }
        header.add(fb, 8, ViewGroup.LayoutParams.WRAP_CONTENT)
        root.add(header)

        scroll = ScrollView(this).apply { setBackgroundColor(C.bg); isFillViewport = true }
        content = vbox(this).apply { setPadding(dp(16), dp(16), dp(16), dp(28)) }
        scroll.addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(C.panel)
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        root.add(nav)

        signinBox = vbox(this).apply { visibility = View.GONE; setBackgroundColor(0xFF0D1433.toInt()) }
        root.addView(signinBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        edgeToEdge(this, root)
        render()
        CrashReport.showIfAny(this)
    }

    override fun onResume() {
        super.onResume()
        if (::content.isInitialized) CrashReport.showIfAny(this)
        if (::content.isInitialized && Account.signedIn(s)) Account.dailyCheckIn(this, s)
        if (::content.isInitialized && (tab == "home" || tab == "more")) render()
    }

    override fun onDestroy() {
        Speaker.stop()
        super.onDestroy()
    }

    // ---------- floating bubble ----------
    private fun bubbleCard(): LinearLayout {
        val c = card(this)
        val on = BubbleService.isEnabled(this)
        val head = hbox(this)
        head.add(text(this, "Floating bubble", 16f, C.ink, true), 0, 0, 1f)
        val pill = text(this, if (on) "ON" else "OFF", 11f, if (on) C.ok else C.muted, true).apply {
            background = rounded(this@MainActivity, C.soft, 99, C.line)
            setPadding(dp(9), dp(3), dp(9), dp(3))
        }
        head.add(pill, 0, ViewGroup.LayoutParams.WRAP_CONTENT)
        c.add(head)
        if (on) {
            c.add(text(this, "Tap any text box in any app and the MYchat Easy bubble appears next to it. Tap the bubble to translate, rewrite or reply, then Replace. Long-press the bubble to hide it in that app.", 13.5f, C.muted), 6)
            val sw = Switch(this).apply {
                text = "Show the bubble"
                setTextColor(C.ink)
                isChecked = s.bubbleOn
                setOnCheckedChangeListener { _, v -> s.bubbleOn = v }
            }
            c.add(sw, 10)
        } else {
            c.add(text(this, "Turn it on once and a MYchat Easy bubble appears next to the text box in WhatsApp, Messenger, Facebook and every other app.", 13.5f, C.muted), 6)
            c.add(primary(this, "Turn on the bubble") { showBubbleDisclosure() }, 12)
        }
        return c
    }

    // ---------- microphone for voice inside the bubble ----------
    private fun micCard(): LinearLayout {
        val c = card(this)
        c.add(text(this, "🎤 Voice in the bubble", 15.5f, C.ink, true))
        c.add(text(this, "Allow the microphone once so you can speak instead of typing in any chat.", 13.5f, C.muted), 6)
        c.add(primary(this, "Allow microphone") { requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), REQ_MIC) }, 10)
        return c
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_MIC) render()
    }

    // ---------- keeping the bubble running ----------
    private fun ignoringBattery(): Boolean = try {
        (getSystemService(POWER_SERVICE) as android.os.PowerManager).isIgnoringBatteryOptimizations(packageName)
    } catch (e: Exception) { true }

    private fun batteryCard(): LinearLayout {
        val c = card(this)
        c.add(text(this, "Keep the bubble always ready", 15.5f, C.ink, true))
        c.add(text(this, "Your phone may stop the bubble to save battery. Allow MYchat Easy to run in the background so the bubble appears instantly in every chat. " +
            "On vivo also turn on Settings › Battery › Background power consumption › MYchat Easy › Allow, and lock MYchat Easy in Recent apps.", 13.5f, C.muted), 6)
        val row = flow(this)
        row.addView(primary(this, "Allow background activity") {
            try {
                startActivity(Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            } catch (e: Exception) { openAppInfo() }
        })
        row.addView(ghost(this, "App settings") { openAppInfo() })
        c.add(row, 10)
        return c
    }

    private fun bubbleStoppedCard(): LinearLayout {
        val c = card(this)
        c.add(text(this, "⚠️ The bubble was stopped by your phone", 15.5f, C.ink, true))
        c.add(text(this, "It's turned on in Accessibility but isn't running. Open Accessibility, turn MYchat Easy bubble off and on again, then allow background activity below so it doesn't stop again.", 13.5f, C.muted), 6)
        val row = flow(this)
        row.addView(primary(this, "Open Accessibility") { openAccessibilitySettings() })
        if (!ignoringBattery()) row.addView(ghost(this, "Allow background activity") {
            try { startActivity(Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))) } catch (e: Exception) { openAppInfo() }
        })
        c.add(row, 10)
        return c
    }

    // vivo, Xiaomi, Oppo, realme and others need an extra "pop-up windows" permission for the bubble to open the panel
    private fun popupBrand(): Boolean {
        val b = (android.os.Build.MANUFACTURER + " " + android.os.Build.BRAND).lowercase()
        return listOf("vivo", "iqoo", "xiaomi", "redmi", "poco", "oppo", "realme", "oneplus", "huawei", "honor", "tecno", "infinix", "itel").any { b.contains(it) }
    }

    private fun popupCard(): LinearLayout {
        val c = card(this)
        val blocked = s.popupBlocked
        c.add(text(this, if (blocked) "⚠️ Allow pop-ups so the bubble can open" else "One more step for the bubble", 15.5f, C.ink, true))
        c.add(text(this, (if (blocked) "Your phone stopped the bubble from opening MYchat Easy. " else "On ${android.os.Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} phones the bubble needs one extra permission. ") +
            "Tap the button, open Permissions (or Other permissions), and allow \"Display pop-up windows while running in the background\" and \"Display over other apps\".", 13.5f, C.muted), 6)
        val row = flow(this)
        row.addView(primary(this, "Allow pop-ups") { openAppInfo() })
        if (!blocked) row.addView(ghost(this, "Done") { s.popupBlocked = false; it_hidePopupHint() })
        c.add(row, 10)
        return c
    }

    private fun it_hidePopupHint() {
        getSharedPreferences("mychat_easy", MODE_PRIVATE).edit().putBoolean("popupHintDone", true).apply()
        render()
    }

    private fun showBubbleDisclosure() {
        AlertDialog.Builder(this)
            .setTitle("Allow the MYchat Easy bubble")
            .setMessage(
                "MYchat Easy uses Android's Accessibility service to:\n\n" +
                "• notice when you tap a text box, so it can show the bubble next to it\n" +
                "• read that text box only when you tap the bubble\n" +
                "• put the result back when you tap Replace\n\n" +
                "Password fields are always ignored. Your text goes only to the AI you chose, only when you pick an action. Nothing else on your screen is read, stored or shared.\n\n" +
                "On the next screen, open Installed apps (or Downloaded apps), tap MYchat Easy bubble and turn it on."
            )
            .setPositiveButton("Continue") { _, _ -> openAccessibilitySettings() }
            .setNegativeButton("Not now", null)
            .show()
    }

    private fun openAccessibilitySettings() {
        try {
            startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
            toast(this, "Find MYchat Easy bubble and turn it on")
        } catch (e: ActivityNotFoundException) {
            toast(this, "Open Settings › Accessibility and turn on MYchat Easy bubble")
        }
    }

    private fun openAppInfo() {
        try {
            startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        } catch (e: ActivityNotFoundException) {
            toast(this, "Open Settings › Apps › MYchat Easy")
        }
    }

    // ---------- voice ----------
    override fun startVoice(langName: String, onText: (String) -> Unit) {
        voiceCb = onText
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Config.langCode(langName) ?: "en-US")
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak in $langName")
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(i, REQ_VOICE)
        } catch (e: ActivityNotFoundException) {
            toast(this, "Voice typing needs the Google app or another speech service on your phone.")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_VOICE && resultCode == RESULT_OK) {
            val said = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!said.isNullOrBlank()) voiceCb?.invoke(said)
        }
    }

    // ---------- layout ----------
    private fun render() {
        val needs = !Account.signedIn(s)
        header.visibility = if (needs) View.GONE else View.VISIBLE
        scroll.visibility = if (needs) View.GONE else View.VISIBLE
        nav.visibility = if (needs) View.GONE else View.VISIBLE
        signinBox.visibility = if (needs) View.VISIBLE else View.GONE
        if (needs) { renderSignin(); return }
        val p = s.provider
        activeLine.text = "${Config.provider(p).label} · ${s.model(p)}"
        renderNav()
        content.removeAllViews()
        when (tab) {
            "home" -> renderHome()
            "ai" -> renderAi()
            "lang" -> renderLang()
            else -> renderMore()
        }
        scroll.scrollTo(0, 0)
    }

    private fun renderNav() {
        nav.removeAllViews()
        listOf("home" to ("✦" to "Home"), "ai" to ("⚙" to "AI"), "lang" to ("文" to "Language"), "more" to ("☰" to "More")).forEach { (id, pair) ->
            val on = id == tab
            val item = vbox(this).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(6), 0, dp(6))
                background = ripple(rounded(this@MainActivity, if (on) C.hover else Color.TRANSPARENT, 12))
                isClickable = true
                setOnClickListener { tab = id; render() }
            }
            item.addView(text(this, pair.first, 18f, if (on) C.brand else C.muted, true).apply { gravity = Gravity.CENTER })
            item.addView(text(this, pair.second, 11.5f, if (on) C.brand else C.muted, on).apply { gravity = Gravity.CENTER })
            nav.addView(item, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    private fun section(title: String) = content.add(label(this, title.uppercase()), 18)

    // ---------- sign-in screen ----------
    private fun renderSignin() {
        signinBox.removeAllViews()
        val head = hbox(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(C.G1, C.G2))
            setPadding(dp(22), dp(36), dp(22), dp(28))
        }
        val logoBox = android.widget.FrameLayout(this).apply { background = rounded(this@MainActivity, 0x29FFFFFF, 16) }
        val logo = ImageView(this).apply { setImageResource(applicationInfo.icon) }
        logoBox.addView(logo, android.widget.FrameLayout.LayoutParams(dp(46), dp(46)).apply { setMargins(dp(6), dp(6), dp(6), dp(6)) })
        head.addView(logoBox)
        val t = vbox(this)
        t.add(text(this, "MYchat Easy", 22f, Color.WHITE, true))
        t.add(text(this, "Translate, rewrite & write in any app", 13.5f, 0xD9FFFFFF.toInt()), 2)
        head.add(t, 14, 0, 1f)
        signinBox.add(head)

        val body = vbox(this).apply { setPadding(dp(22), dp(28), dp(22), dp(22)); gravity = Gravity.CENTER_HORIZONTAL }
        val status = text(this, "Sign in to use all MYchat Easy features", 13f, 0xFF9AA6D8.toInt()).apply { gravity = Gravity.CENTER }
        val gbtn = hbox(this).apply {
            gravity = Gravity.CENTER
            background = ripple(GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF2448E6.toInt(), 0xFF6A3BE8.toInt())).apply { cornerRadius = dp(14).toFloat() }, 0x33FFFFFF)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            isClickable = true
        }
        val g = text(this, "G", 15f, 0xFF4285F4.toInt(), true).apply {
            gravity = Gravity.CENTER
            background = rounded(this@MainActivity, Color.WHITE, 99)
        }
        gbtn.addView(g, LinearLayout.LayoutParams(dp(28), dp(28)))
        gbtn.add(text(this, "Sign in with Google", 16f, Color.WHITE, true), 12, ViewGroup.LayoutParams.WRAP_CONTENT)
        gbtn.setOnClickListener {
            status.text = "Opening Google sign-in…"; status.setTextColor(0xFF9AA6D8.toInt())
            gbtn.isEnabled = false
            try {
                Account.signIn(this, s) { err ->
                    gbtn.isEnabled = true
                    if (err == null) { toast(this, "Signed in as ${s.accountEmail}"); tab = if (Config.ORDER.none { s.hasKey(it) }) "ai" else "home"; render() }
                    else { status.text = err; status.setTextColor(0xFFFF8A96.toInt()) }
                }
            } catch (e: Throwable) {
                gbtn.isEnabled = true
                status.text = "Google sign-in couldn't start: ${e.javaClass.simpleName}"; status.setTextColor(0xFFFF8A96.toInt())
            }
        }
        body.add(gbtn)
        body.add(status, 14)
        body.add(text(this, "We never see your password. Your text and API keys stay on your phone.", 12f, 0xFF7380B4.toInt()).apply { gravity = Gravity.CENTER }, 18)
        body.add(link(this, "Privacy policy") { openUrl("https://digitalaidit.com/mychat-easy-privacy") }.apply { setTextColor(0xFFB9C3FF.toInt()); gravity = Gravity.CENTER }, 6, ViewGroup.LayoutParams.WRAP_CONTENT)
        signinBox.add(body)
    }

    // ---------- promo (from the sheet) ----------
    private fun promoCard(): View {
        val holder = vbox(this)
        fun fill(p: Promo?) {
            holder.removeAllViews()
            if (p == null || promoClosed) { holder.visibility = View.GONE; return }
            holder.visibility = View.VISIBLE
            val c = hbox(this).apply {
                gravity = Gravity.TOP
                background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                    intArrayOf(if (C.dark) 0xFF1E2448.toInt() else 0xFFE9EDFF.toInt(), if (C.dark) 0xFF2A1F4A.toInt() else 0xFFF1E9FF.toInt())).apply {
                    cornerRadius = dp(16).toFloat(); setStroke(dp(1), C.line)
                }
                setPadding(dp(12), dp(12), dp(8), dp(12))
            }
            val img = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; visibility = View.GONE; clipToOutline = true; background = rounded(this@MainActivity, C.soft, 12) }
            c.addView(img, LinearLayout.LayoutParams(dp(64), dp(64)).apply { rightMargin = dp(12) })
            if (p.image.isNotBlank()) bg {
                val bmp = try {
                    val conn = java.net.URL(p.image).openConnection() as java.net.HttpURLConnection
                    conn.connectTimeout = 10000; conn.readTimeout = 10000
                    conn.inputStream.use { stream -> android.graphics.BitmapFactory.decodeStream(java.io.BufferedInputStream(stream, 2 * 1024 * 1024)) }
                } catch (e: Exception) { null }
                if (bmp != null) ui { img.setImageBitmap(bmp); img.visibility = View.VISIBLE }
            }
            val t = vbox(this)
            if (p.title.isNotBlank()) t.add(text(this, p.title, 15f, C.ink, true))
            if (p.text.isNotBlank()) t.add(text(this, p.text, 13f, C.muted), 3)
            if (p.link.isNotBlank()) {
                val b = primary(this, p.button) {
                    Account.promoClick(this, s, p)
                    openUrl(p.link)
                }.apply { setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f); setPadding(dp(12), dp(6), dp(12), dp(6)) }
                t.add(b, 8, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            c.add(t, 0, 0, 1f)
            c.add(link(this, "✕") { promoClosed = true; holder.visibility = View.GONE }.apply { setTextColor(C.muted) }, 4, ViewGroup.LayoutParams.WRAP_CONTENT)
            holder.add(c)
        }
        fill(Account.cachedPromo(s))
        bg { val fresh = Account.refreshPromo(s); ui { if (!isFinishing) fill(fresh) } }
        return holder
    }

    // ---------- account & feedback ----------
    private fun accountAndFeedback() {
        if (!Config.accountRequired()) return
        section("Account")
        val a = card(this)
        val row = hbox(this)
        val initial = text(this, (s.accountName.ifBlank { s.accountEmail }).trim().take(1).uppercase(), 16f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(C.G1, C.G2)).apply { shape = GradientDrawable.OVAL }
        }
        row.addView(initial, LinearLayout.LayoutParams(dp(40), dp(40)))
        val who = vbox(this)
        who.add(text(this, s.accountName.ifBlank { "Signed in" }, 15f, C.ink, true))
        who.add(text(this, s.accountEmail, 13f, C.muted))
        row.add(who, 12, 0, 1f)
        row.add(link(this, "Sign out") {
            AlertDialog.Builder(this).setMessage("Sign out of MYchat Easy?")
                .setPositiveButton("Sign out") { _, _ -> Account.signOut(this, s); render() }
                .setNegativeButton("Cancel", null).show()
        }.apply { setTextColor(C.err) }, 0, ViewGroup.LayoutParams.WRAP_CONTENT)
        a.add(row)
        content.add(a, 8)

        section("Send feedback")
        val f = card(this)
        var rating = 0
        val stars = hbox(this)
        val starViews = (1..5).map { i ->
            text(this, "★", 30f, C.line).apply {
                setPadding(dp(2), 0, dp(4), 0)
                isClickable = true
                contentDescription = "$i star${if (i > 1) "s" else ""}"
            }
        }
        fun paint() { starViews.forEachIndexed { i, v -> v.setTextColor(if (i < rating) 0xFFF5B301.toInt() else C.line) } }
        starViews.forEachIndexed { i, v -> v.setOnClickListener { rating = i + 1; paint() }; stars.add(v, 0, ViewGroup.LayoutParams.WRAP_CONTENT) }
        f.add(stars)
        val msg = input(this, "What do you like? What should we improve?", multi = true)
        f.add(msg, 8)
        val status = text(this, "", 13f, C.muted)
        val send = primary(this, "Send feedback") { }
        send.setOnClickListener {
            status.text = "Sending…"; status.setTextColor(C.muted); send.isEnabled = false
            Account.sendFeedback(this, s, rating, msg.text.toString()) { err ->
                if (err == null) {
                    msg.setText(""); rating = 0; paint()
                    status.text = "Thank you! Your feedback was sent."; status.setTextColor(C.ok)
                    send.postDelayed({ send.isEnabled = true }, 30000)   // avoid accidental double sends
                } else { status.text = err; status.setTextColor(C.err); send.isEnabled = true }
            }
        }
        f.add(send, 12, ViewGroup.LayoutParams.WRAP_CONTENT)
        f.add(status, 8)
        f.add(text(this, "Sent with your Google email so Digital Aid IT can reply.", 12f, C.muted), 4)
        content.add(f, 8)
    }

    // ---------- Home ----------
    private fun renderHome() {
        content.add(promoCard())
        val tip = vbox(this).apply {
            background = gradient(this@MainActivity, 16)
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        tip.add(text(this, "Works in every app", 16f, Color.WHITE, true))
        tip.add(text(this, "Turn on the floating bubble below and it appears next to the text box in any app.", 13.5f, 0xE6FFFFFF.toInt()), 4)
        tip.isClickable = true
        tip.setOnClickListener { tab = "more"; render() }
        if (!BubbleService.isEnabled(this)) content.add(tip)   // once the bubble is turned on, this hint isn't needed
        if (!BubbleService.isEnabled(this)) content.add(bubbleCard(), 12)
        else if (BubbleService.instance == null) content.add(bubbleStoppedCard(), 12)
        else if (!ignoringBattery()) content.add(batteryCard(), 12)
        if (BubbleService.isEnabled(this) && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) content.add(micCard(), 12)

        if (Config.ORDER.none { s.hasKey(it) }) {
            val warn = card(this)
            warn.add(text(this, "Add a free API key first", 15.5f, C.ink, true))
            warn.add(text(this, "Choose Gemini or Groq in the AI tab. Both give free keys in about a minute.", 13.5f, C.muted), 4)
            warn.add(primary(this, "Set up AI") { tab = "ai"; render() }, 10)
            content.add(warn, 12)
        }

        val panel = ToolPanel(this, s, "", null, this) { v -> scroll.post { scroll.smoothScrollTo(0, (v.top + (v.parent as View).top - dp(12)).coerceAtLeast(0)) } }
        content.add(panel.view, 14)
    }

    // ---------- AI ----------
    private fun renderAi() {
        section("Choose your AI")
        val list = vbox(this).apply { background = rounded(this@MainActivity, C.panel, 16, C.line) }
        Config.ORDER.forEachIndexed { i, id ->
            val pr = Config.provider(id)
            val on = id == s.provider
            val row = hbox(this).apply {
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = ripple(rounded(this@MainActivity, Color.TRANSPARENT, 16))
                isClickable = true
                setOnClickListener { s.provider = id; render() }
            }
            val radio = View(this).apply {
                background = if (on) android.graphics.drawable.LayerDrawable(arrayOf(
                    rounded(this@MainActivity, Color.TRANSPARENT, 99, null).apply { setStroke(dp(2), C.brand) },
                    android.graphics.drawable.InsetDrawable(rounded(this@MainActivity, C.brand, 99), dp(4))
                )) else rounded(this@MainActivity, Color.TRANSPARENT, 99, null).apply { setStroke(dp(2), C.line) }
            }
            row.addView(radio, LinearLayout.LayoutParams(dp(18), dp(18)))
            val names = vbox(this)
            names.add(text(this, pr.label.replace(" (Free)", ""), 15.5f, C.ink, true))
            names.add(text(this, if (s.hasKey(id)) s.model(id).ifBlank { "Set a model" } else "No key yet", 12.5f, C.muted))
            row.add(names, 12, 0, 1f)
            val badge = text(this, if (pr.free) "FREE" else if (id == "custom") "ANY" else "PAID", 10.5f,
                if (pr.free) C.ok else C.muted, true).apply {
                background = rounded(this@MainActivity, if (pr.free) (C.ok and 0x00FFFFFF) or 0x22000000 else C.soft, 99)
                setPadding(dp(8), dp(2), dp(8), dp(2))
            }
            row.add(badge, 8, ViewGroup.LayoutParams.WRAP_CONTENT)
            val dot = View(this).apply { background = rounded(this@MainActivity, if (s.hasKey(id)) C.ok else C.line, 99) }
            row.addView(dot, LinearLayout.LayoutParams(dp(9), dp(9)).apply { leftMargin = dp(10) })
            if (i > 0) list.addView(View(this).apply { setBackgroundColor(C.line) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
            list.add(row)
        }
        content.add(list, 8)

        val p = s.provider
        val pr = Config.provider(p)
        val c = card(this)
        val status = text(this, "", 13f, C.muted)

        if (p == "custom") {
            c.add(label(this, "BASE URL (OPENAI-COMPATIBLE)"))
            val base = input(this, "https://openrouter.ai/api/v1").apply {
                setText(s.customBase)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            }
            base.addTextChangedListener(watcher { s.customBase = it })
            c.add(base, 6)
        }

        val keyHead = hbox(this)
        keyHead.add(label(this, "API KEY"), 0, 0, 1f)
        keyHead.add(link(this, "Get a key ↗") { openUrl(pr.keyUrl) }, 0, ViewGroup.LayoutParams.WRAP_CONTENT)
        c.add(keyHead, if (p == "custom") 14 else 0)
        val keyRow = hbox(this)
        val key = input(this, "Paste your API key").apply {
            setText(s.key(p))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        key.addTextChangedListener(watcher { s.setKey(p, it); status.text = "Key saved"; status.setTextColor(C.ok) })
        keyRow.add(key, 0, 0, 1f)
        keyRow.add(link(this, "Show") {
            val hidden = key.inputType and InputType.TYPE_TEXT_VARIATION_PASSWORD != 0
            key.inputType = InputType.TYPE_CLASS_TEXT or (if (hidden) InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD else InputType.TYPE_TEXT_VARIATION_PASSWORD)
            key.setSelection(key.text.length)
        }, 6, ViewGroup.LayoutParams.WRAP_CONTENT)
        c.add(keyRow, 6)

        // model picker
        val modelHead = hbox(this)
        modelHead.add(label(this, "MODEL"), 0, 0, 1f)
        val loadBtn = link(this, "↻ Load latest models") { }
        loadBtn.setOnClickListener {
            loadBtn.text = "Loading…"
            bg {
                try {
                    val ids = Ai.listModels(s, p)
                    ui { toast(this, if (ids.isEmpty()) "No models returned. Use Other model… to type one." else "Loaded ${ids.size} models"); render() }
                } catch (e: Exception) {
                    val m = e.message ?: "Couldn't load models"
                    ui { loadBtn.text = "↻ Load latest models"; status.text = m; status.setTextColor(C.err) }
                }
            }
        }
        modelHead.add(loadBtn, 0, ViewGroup.LayoutParams.WRAP_CONTENT)
        c.add(modelHead, 14)

        val cached = s.modelList(p)
        val choices = (if (cached.isNotEmpty()) cached else pr.models).toMutableList()
        val current = s.model(p)
        if (current.isNotBlank() && current !in choices) choices.add(0, current)
        val other = input(this, "Type a model name").apply { setText(if (current in choices) "" else current) }
        other.addTextChangedListener(watcher { if (it.isNotBlank()) { s.setModel(p, it); activeLine.text = "${pr.label} · $it" } })
        val labels = choices + "Other model…"
        val startIndex = if (choices.isEmpty()) labels.size - 1 else choices.indexOf(current).coerceAtLeast(0)
        other.visibility = if (choices.isEmpty()) View.VISIBLE else View.GONE
        c.add(spinner(this, labels, startIndex) { i ->
            if (i == labels.size - 1) { other.visibility = View.VISIBLE; other.requestFocus() }
            else {
                other.visibility = View.GONE
                s.setModel(p, choices[i])
                activeLine.text = "${pr.label} · ${choices[i]}"
                status.text = "Model saved"; status.setTextColor(C.ok)
            }
        }, 6)
        c.add(other, 8)

        val testRow = hbox(this)
        val test = primary(this, "Test connection") { }
        test.setOnClickListener {
            status.text = "Testing…"; status.setTextColor(C.muted)
            bg {
                try {
                    val r = Ai.run(s, Task("translate", text = "Bonjour, comment ça va ?", target = "English", noHistory = true), p)
                    ui { status.text = "Connected ✓ \"${r.text.take(30)}\" · ${r.inTok + r.outTok} tokens"; status.setTextColor(C.ok); activeLine.text = "${pr.label} · ${s.model(p)}" }
                } catch (e: Exception) {
                    val m = e.message ?: "Test failed"
                    ui { status.text = m; status.setTextColor(C.err) }
                }
            }
        }
        testRow.add(test, 0, ViewGroup.LayoutParams.WRAP_CONTENT)
        c.add(testRow, 16)
        c.add(status, 8)
        c.add(text(this, pr.note, 12.5f, C.muted), 8)
        content.add(c, 12)

        renderUsage()
    }

    private fun renderUsage() {
        section("This month")
        val c = card(this)
        val by = s.usage().getJSONObject("by")
        var uses = 0; var tok = 0; var usd = 0.0; var unknown = false
        val rows = vbox(this)
        Config.ORDER.forEach { id ->
            val r = by.optJSONObject(id) ?: return@forEach
            uses += r.optInt("count"); tok += r.optInt("in") + r.optInt("out"); usd += r.optDouble("usd", 0.0)
            if (r.optBoolean("unknown")) unknown = true
            val cost = if (Config.provider(id).free) "Free" else if (r.optBoolean("unknown") && r.optDouble("usd", 0.0) == 0.0) "—" else s.formatCost(r.optDouble("usd", 0.0))
            val row = hbox(this)
            row.add(text(this, Config.provider(id).label, 14f), 0, 0, 1f)
            row.add(text(this, "${r.optInt("count")} uses · ${compact(r.optInt("in") + r.optInt("out"))} tok · $cost", 13f, C.muted), 0, ViewGroup.LayoutParams.WRAP_CONTENT)
            rows.add(row, 6)
        }
        val stats = hbox(this)
        listOf("Uses" to uses.toString(), "Tokens" to compact(tok), "Est. cost" to if (uses == 0) "—" else s.formatCost(usd) + if (unknown) "+" else "").forEachIndexed { i, (k, v) ->
            val b = vbox(this).apply { background = rounded(this@MainActivity, C.soft, 12); setPadding(dp(10), dp(8), dp(10), dp(8)) }
            b.add(text(this, v, 17f, C.ink, true))
            b.add(text(this, k, 11.5f, C.muted))
            stats.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (i > 0) leftMargin = dp(8) })
        }
        c.add(stats)
        c.add(rows, 6)

        val cur = hbox(this)
        val code = input(this, "USD").apply { setText(s.currency); filters = arrayOf(android.text.InputFilter.LengthFilter(3)) }
        val rate = input(this, "Rate per 1 USD").apply { setText(if (s.currency == "USD") "" else s.rate.toString()); inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL }
        code.addTextChangedListener(watcher { if (Regex("^[A-Za-z]{3}$").matches(it)) s.currency = it })
        rate.addTextChangedListener(watcher { it.toDoubleOrNull()?.let { v -> s.rate = v } })
        cur.add(code, 0, 0, 1f)
        cur.add(rate, 8, 0, 1.4f)
        c.add(label(this, "CURRENCY · RATE PER 1 USD"), 12)
        c.add(cur, 6)
        val foot = hbox(this)
        foot.add(text(this, "Token counts are exact. Costs are estimates.", 12f, C.muted), 0, 0, 1f)
        foot.add(link(this, "Reset") { s.resetUsage(); render() }, 0, ViewGroup.LayoutParams.WRAP_CONTENT)
        c.add(foot, 8)
        content.add(c, 8)
    }

    private fun compact(n: Int): String = when {
        n >= 1_000_000 -> String.format("%.1fM", n / 1_000_000.0)
        n >= 10_000 -> "${n / 1000}k"
        else -> n.toString()
    }

    // ---------- Language ----------
    private fun renderLang() {
        section("Your languages")
        val c = card(this)
        val langs = s.languages
        val f = flow(this)
        langs.forEachIndexed { i, l ->
            f.addView(chip(this, "${i + 1}  $l  ✕", false) {
                if (langs.size <= 1) { toast(this, "Keep at least one language"); return@chip }
                AlertDialog.Builder(this).setMessage("Remove $l?")
                    .setPositiveButton("Remove") { _, _ -> s.languages = langs.filter { it != l }; render() }
                    .setNegativeButton("Cancel", null).show()
            })
        }
        c.add(f)
        val addRow = hbox(this)
        val field = AutoCompleteTextView(this).apply {
            hint = "Add a language, e.g. Spanish"
            setHintTextColor(C.muted); setTextColor(C.ink)
            background = rounded(this@MainActivity, C.soft, 12, C.line)
            setPadding(dp(13), dp(11), dp(13), dp(11))
            isSingleLine = true
            threshold = 1
            setAdapter(ArrayAdapter(this@MainActivity, android.R.layout.simple_dropdown_item_1line, Config.SUGGESTED_LANGS.filter { it !in langs }))
        }
        addRow.add(field, 0, 0, 1f)
        addRow.add(ghost(this, "Add") {
            val n = field.text.toString().trim().replace(Regex("\\s+"), " ")
            if (n.isNotEmpty() && langs.none { it.equals(n, true) }) { s.languages = langs + n; render() }
        }, 8, ViewGroup.LayoutParams.WRAP_CONTENT)
        c.add(addRow, 12)
        content.add(c, 8)

        section("Translation")
        val t = card(this)
        val (a, b) = s.autoPair
        t.add(label(this, "AUTO TRANSLATE SWITCHES BETWEEN"))
        val pair = hbox(this)
        pair.add(spinner(this, langs, langs.indexOf(a)) { i -> s.autoPair = listOf(langs[i], s.autoPair[1]) }, 0, 0, 1f)
        pair.add(text(this, "⇄", 16f, C.muted), 8, ViewGroup.LayoutParams.WRAP_CONTENT)
        pair.add(spinner(this, langs, langs.indexOf(b)) { i -> s.autoPair = listOf(s.autoPair[0], langs[i]) }, 8, 0, 1f)
        t.add(pair, 6)
        t.add(label(this, "TONE FOR TRANSLATING AND WRITING"), 16)
        val tones = flow(this)
        Config.TONES.forEach { (id, name) -> tones.addView(chip(this, name, s.tone == id) { s.tone = id; render() }) }
        t.add(tones, 6)
        val voices = Config.voiceLanguages(langs)
        t.add(label(this, "I SPEAK IN (VOICE INPUT)"), 16)
        t.add(spinner(this, voices, voices.indexOf(s.voiceLang)) { i -> s.voiceLang = voices[i] }, 6)
        content.add(t, 8)
    }

    // ---------- More ----------
    private fun renderMore() {
        accountAndFeedback()
        section("Floating bubble")
        content.add(bubbleCard(), 8)
        val hidden = s.bubbleHidden
        if (hidden.isNotEmpty()) {
            val hc = card(this)
            hc.add(text(this, "Hidden in these apps", 14.5f, C.ink, true))
            hidden.sorted().forEach { pkg ->
                val name = try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() } catch (e: Exception) { pkg }
                val row = hbox(this)
                row.add(text(this, name, 14f), 0, 0, 1f)
                row.add(link(this, "Show again") { s.bubbleHidden = s.bubbleHidden - pkg; render() }, 0, ViewGroup.LayoutParams.WRAP_CONTENT)
                hc.add(row, 6)
            }
            content.add(hc, 8)
        }
        if (!BubbleService.isEnabled(this)) {
            val help = card(this)
            help.add(text(this, "Can't turn it on?", 14.5f, C.ink, true))
            help.add(text(this, "On Android 13 and newer, apps installed from an APK need one extra step: open App info, tap ⋮ (top right), choose Allow restricted settings, then turn on MYchat Easy bubble in Accessibility.", 13.5f, C.muted), 4)
            val row = flow(this)
            row.addView(ghost(this, "Open App info") { openAppInfo() })
            row.addView(ghost(this, "Open Accessibility") { openAccessibilitySettings() })
            help.add(row, 10)
            content.add(help, 8)
        }

        section("Other ways to use it")
        val how = card(this)
        listOf(
            "1" to "Select text in any app, then tap MYchat Easy in the menu (tap ⋮ first if you don't see it). Not every app shows this option.",
            "2" to "Share any text to MYchat Easy from the share menu.",
            "3" to "Open this app and use the tool on the Home tab, then Copy the result."
        ).forEach { (n, line) ->
            val row = hbox(this).apply { gravity = Gravity.TOP }
            row.add(text(this, n, 13f, C.brand, true).apply { minWidth = dp(30) }, 0, ViewGroup.LayoutParams.WRAP_CONTENT)
            row.add(text(this, line, 14f), 4, 0, 1f)
            how.add(row, 8)
        }
        content.add(how, 8)

        renderTemplates()

        section("History")
        val h = card(this)
        val sw = Switch(this).apply {
            text = "Save history on this phone"
            setTextColor(C.ink)
            isChecked = s.saveHistory
            setOnCheckedChangeListener { _, v -> s.saveHistory = v }
        }
        h.add(sw)
        val items = s.history()
        if (items.length() == 0) h.add(text(this, "Nothing yet. Your translations, rewrites and drafts will appear here.", 13.5f, C.muted), 10)
        for (i in 0 until minOf(items.length(), 50)) {
            val it = items.optJSONObject(i) ?: continue
            val e = vbox(this)
            val top = hbox(this)
            top.add(text(this, it.optString("label"), 13.5f, C.ink, true), 0, 0, 1f)
            top.add(text(this, DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it.optLong("at"))), 11.5f, C.muted), 0, ViewGroup.LayoutParams.WRAP_CONTENT)
            e.add(top)
            val out = it.optString("output")
            e.add(text(this, out, 14f).apply { maxLines = 4; ellipsize = android.text.TextUtils.TruncateAt.END }, 4)
            val acts = hbox(this)
            acts.add(link(this, "Copy") { copyText(this, out) }, 0, ViewGroup.LayoutParams.WRAP_CONTENT)
            acts.add(link(this, "Share") { shareText(this, out) }, 4, ViewGroup.LayoutParams.WRAP_CONTENT)
            e.add(acts, 2)
            h.addView(View(this).apply { setBackgroundColor(C.line) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply { topMargin = dp(10) })
            h.add(e, 8)
        }
        if (items.length() > 0) h.add(link(this, "Clear history") { s.clearHistory(); render() }, 8, ViewGroup.LayoutParams.WRAP_CONTENT)
        content.add(h, 8)

        section("About")
        val about = card(this)
        about.add(text(this, "MYchat Easy by Digital Aid IT · version ${Account.appVersion}", 14.5f, C.ink, true))
        about.add(text(this, "Bubble: " + when {
            !BubbleService.isEnabled(this) -> "off"
            BubbleService.instance == null -> "turned on but stopped by the phone"
            else -> "running"
        }, 13f, C.muted), 2)
        about.add(text(this, "Your keys, settings and history stay on this phone. Text goes only to the AI you choose, only when you tap.", 13f, C.muted), 4)
        val links = hbox(this)
        links.add(link(this, "digitalaidit.com") { openUrl("https://digitalaidit.com") }, 0, ViewGroup.LayoutParams.WRAP_CONTENT)
        links.add(link(this, "Support") { openUrl("mailto:support@digitalaidit.com") }, 6, ViewGroup.LayoutParams.WRAP_CONTENT)
        about.add(links, 6)
        content.add(about, 8)
    }

    private fun renderTemplates() {
        section("Saved texts")
        val c = card(this)
        c.add(text(this, "Save replies you send often, then copy them in one tap.", 13f, C.muted))
        val tpls = s.templates()
        for (i in 0 until tpls.length()) {
            val t = tpls.optJSONObject(i) ?: continue
            val body = t.optString("text")
            val row = vbox(this).apply { background = rounded(this@MainActivity, C.soft, 12, C.line); setPadding(dp(12), dp(10), dp(12), dp(10)) }
            val top = hbox(this)
            top.add(text(this, t.optString("key"), 14f, C.ink, true), 0, 0, 1f)
            top.add(link(this, "Copy") { copyText(this, body) }, 0, ViewGroup.LayoutParams.WRAP_CONTENT)
            top.add(link(this, "Delete") {
                val next = JSONArray()
                for (j in 0 until tpls.length()) if (j != i) next.put(tpls.get(j))
                s.setTemplates(next); render()
            }, 2, ViewGroup.LayoutParams.WRAP_CONTENT)
            row.add(top)
            row.add(text(this, body, 13.5f).apply { maxLines = 3; ellipsize = android.text.TextUtils.TruncateAt.END }, 2)
            c.add(row, 8)
        }
        val name = input(this, "Name, e.g. Thanks")
        val body = input(this, "Text, e.g. Thanks for reaching out! I'll get back to you shortly.", multi = true)
        c.add(name, 12)
        c.add(body, 8)
        c.add(primary(this, "Save text") {
            val n = name.text.toString().trim()
            val b = body.text.toString()
            if (n.isEmpty() || b.isBlank()) { toast(this, "Add a name and the text"); return@primary }
            val next = JSONArray()
            for (j in 0 until tpls.length()) { val o = tpls.optJSONObject(j); if (o != null && !o.optString("key").equals(n, true)) next.put(o) }
            next.put(JSONObject().put("key", n).put("text", b))
            s.setTemplates(next); toast(this, "Saved"); render()
        }, 10)
        content.add(c, 8)
    }

    // ---------- helpers ----------
    private fun openUrl(u: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u))) } catch (e: ActivityNotFoundException) { toast(this, "No app can open this link") }
    }

    private fun watcher(onChange: (String) -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(e: Editable?) { onChange(e?.toString()?.trim() ?: "") }
    }

    companion object {
        const val REQ_VOICE = 7001
        const val REQ_MIC = 7002
    }
}
