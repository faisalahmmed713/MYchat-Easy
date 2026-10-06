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

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        store = Store(this)
        C.init(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        handler.removeCallbacks(check)
        handler.postDelayed(check, 120)
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        hide()
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        hide()
        instance = null
        super.onDestroy()
    }

    // ---------- tracking the focused text box ----------
    private fun refresh() {
        if (!store.bubbleOn) { hide(); return }
        val root = try { rootInActiveWindow } catch (e: Exception) { null }
        val node = try { root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) } catch (e: Exception) { null }
        val pkg = node?.packageName?.toString() ?: root?.packageName?.toString() ?: ""
        if (node == null || !node.isEditable || node.isPassword || pkg == packageName || pkg in store.bubbleHidden) {
            hide(); return
        }
        val r = Rect()
        node.getBoundsInScreen(r)
        if (r.width() < dp(80) || r.height() <= 0) { hide(); return }
        target = node
        targetPkg = pkg
        show(r)
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

    // ---------- open the panel, then write the result back ----------
    private fun openPanel() {
        val node = target ?: return
        node.refresh()
        val full = if (node.isShowingHintText) "" else node.text?.toString() ?: ""
        val s = node.textSelectionStart
        val e = node.textSelectionEnd
        val hasSel = s >= 0 && e > s && e <= full.length
        pending = Pending(node, full, if (hasSel) s else 0, if (hasSel) e else full.length)
        hide()
        val i = Intent(this, ProcessTextActivity::class.java)
            .setAction(ProcessTextActivity.ACTION_BUBBLE)
            .putExtra(ProcessTextActivity.EXTRA_TEXT, if (hasSel) full.substring(s, e) else full)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        startActivity(i)
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
        }, 400)
    }

    companion object {
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
