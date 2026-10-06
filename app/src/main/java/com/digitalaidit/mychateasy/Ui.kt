package com.digitalaidit.mychateasy

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

object C {
    var dark = false
    fun init(ctx: Context) {
        dark = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }
    val bg get() = if (dark) 0xFF0F111A.toInt() else 0xFFF3F4F9.toInt()
    val panel get() = if (dark) 0xFF171A26.toInt() else Color.WHITE
    val ink get() = if (dark) 0xFFE9EBF5.toInt() else 0xFF121527.toInt()
    val muted get() = if (dark) 0xFF99A0B8.toInt() else 0xFF6A7088.toInt()
    val line get() = if (dark) 0xFF272C3F.toInt() else 0xFFE4E6EF.toInt()
    val soft get() = if (dark) 0xFF1E2232.toInt() else 0xFFF6F7FB.toInt()
    val hover get() = if (dark) 0xFF252A40.toInt() else 0xFFEEF0FF.toInt()
    val brand get() = if (dark) 0xFF8391FF.toInt() else 0xFF3B4BFF.toInt()
    val onBrand get() = if (dark) 0xFF0E1020.toInt() else Color.WHITE
    val ok get() = if (dark) 0xFF3DD68C.toInt() else 0xFF10A35A.toInt()
    val err get() = if (dark) 0xFFFF6B7A.toInt() else 0xFFD12A3B.toInt()
    val G1 = 0xFF2E54FF.toInt()
    val G2 = 0xFF7C3AED.toInt()
}

fun Context.dp(v: Number): Int = (v.toFloat() * resources.displayMetrics.density + 0.5f).toInt()

private val mainHandler = Handler(Looper.getMainLooper())
fun ui(f: () -> Unit) { mainHandler.post(f) }
fun bg(f: () -> Unit) { Thread(f).start() }

fun rounded(ctx: Context, color: Int, radius: Number, stroke: Int? = null): GradientDrawable =
    GradientDrawable().apply {
        setColor(color)
        cornerRadius = ctx.dp(radius).toFloat()
        if (stroke != null) setStroke(ctx.dp(1), stroke)
    }

fun gradient(ctx: Context, radius: Number): GradientDrawable =
    GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(C.G1, C.G2)).apply { cornerRadius = ctx.dp(radius).toFloat() }

fun ripple(bg: Drawable, color: Int = if (C.dark) 0x33FFFFFF else 0x22000000): RippleDrawable =
    RippleDrawable(ColorStateList.valueOf(color), bg, null)

fun text(ctx: Context, s: CharSequence, size: Float = 15f, color: Int = C.ink, bold: Boolean = false): TextView =
    TextView(ctx).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setTextColor(color)
        if (bold) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        setLineSpacing(0f, 1.15f)
    }

fun vbox(ctx: Context): LinearLayout = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
fun hbox(ctx: Context): LinearLayout = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }

fun lp(w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT, weight: Float = 0f): LinearLayout.LayoutParams =
    LinearLayout.LayoutParams(w, h, weight)

fun LinearLayout.add(v: View, top: Int = 0, w: Int = ViewGroup.LayoutParams.MATCH_PARENT, weight: Float = 0f): View {
    val p = lp(w, ViewGroup.LayoutParams.WRAP_CONTENT, weight)
    if (orientation == LinearLayout.VERTICAL) p.topMargin = context.dp(top) else p.leftMargin = context.dp(top)
    addView(v, p)
    return v
}

fun spacer(ctx: Context): View = View(ctx).apply { layoutParams = lp(0, 1, 1f) }

fun card(ctx: Context): LinearLayout = vbox(ctx).apply {
    background = rounded(ctx, C.panel, 16, C.line)
    val p = ctx.dp(14)
    setPadding(p, p, p, p)
}

fun label(ctx: Context, s: String): TextView = text(ctx, s, 12.5f, C.muted, true)

fun primary(ctx: Context, s: String, onClick: () -> Unit): TextView = text(ctx, s, 15f, Color.WHITE, true).apply {
    gravity = Gravity.CENTER
    background = ripple(gradient(ctx, 12), 0x33FFFFFF)
    setPadding(ctx.dp(16), ctx.dp(11), ctx.dp(16), ctx.dp(11))
    isClickable = true
    setOnClickListener { onClick() }
}

