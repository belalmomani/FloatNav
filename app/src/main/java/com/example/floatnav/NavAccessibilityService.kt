package com.example.floatnav

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
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
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.ImageView
import android.widget.LinearLayout
import kotlin.math.abs

class NavAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var instance: NavAccessibilityService? = null
        @Volatile var barShown: Boolean = false
        @Volatile var lastError: String? = null
    }

    private lateinit var wm: WindowManager
    private lateinit var prefs: Prefs
    private val handler = Handler(Looper.getMainLooper())

    private var bar: View? = null
    private var fab: View? = null
    private var smartPkg: String? = null
    private var lastSmartRefresh = 0L

    @Volatile private var suppressed = false
    private var kbVisible = false
    private var kbTop = 0
    private var expanded = false

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key != "kb_x" && key != "kb_y") handler.post { render(true) }
    }
    private val collapseRunnable = Runnable { expanded = false; render() }
    private val kbCheckRunnable = Runnable { checkKeyboard() }

    // ---------------- دورة الحياة ----------------

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        suppressed = false
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        prefs = Prefs(this)
        prefs.sp.registerOnSharedPreferenceChangeListener(prefListener)
        checkKeyboard()
        render(true)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!::prefs.isInitialized) return
        when (event?.eventType) {
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                checkKeyboard()
                // إعادة فحص بعد انتهاء حركة لوحة المفاتيح
                handler.removeCallbacks(kbCheckRunnable)
                handler.postDelayed(kbCheckRunnable, 300)
                if (SystemClock.elapsedRealtime() - lastSmartRefresh > 10 * 60 * 1000) render(true)
            }
        }
    }

    override fun onInterrupt() {}

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::prefs.isInitialized) handler.post { render() }
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
        handler.removeCallbacksAndMessages(null)
        removeViews()
        if (::prefs.isInitialized) prefs.sp.unregisterOnSharedPreferenceChangeListener(prefListener)
        instance = null
    }

    /** تُستدعى من MainActivity. */
    fun refresh() {
        handler.post { render(true) }
    }

    /** إخفاء الشريط أثناء ظهور شاشة إعدادات التطبيق فقط. */
    fun setSuppressed(value: Boolean) {
        handler.post {
            suppressed = value
            render()
        }
    }

    // ---------------- لوحة المفاتيح ----------------

    @Suppress("DEPRECATION")
    private fun checkKeyboard() {
        var vis = false
        var top = 0
        try {
            val ws = windows
            for (w in ws) {
                if (w.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                    val r = Rect()
                    w.getBoundsInScreen(r)
                    vis = true
                    top = r.top
                }
            }
            for (w in ws) try { w.recycle() } catch (_: Exception) {}
        } catch (_: Exception) {
        }
        val changed = vis != kbVisible || (vis && abs(top - kbTop) > dp(24))
        if (!changed) return
        kbVisible = vis
        kbTop = top
        if (!vis) {
            expanded = false
            handler.removeCallbacks(collapseRunnable)
        }
        render()
    }

    // ---------------- الرسم ----------------

    private fun render(forceSmart: Boolean = false) {
        try {
            renderInternal(forceSmart)
        } catch (e: Throwable) {
            barShown = false
            lastError = e.javaClass.simpleName + ": " + (e.message ?: "")
        }
    }

    private fun renderInternal(forceSmart: Boolean) {
        removeViews()
        if (!prefs.enabled || suppressed) return
        if (forceSmart || lastSmartRefresh == 0L ||
            SystemClock.elapsedRealtime() - lastSmartRefresh > 60_000
        ) refreshSmart()

        val btn = dp(prefs.sizeDp)
        val (sw, sh) = screenSize()
        if (kbVisible && !expanded) addFab(btn, sw, sh) else addBar(btn, sh)
    }

    private fun removeViews() {
        bar?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        fab?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        bar = null
        fab = null
        barShown = false
    }

    private fun overlayParams(w: Int, h: Int) = WindowManager.LayoutParams(
        w, h,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    )

    // ----- الشريط الكامل (ثابت أسفل الشاشة، أو فوق لوحة المفاتيح عند التوسيع) -----
    private fun addBar(btn: Int, sh: Int) {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            // ترتيب عربي: الأول (رجوع) يظهر في أقصى اليمين
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            gravity = Gravity.CENTER_VERTICAL
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
                val launch = smartPkg?.let { packageManager.getLaunchIntentForPackage(it) }
                if (launch != null) {
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try { startActivity(launch) } catch (_: Exception) { openSettings() }
                } else openSettings()
            },
            { openSettings() }, true))

        val params = overlayParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT
        )
        params.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        params.x = 0
        params.y = if (kbVisible) {
            val top = if (kbTop > 0) kbTop else (sh * 0.6f).toInt()
            (sh - top) + dp(8)           // فوق لوحة المفاتيح مباشرة
        } else dp(12)                    // ثابت أسفل الشاشة

        try {
            wm.addView(root, params)
            bar = root
            barShown = true
            lastError = null
        } catch (e: Exception) {
            lastError = "addView: " + e.javaClass.simpleName + ": " + (e.message ?: "")
        }

        if (kbVisible) {
            handler.removeCallbacks(collapseRunnable)
            handler.postDelayed(collapseRunnable, 6000)
        }
    }

    // ----- الزر العائم (يظهر فقط أثناء لوحة المفاتيح) -----
    private fun addFab(btn: Int, sw: Int, sh: Int) {
        val v = ImageView(this)
        v.setImageResource(R.drawable.ic_nav)
        v.scaleType = ImageView.ScaleType.FIT_CENTER
        v.setPadding(btn / 6, btn / 6, btn / 6, btn / 6)
        v.contentDescription = getString(R.string.recents)
        v.alpha = prefs.alphaPct / 100f

        val top = if (kbTop > 0) kbTop else (sh * 0.6f).toInt()
        val maxY = maxOf(0, top - btn - dp(4))
        var x = prefs.kbX
        var y = prefs.kbY
        if (x < 0) x = sw - btn - dp(10)
        if (y < 0) y = maxY - dp(8)
        x = x.coerceIn(0, maxOf(0, sw - btn))
        y = y.coerceIn(0, maxY)

        val params = overlayParams(btn, btn)
        params.gravity = Gravity.TOP or Gravity.START
        params.x = x
        params.y = y

        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var sx = 0; var sy = 0; var dx = 0f; var dy = 0f; var moved = false
        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    sx = params.x; sy = params.y; dx = e.rawX; dy = e.rawY; moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val mx = e.rawX - dx
                    val my = e.rawY - dy
                    if (!moved && (abs(mx) > slop || abs(my) > slop)) moved = true
                    if (moved) {
                        params.x = (sx + mx).toInt().coerceIn(0, maxOf(0, sw - btn))
                        params.y = (sy + my).toInt().coerceIn(0, maxY)
                        try { wm.updateViewLayout(v, params) } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) {
                        prefs.kbX = params.x
                        prefs.kbY = params.y
                    } else {
                        v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        expanded = true
                        render()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }

        try {
            wm.addView(v, params)
            fab = v
            barShown = true
            lastError = null
        } catch (e: Exception) {
            lastError = "addView: " + e.javaClass.simpleName + ": " + (e.message ?: "")
        }
    }

    // ---------------- أدوات ----------------

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

    private fun openSettings() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun afterAction() {
        if (kbVisible && expanded) {
            handler.removeCallbacks(collapseRunnable)
            handler.postDelayed(collapseRunnable, 250)
        }
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
            afterAction()
        }
        if (onLongClick != null) {
            view.setOnLongClickListener {
                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                onLongClick()
                afterAction()
                true
            }
        }
        return view
    }
}
