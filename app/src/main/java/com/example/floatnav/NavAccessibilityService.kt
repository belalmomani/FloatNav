package com.example.floatnav

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.ImageView
import android.widget.LinearLayout

class NavAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: NavAccessibilityService? = null
        @Volatile
        var barShown: Boolean = false
        @Volatile
        var lastError: String? = null
    }

    private lateinit var wm: WindowManager
    private lateinit var prefs: Prefs
    private val handler = Handler(Looper.getMainLooper())
    private var bar: View? = null
    private var smartPkg: String? = null
    private var lastSmartRefresh = 0L
    @Volatile private var suppressed = false

    // يجب الاحتفاظ بمرجع قوي للمستمع
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key != "pos_x" && key != "pos_y") handler.post { rebuild() }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        suppressed = false
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        prefs = Prefs(this)
        prefs.sp.registerOnSharedPreferenceChangeListener(prefListener)
        rebuild()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // تحديث أيقونة الاختصار الذكي كل 10 دقائق كحد أقصى
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            SystemClock.elapsedRealtime() - lastSmartRefresh > 10 * 60 * 1000
        ) {
            handler.post { rebuild() }
        }
    }

    override fun onInterrupt() {}

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::prefs.isInitialized) handler.post { rebuild() }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        cleanup()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    private fun cleanup() {
        removeBar()
        if (::prefs.isInitialized) prefs.sp.unregisterOnSharedPreferenceChangeListener(prefListener)
        instance = null
    }

    /** تُستدعى من MainActivity بعد إعادة ضبط الموضع. */
    fun refresh() {
        handler.post { rebuild() }
    }

    private fun removeBar() {
        bar?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        bar = null
        barShown = false
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun screenSize(): Pair<Int, Int> =
        if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.maximumWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val m = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(m)
            m.widthPixels to m.heightPixels
        }

    private fun refreshSmart() {
        lastSmartRefresh = SystemClock.elapsedRealtime()
        val pinned = prefs.pinnedPkg
        smartPkg = if (pinned.isNotEmpty() && packageManager.getLaunchIntentForPackage(pinned) != null) {
            pinned
        } else {
            AppUsage.topApp(this)
        }
    }

    private fun rebuild() {
        try {
            rebuildInternal()
        } catch (e: Throwable) {
            barShown = false
            lastError = e.javaClass.simpleName + ": " + (e.message ?: "")
        }
    }

    private fun rebuildInternal() {
        removeBar()
        if (!prefs.enabled) return
        refreshSmart()

        val btn = dp(prefs.sizeDp)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
            background = GradientDrawable().apply {
                setColor(0xE61C1C1E.toInt())
                cornerRadius = dp(40).toFloat()
                setStroke(dp(1), 0x40FFFFFF)
            }
            alpha = prefs.alphaPct / 100f
        }

        root.addView(makeButton(btn, getString(R.string.back), getDrawable(R.drawable.ic_back),
            { performGlobalAction(GLOBAL_ACTION_BACK) }, null))
        root.addView(makeButton(btn, getString(R.string.home), getDrawable(R.drawable.ic_home),
            { performGlobalAction(GLOBAL_ACTION_HOME) }, null))
        root.addView(makeButton(btn, getString(R.string.recents), getDrawable(R.drawable.ic_recents),
            { performGlobalAction(GLOBAL_ACTION_RECENTS) },
            {
                // ضغطة مطوّلة: التبديل لآخر تطبيق (ضغطتان سريعتان على "الحديثة")
                performGlobalAction(GLOBAL_ACTION_RECENTS)
                handler.postDelayed({ performGlobalAction(GLOBAL_ACTION_RECENTS) }, 400)
            }))

        val smartIcon: Drawable? = smartPkg?.let {
            try { packageManager.getApplicationIcon(it) } catch (_: Exception) { null }
        } ?: getDrawable(R.drawable.ic_star)
        root.addView(makeButton(btn, getString(R.string.smart), smartIcon,
            {
                val pkg = smartPkg
                val launch = pkg?.let { packageManager.getLaunchIntentForPackage(it) }
                if (launch != null) {
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try { startActivity(launch) } catch (_: Exception) { openSettings() }
                } else openSettings()
            },
            { openSettings() }, true))

        // مثبّت أسفل الشاشة في المنتصف، بلا سحب
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        params.x = 0
        params.y = dp(12)

        root.visibility = if (suppressed) View.GONE else View.VISIBLE
        try {
            wm.addView(root, params)
            bar = root
            barShown = true
            lastError = null
        } catch (e: Exception) {
            barShown = false
            lastError = "addView: " + e.javaClass.simpleName + ": " + (e.message ?: "")
        }
    }

    /** إخفاء الشريط أثناء ظهور شاشة إعدادات التطبيق فقط. */
    fun setSuppressed(value: Boolean) {
        handler.post {
            suppressed = value
            bar?.visibility = if (value) View.GONE else View.VISIBLE
        }
    }

    private fun openSettings() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun makeButton(
        size: Int,
        desc: String,
        icon: Drawable?,
        onClick: () -> Unit,
        onLongClick: (() -> Unit)?,
        smallPadding: Boolean = false
    ): ImageView {
        val view = ImageView(this)
        view.layoutParams = LinearLayout.LayoutParams(size, size)
        val pad = if (smallPadding) size / 6 else size / 5
        view.setPadding(pad, pad, pad, pad)
        view.setImageDrawable(icon)
        view.scaleType = ImageView.ScaleType.FIT_CENTER
        view.contentDescription = desc
        view.isClickable = true
        val mask = GradientDrawable()
        mask.shape = GradientDrawable.OVAL
        mask.setColor(Color.WHITE)
        view.background = RippleDrawable(ColorStateList.valueOf(0x44FFFFFF), null, mask)
        view.setOnClickListener {
            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            onClick()
        }
        if (onLongClick != null) {
            view.setOnLongClickListener {
                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                onLongClick()
                true
            }
        }
        return view
    }
}