fun ghost(ctx: Context, s: String, onClick: () -> Unit): TextView = text(ctx, s, 14f, C.ink, true).apply {
    gravity = Gravity.CENTER
    background = ripple(rounded(ctx, C.panel, 10, C.line))
    setPadding(ctx.dp(13), ctx.dp(8), ctx.dp(13), ctx.dp(8))
    isClickable = true
    setOnClickListener { onClick() }
}

fun link(ctx: Context, s: String, onClick: () -> Unit): TextView = text(ctx, s, 13.5f, C.brand, true).apply {
    setPadding(ctx.dp(6), ctx.dp(6), ctx.dp(6), ctx.dp(6))
    background = ripple(rounded(ctx, Color.TRANSPARENT, 8))
    isClickable = true
    setOnClickListener { onClick() }
}

fun chip(ctx: Context, s: String, selected: Boolean, onClick: () -> Unit): TextView =
    text(ctx, s, 14f, if (selected) C.onBrand else C.ink, selected).apply {
        background = ripple(if (selected) rounded(ctx, C.brand, 99) else rounded(ctx, C.soft, 99, C.line))
        setPadding(ctx.dp(14), ctx.dp(8), ctx.dp(14), ctx.dp(8))
        isClickable = true
        setOnClickListener { onClick() }
    }

fun input(ctx: Context, hint: String, multi: Boolean = false): EditText = EditText(ctx).apply {
    this.hint = hint
    setHintTextColor(C.muted)
    setTextColor(C.ink)
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f)
    background = rounded(ctx, C.soft, 12, C.line)
    setPadding(ctx.dp(13), ctx.dp(11), ctx.dp(13), ctx.dp(11))
    if (multi) {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        minLines = 3
        maxLines = 9
        gravity = Gravity.TOP or Gravity.START
        isVerticalScrollBarEnabled = true
    } else {
        inputType = InputType.TYPE_CLASS_TEXT
        isSingleLine = true
    }
}

fun spinner(ctx: Context, items: List<String>, selected: Int, onSelect: (Int) -> Unit): Spinner = Spinner(ctx).apply {
    val ad = ArrayAdapter(ctx, android.R.layout.simple_spinner_item, items)
    ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
    adapter = ad
    background = rounded(ctx, C.soft, 12, C.line)
    setPadding(ctx.dp(6), ctx.dp(4), ctx.dp(6), ctx.dp(4))
    if (selected in items.indices) setSelection(selected)
    var first = true
    onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
            (view as? TextView)?.setTextColor(C.ink)
            if (first) { first = false; return }
            onSelect(position)
        }
        override fun onNothingSelected(parent: AdapterView<*>?) {}
    }
}

/** Wraps children onto new rows, like chips. */
class FlowLayout(ctx: Context, private val hGap: Int, private val vGap: Int) : ViewGroup(ctx) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxW = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        var x = 0; var y = 0; var rowH = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == GONE) continue
            c.measure(MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            if (x > 0 && x + c.measuredWidth > maxW) { x = 0; y += rowH + vGap; rowH = 0 }
            x += c.measuredWidth + hGap
            rowH = maxOf(rowH, c.measuredHeight)
        }
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), y + rowH + paddingTop + paddingBottom)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val maxW = r - l - paddingLeft - paddingRight
        var x = 0; var y = 0; var rowH = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == GONE) continue
            if (x > 0 && x + c.measuredWidth > maxW) { x = 0; y += rowH + vGap; rowH = 0 }
            c.layout(paddingLeft + x, paddingTop + y, paddingLeft + x + c.measuredWidth, paddingTop + y + c.measuredHeight)
            x += c.measuredWidth + hGap
            rowH = maxOf(rowH, c.measuredHeight)
        }
    }
}

fun flow(ctx: Context): FlowLayout = FlowLayout(ctx, ctx.dp(7), ctx.dp(7))

/** A vertical box that never grows taller than a share of the screen (for the bottom sheet). */
class MaxHeightBox(ctx: Context, private val fraction: Float) : LinearLayout(ctx) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val max = (resources.displayMetrics.heightPixels * fraction).toInt()
        val available = MeasureSpec.getSize(heightMeasureSpec)
        val cap = if (available in 1 until max) available else max
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST))
    }
}

