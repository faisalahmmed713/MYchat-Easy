package com.digitalaidit.mychateasy

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.ImageView

/**
 * Shows a small MYchat Easy bubble next to whatever text box you are typing in, in any app.
 * Tapping it opens the MYchat Easy panel with that text; Replace writes the result back into the box.
 * Only the focused text box is read, and only when the bubble is tapped. Password fields are ignored.
 */
class BubbleService : AccessibilityService() {

    private class Pending(val node: AccessibilityNodeInfo, val full: String, val start: Int, val end: Int)

    private lateinit var wm: WindowManager
    private lateinit var store: Store
    private val handler = Handler(Looper.getMainLooper())
    private val check = Runnable { refresh() }
    private var bubble: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var target: AccessibilityNodeInfo? = null
    private var targetPkg = ""
    private var pending: Pending? = null
    @Volatile private var panelShown = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        store = Store(this)
        C.init(this)
        CrashReport.install(this)
        handler.post(check)
        applyKeepAlive()
    }

    // ---------- keep alive: a quiet ongoing notification makes phones much less likely to stop the bubble ----------
    var keepAliveActive = false
        private set

    fun applyKeepAlive() {
        if (store.keepAlive) startKeepAlive() else stopKeepAlive()
    }

    private fun startKeepAlive() {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            if (android.os.Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(android.app.NotificationChannel(CHANNEL, "Bubble status", android.app.NotificationManager.IMPORTANCE_MIN).apply {
                    description = "Keeps the MYchat Easy bubble ready in every app"
                    setShowBadge(false)
                })
            }
            val open = android.app.PendingIntent.getActivity(this, 0,
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
            val iconId = resources.getIdentifier("ic_launcher_foreground", "mipmap", packageName).takeIf { it != 0 } ?: applicationInfo.icon
            val n = android.app.Notification.Builder(this, CHANNEL)
                .setSmallIcon(iconId)
                .setContentTitle("MYchat Easy bubble is ready")
                .setContentText("Tap a text box in any app to translate, rewrite or reply.")
                .setContentIntent(open)
                .setOngoing(true)
                .setShowWhen(false)
                .build()
            if (android.os.Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, FGS_SPECIAL_USE)
            else startForeground(NOTIF_ID, n)
            keepAliveActive = true
        } catch (e: Throwable) {
            keepAliveActive = false   // the phone didn't allow it; the bubble still works
        }
    }

    private fun stopKeepAlive() {
        try { if (android.os.Build.VERSION.SDK_INT >= 24) stopForeground(android.app.Service.STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true) } catch (_: Throwable) { }
        keepAliveActive = false
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!::store.isInitialized) return
        if (panelView != null) { hide(); return }
        if (event != null && event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED && store.copyBar && isCopyClick(event)) {
            val pkg = event.packageName?.toString() ?: ""
            if (pkg != packageName && pkg !in store.bubbleHidden) handler.postDelayed({ showCopyBar(pkg) }, 250)
        }
        // Fast path: when a text box is tapped, focused or typed in, show the bubble right away for that box
        if (event != null && store.bubbleOn) {
            val t = event.eventType
            if (t == AccessibilityEvent.TYPE_VIEW_FOCUSED || t == AccessibilityEvent.TYPE_VIEW_CLICKED ||
                t == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED || t == AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED) {
                val src = try { event.source } catch (e: Exception) { null }
                if (src != null && usable(src)) { showFor(src); handler.removeCallbacks(check); return }
            }
        }
        handler.removeCallbacks(check)
        handler.postDelayed(check, 80)
    }

    private fun isTextBox(n: AccessibilityNodeInfo): Boolean =
        n.isEditable || (n.className?.toString()?.endsWith("EditText") == true)

    private fun usable(n: AccessibilityNodeInfo): Boolean {
        if (!isTextBox(n) || n.isPassword) return false
        val pkg = n.packageName?.toString() ?: return false
        return pkg != packageName && pkg !in store.bubbleHidden
    }

    private fun showFor(node: AccessibilityNodeInfo): Boolean {
        val r = Rect()
        node.getBoundsInScreen(r)
        if (r.width() < dp(80) || r.height() <= 0) return false
        target = node
        targetPkg = node.packageName?.toString() ?: ""
        show(r)
        return true
    }

    // Finds the focused text box in the active window, or in any window on screen (some apps need this)
    private fun findFocusedInput(): AccessibilityNodeInfo? {
        try { rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { if (isTextBox(it)) return it } } catch (_: Exception) { }
        try {
            for (w in windows) {
                if (w.type != android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION) continue
                w.root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { if (isTextBox(it)) return it }
            }
        } catch (_: Exception) { }
        // Last resort for apps that don't report input focus (e.g. WeChat): look for a focused text box on screen
        try { rootInActiveWindow?.let { scanForFocusedBox(it) }?.let { return it } } catch (_: Exception) { }
        return null
    }

    /** Top edge of the on-screen keyboard, or 0 when no keyboard is showing. */
    private fun keyboardTop(): Int = try {
        val w = windows.firstOrNull { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        if (w == null) 0 else { val r = Rect(); w.getBoundsInScreen(r); if (r.height() > dp(100)) r.top else 0 }
    } catch (e: Exception) { 0 }

    /** Package of the app window the user is in. */
    private fun activeAppPackage(): String = try {
        rootInActiveWindow?.packageName?.toString()
            ?: windows.firstOrNull { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION && it.isActive }?.root?.packageName?.toString()
            ?: ""
    } catch (e: Exception) { "" }

    private fun scanForFocusedBox(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var seen = 0
        while (queue.isNotEmpty() && seen < 400) {
            val n = queue.removeFirst(); seen++
            if (n.isFocused && isTextBox(n) && n.isVisibleToUser) return n
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
        }
        return null
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        hide()
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        removeCopyBar()
        closePanel()
        hide()
        instance = null
        super.onDestroy()
    }

    // ---------- tracking the focused text box ----------
    private fun refresh() {
        if (!::store.isInitialized) return
        if (panelView != null) { hide(); return }
        if (!store.bubbleOn) { hide(); return }
        val node = findFocusedInput()
        if (node != null && usable(node) && showFor(node)) return
        // Keyboard is open but the app hides its text box (e.g. WeChat): show the bubble just above the keyboard.
        // The result is copied for the user to paste.
        if (node == null) {
            val kb = keyboardTop()
            val appPkg = activeAppPackage()
            if (kb > 0 && appPkg.isNotBlank() && appPkg != packageName && appPkg !in store.bubbleHidden) {
                target = null
                targetPkg = appPkg
                show(Rect(0, kb, resources.displayMetrics.widthPixels, kb + 1))
                return
            }
        }
        // Some apps don't report focus reliably: keep the bubble while the last text box is still focused on screen
        val last = target
        if (node == null && last != null && bubble != null) {
            val still = try { last.refresh() && last.isFocused && last.isVisibleToUser } catch (e: Exception) { false }
            if (still && showFor(last)) return
        }
        hide()
    }

    private fun makeBubble(): View {
        val size = dp(BUBBLE)
        val pad = dp(PAD)
        val box = FrameLayout(this)
        val circle = FrameLayout(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(C.G1, C.G2)).apply { shape = GradientDrawable.OVAL }
            elevation = dp(5).toFloat()
            contentDescription = "MYchat Easy"
        }
        val icon = ImageView(this)
        val id = resources.getIdentifier("ic_launcher_foreground", "mipmap", packageName)
        if (id != 0) icon.setImageResource(id)
        icon.scaleType = ImageView.ScaleType.FIT_CENTER
        circle.addView(icon, FrameLayout.LayoutParams(size, size))
        box.addView(circle, FrameLayout.LayoutParams(size, size).apply { setMargins(pad, pad, pad, pad) })
        circle.setOnClickListener { openPanel() }
        circle.setOnLongClickListener { hideForThisApp(); true }
        return box
    }

    private fun show(field: Rect) {
        val total = dp(BUBBLE + PAD * 2)
        val screenW = resources.displayMetrics.widthPixels
        val screenH = resources.displayMetrics.heightPixels
        var x = field.right - total
        var y = field.top - total + dp(PAD)
        if (y < dp(24)) y = field.bottom - dp(PAD)
        x = x.coerceIn(0, screenW - total)
        y = y.coerceIn(0, screenH - total)

        val p = params ?: WindowManager.LayoutParams(
            total, total,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        p.x = x
        p.y = y
        params = p
        try {
            val b = bubble
            if (b == null) {
                val nb = makeBubble()
                wm.addView(nb, p)
                bubble = nb
            } else {
                wm.updateViewLayout(b, p)
            }
        } catch (e: Exception) {
            bubble = null
        }
    }

    private fun hide() {
        val b = bubble ?: return
        try { wm.removeView(b) } catch (_: Exception) { }
        bubble = null
    }

    private fun hideForThisApp() {
        if (targetPkg.isBlank()) return
        store.bubbleHidden = store.bubbleHidden + targetPkg
        hide()
        val name = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(targetPkg, 0)).toString()
        } catch (e: Exception) { targetPkg }
        toast(this, "Bubble hidden in $name. Turn it back on in MYchat Easy › More.")
    }

    // ---------- toolbar after the user taps Copy in any app ----------
    private val COPY_WORDS = Regex("^(copy|copy text|কপি|কপি করুন|কপি করো|复制|複製|copiar|copier|kopieren|копировать|salin|kopyala|नकल|कॉपी करें|कॉपी|نسخ|کاپی)$", RegexOption.IGNORE_CASE)
    private var copyBarView: View? = null
    private val hideCopyBar = Runnable { removeCopyBar() }

    private fun isCopyClick(e: AccessibilityEvent): Boolean {
        val words = (e.text.map { it.toString() } + listOfNotNull(e.contentDescription?.toString())).map { it.trim() }
        return words.any { COPY_WORDS.matches(it) }
    }

    private fun showCopyBar(appPkg: String) {
        removeCopyBar()
        C.init(this)
        val ctx = android.view.ContextThemeWrapper(this, if (C.dark) android.R.style.Theme_DeviceDefault_NoActionBar else android.R.style.Theme_DeviceDefault_Light_NoActionBar)
        val bar = hbox(ctx).apply {
            background = rounded(ctx, C.panel, 99, C.line)
            elevation = ctx.dp(8).toFloat()
            setPadding(ctx.dp(6), ctx.dp(6), ctx.dp(6), ctx.dp(6))
        }
        val logo = FrameLayout(ctx).apply { background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(C.G1, C.G2)).apply { shape = GradientDrawable.OVAL } }
        val icon = ImageView(ctx)
        val id = resources.getIdentifier("ic_launcher_foreground", "mipmap", packageName)
        if (id != 0) icon.setImageResource(id)
        logo.addView(icon, FrameLayout.LayoutParams(ctx.dp(34), ctx.dp(34)))
        bar.addView(logo, android.widget.LinearLayout.LayoutParams(ctx.dp(34), ctx.dp(34)))
        fun action(label: String, what: String) = text(ctx, label, 14f, C.ink, true).apply {
            setPadding(ctx.dp(12), ctx.dp(8), ctx.dp(12), ctx.dp(8))
            background = ripple(rounded(ctx, android.graphics.Color.TRANSPARENT, 99))
            isClickable = true
            setOnClickListener { removeCopyBar(); openFromCopy(appPkg, what) }
        }
        bar.add(action("Translate", "translate"), 4, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        bar.add(action("Reply ideas", "reply"), 0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        bar.add(action("Listen", "listen"), 0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        bar.add(text(ctx, "✕", 13f, C.muted).apply {
            setPadding(ctx.dp(10), ctx.dp(8), ctx.dp(8), ctx.dp(8)); isClickable = true; setOnClickListener { removeCopyBar() }
        }, 0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            val kb = keyboardTop()
            y = if (kb > 0) resources.displayMetrics.heightPixels - kb + ctx.dp(12) else ctx.dp(110)
            windowAnimations = android.R.style.Animation_Toast
        }
        try { wm.addView(bar, lp); copyBarView = bar; handler.postDelayed(hideCopyBar, 6000) } catch (_: Exception) { }
    }

    private fun removeCopyBar() {
        handler.removeCallbacks(hideCopyBar)
        val v = copyBarView ?: return
        copyBarView = null
        try { wm.removeView(v) } catch (_: Exception) { }
    }

    /** Opens the panel with what the user just copied, and starts the chosen action. */
    private fun openFromCopy(appPkg: String, what: String) {
        // if the chat box in that app is ready, reply ideas can be inserted straight into it
        val box = findFocusedInput()?.takeIf { usable(it) && it.packageName?.toString() == appPkg }
        pending = box?.let {
            it.refresh()
            val full = if (it.isShowingHintText) "" else it.text?.toString() ?: ""
            Pending(it, full, full.length, full.length)
        }
        hide()
        showPanel("", clipAction = what, repliesOnly = true)
    }

    // ---------- open the panel over the current app, then write the result back ----------
    private var panelView: View? = null

    private fun openPanel() {
        val node = target
        if (node == null) {          // keyboard-only mode: no text box we can write to
            pending = null
            hide()
            showPanel(pasteText(this)?.takeIf { it.length < 2000 } ?: "")
            return
        }
        node.refresh()
        val full = if (node.isShowingHintText) "" else node.text?.toString() ?: ""
        val s = node.textSelectionStart
        val e = node.textSelectionEnd
        val hasSel = s >= 0 && e > s && e <= full.length
        pending = Pending(node, full, if (hasSel) s else 0, if (hasSel) e else full.length)
        hide()
        showPanel(if (hasSel) full.substring(s, e) else full)
    }

    private fun showPanel(text: String, clipAction: String? = null, repliesOnly: Boolean = false) {
        try { buildPanel(text, clipAction, repliesOnly) } catch (e: Throwable) {
            panelView = null
            toast(this, "Couldn't open the MYchat Easy panel (${e.javaClass.simpleName}). Open MYchat Easy to see details.")
            CrashReport.save(this, e)
            handler.postDelayed(check, 300)
        }
    }

    private fun buildPanel(text: String, clipAction: String? = null, repliesOnly: Boolean = false) {
        closePanel()
        removeCopyBar()
        var panelRef: ToolPanel? = null
        var clipDone = clipAction == null
        C.init(this)
        val ctx = android.view.ContextThemeWrapper(this,
            if (C.dark) android.R.style.Theme_DeviceDefault_NoActionBar else android.R.style.Theme_DeviceDefault_Light_NoActionBar)

        val root = object : FrameLayout(ctx) {
            override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
                super.onWindowFocusChanged(hasWindowFocus)
                if (!hasWindowFocus || clipDone) return
                clipDone = true
                val copied = pasteText(this@BubbleService)?.trim().orEmpty()
                val p = panelRef ?: return
                if (copied.isBlank()) { toast(this@BubbleService, "Nothing was copied. Copy a message first."); return }
                p.load(copied.take(5000))
                when (clipAction) {
                    "translate" -> p.translateToMine()
                    "reply" -> p.replyIdeas()
                    "listen" -> Speaker.speak(this@BubbleService, copied, null)
                }
            }

            override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
                if (event.keyCode == android.view.KeyEvent.KEYCODE_BACK) {
                    if (event.action == android.view.KeyEvent.ACTION_UP) closePanel()
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
        }
        root.setBackgroundColor(0x80000000.toInt())
        root.isClickable = true
        root.setOnClickListener { closePanel() }

        val sheet = MaxHeightBox(ctx, 0.88f).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            isClickable = true
            val r = ctx.dp(22).toFloat()
            background = GradientDrawable().apply { setColor(C.bg); cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f) }
        }
        val handle = View(ctx).apply { background = rounded(ctx, C.line, 99) }
        sheet.addView(handle, android.widget.LinearLayout.LayoutParams(ctx.dp(40), ctx.dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = ctx.dp(10) })
        val header = hbox(ctx).apply { setPadding(ctx.dp(18), ctx.dp(10), ctx.dp(10), ctx.dp(4)) }
        val logo = ImageView(ctx).apply { setImageResource(applicationInfo.icon) }
        header.addView(logo, android.widget.LinearLayout.LayoutParams(ctx.dp(30), ctx.dp(30)))
        header.add(text(ctx, "MYchat Easy", 16.5f, C.ink, true), 10, 0, 1f)
        header.add(link(ctx, "✕") { closePanel() }, 0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        sheet.add(header)

        val scroll = android.widget.ScrollView(ctx)
        val body = vbox(ctx).apply { setPadding(ctx.dp(16), ctx.dp(6), ctx.dp(16), ctx.dp(20)) }
        if (Config.ORDER.none { store.hasKey(it) }) {
            body.add(text(ctx, "Add a free API key first: open MYchat Easy › AI.", 13.5f, C.err), 0)
        }
        val panel = ToolPanel(ctx, store, text, { result -> closePanel(); replaceText(result) }, OverlayVoice(), repliesOnly) { v ->
            scroll.post { scroll.smoothScrollTo(0, (v.top + (v.parent as View).top - ctx.dp(12)).coerceAtLeast(0)) }
        }
        panelRef = panel
        body.add(panel.view, 4)
        scroll.addView(body, FrameLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT))
        sheet.addView(scroll, android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(sheet, FrameLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))

        // keep the sheet above the navigation bar and the keyboard
        root.setOnApplyWindowInsetsListener { v, ins ->
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                val i = ins.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.ime())
                v.setPadding(i.left, i.top, i.right, i.bottom)
            } else {
                @Suppress("DEPRECATION") v.setPadding(ins.systemWindowInsetLeft, ins.systemWindowInsetTop, ins.systemWindowInsetRight, ins.stableInsetBottom)
            }
            ins
        }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // Android 11+: we pad for the keyboard ourselves (insets); older versions resize the window instead
            softInputMode = (if (android.os.Build.VERSION.SDK_INT >= 30) WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
                else WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE) or WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN
            windowAnimations = android.R.style.Animation_InputMethod
        }
        wm.addView(root, lp)
        panelView = root
        hide()
    }

    private fun closePanel() {
        val v = panelView ?: return
        panelView = null
        try { hideKeyboard(v) } catch (_: Exception) { }
        try { wm.removeView(v) } catch (_: Exception) { }
        handler.postDelayed(check, 250)
    }

    /** Voice input inside the bubble panel, with the phone's speech recognizer (needs the microphone permission). */
    private inner class OverlayVoice : VoiceHost {
        override fun startVoice(langName: String, onText: (String) -> Unit) {
            if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                toast(this@BubbleService, "Allow the microphone first: open MYchat Easy › Home › Allow microphone.")
                return
            }
            if (!android.speech.SpeechRecognizer.isRecognitionAvailable(this@BubbleService)) {
                toast(this@BubbleService, "Voice typing needs the Google app or another speech service.")
                return
            }
            val sr = android.speech.SpeechRecognizer.createSpeechRecognizer(this@BubbleService)
            val i = Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, Config.langCode(langName) ?: "en-US")
            sr.setRecognitionListener(object : android.speech.RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { toast(this@BubbleService, "Listening… speak in $langName") }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(error: Int) {
                    sr.destroy()
                    toast(this@BubbleService, if (error == android.speech.SpeechRecognizer.ERROR_NO_MATCH || error == android.speech.SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
                        "Didn't catch that. Try again." else "Voice input stopped (error $error).")
                }
                override fun onResults(results: Bundle?) {
                    sr.destroy()
                    val said = results?.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if (!said.isNullOrBlank()) onText(said)
                }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            sr.startListening(i)
        }
    }

    /** Called by the panel when the user taps Replace or Use. */
    fun replaceText(result: String) {
        val p = pending ?: run { copyText(this, result); toast(this, "Copied. Long-press the text box and tap Paste."); return }
        handler.postDelayed({
            val node = p.node
            node.refresh()
            val cur = if (node.isShowingHintText) "" else node.text?.toString() ?: ""
            var start = p.start
            var end = p.end
            if (cur != p.full) { start = 0; end = cur.length } // the box changed meanwhile: replace all
            start = start.coerceIn(0, cur.length)
            end = end.coerceIn(start, cur.length)
            val next = cur.substring(0, start) + result + cur.substring(end)
            val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, next) }
            val ok = try { node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args) } catch (e: Exception) { false }
            if (ok) {
                val pos = start + result.length
                val sel = Bundle().apply {
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, pos)
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, pos)
                }
                try { node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel) } catch (_: Exception) { }
            } else {
                copyText(this, result)
                toast(this, "This box didn't accept the text. It's copied, so long-press the box and tap Paste.")
            }
            pending = null
        }, 150)
    }

    companion object {
        private const val CHANNEL = "bubble"
        private const val NOTIF_ID = 42
        private const val FGS_SPECIAL_USE = 0x40000000   // ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE (Android 14)
        private const val BUBBLE = 40
        private const val PAD = 6
        var instance: BubbleService? = null
            private set

        fun isEnabled(ctx: Context): Boolean {
            val list = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            val full = "${ctx.packageName}/${BubbleService::class.java.name}"
            val short = "${ctx.packageName}/.BubbleService"
            return list.split(':').any { it.equals(full, true) || it.equals(short, true) }
        }
    }
}