fun toast(ctx: Context, msg: String) = Toast.makeText(ctx.applicationContext, msg, Toast.LENGTH_SHORT).show()

fun copyText(ctx: Context, s: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("MYchat Easy", s))
    if (Build.VERSION.SDK_INT < 33) toast(ctx, "Copied")
}

fun pasteText(ctx: Context): String? {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = cm.primaryClip ?: return null
    if (clip.itemCount == 0) return null
    return clip.getItemAt(0).coerceToText(ctx)?.toString()
}

fun shareText(act: Activity, s: String) {
    val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, s)
    act.startActivity(Intent.createChooser(i, "Share with"))
}

fun hideKeyboard(v: View) {
    val imm = v.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    imm.hideSoftInputFromWindow(v.windowToken, 0)
}

/** Draw behind the system bars on every Android version, and pad the root by the bars and the keyboard. */
@Suppress("DEPRECATION")
fun edgeToEdge(act: Activity, root: View, transparentBars: Boolean = true) {
    val w = act.window
    if (transparentBars) {
        w.statusBarColor = Color.TRANSPARENT
        w.navigationBarColor = Color.TRANSPARENT
    }
    if (Build.VERSION.SDK_INT >= 30) {
        w.setDecorFitsSystemWindows(false)
        val light = if (C.dark) 0 else (WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS)
        w.insetsController?.setSystemBarsAppearance(light,
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS)
    } else {
        var flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        if (!C.dark) flags = flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        w.decorView.systemUiVisibility = flags
    }
    root.setOnApplyWindowInsetsListener { v, ins ->
        if (Build.VERSION.SDK_INT >= 30) {
            val i = ins.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
            v.setPadding(i.left, i.top, i.right, i.bottom)
        } else {
            v.setPadding(ins.systemWindowInsetLeft, ins.systemWindowInsetTop, ins.systemWindowInsetRight, ins.systemWindowInsetBottom)
        }
        ins
    }
}

/** Read aloud with the phone's built-in text-to-speech voices. Tap again to stop. */
object Speaker {
    private var tts: TextToSpeech? = null
    private var ready = false
    private var pending: (() -> Unit)? = null

    fun guess(text: String): String = when {
        Regex("[\\u0980-\\u09FF]").containsMatchIn(text) -> "bn-BD"
        Regex("[\\u0900-\\u097F]").containsMatchIn(text) -> "hi-IN"
        Regex("[\\u0600-\\u06FF]").containsMatchIn(text) -> "ar-SA"
        Regex("[\\u3040-\\u30FF]").containsMatchIn(text) -> "ja-JP"
        Regex("[\\uAC00-\\uD7AF]").containsMatchIn(text) -> "ko-KR"
        Regex("[\\u4E00-\\u9FFF]").containsMatchIn(text) -> "zh-CN"
        Regex("[\\u0E00-\\u0E7F]").containsMatchIn(text) -> "th-TH"
        Regex("[\\u0400-\\u04FF]").containsMatchIn(text) -> "ru-RU"
        Regex("[\\u0B80-\\u0BFF]").containsMatchIn(text) -> "ta-IN"
        else -> "en-US"
    }

    fun speak(ctx: Context, text: String, langName: String?) {
        val current = tts
        if (current != null && ready && current.isSpeaking) { current.stop(); return }
        val code = (if (langName != null && !Config.isRomanized(langName)) Config.langCode(langName) else null) ?: guess(text)
        val job = {
            val t = tts
            if (t != null) {
                val r = t.setLanguage(Locale.forLanguageTag(code))
                if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                    toast(ctx, "No voice for this language on your phone. You can add one in Settings › Text-to-speech.")
                }
                t.speak(text.take(3900), TextToSpeech.QUEUE_FLUSH, null, "mychat-easy")
            }
        }
        if (current != null && ready) { job(); return }
        pending = job
        if (current == null) {
            tts = TextToSpeech(ctx.applicationContext) { status ->
                ready = status == TextToSpeech.SUCCESS
                if (ready) pending?.invoke() else toast(ctx, "Text-to-speech isn't available on this phone.")
                pending = null
            }
        }
    }

    fun stop() { tts?.stop() }
}
