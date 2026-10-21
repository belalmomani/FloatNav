package com.example.floatnav

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var tvStatus: TextView
    private lateinit var tvSize: TextView
    private lateinit var tvAlpha: TextView
    private lateinit var tvSmart: TextView
    private lateinit var tvUsage: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        tvStatus = findViewById(R.id.tvStatus)
        tvSize = findViewById(R.id.tvSize)
        tvAlpha = findViewById(R.id.tvAlpha)
        tvSmart = findViewById(R.id.tvSmart)
        tvUsage = findViewById(R.id.tvUsage)

        findViewById<Button>(R.id.btnAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        val sw = findViewById<Switch>(R.id.switchEnabled)
        sw.isChecked = prefs.enabled
        sw.setOnCheckedChangeListener { _, checked -> prefs.enabled = checked }

        val seekSize = findViewById<SeekBar>(R.id.seekSize)
        seekSize.max = 40                       // 32..72 dp
        seekSize.progress = prefs.sizeDp - 32
        seekSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) prefs.sizeDp = 32 + p
                updateUi()
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        val seekAlpha = findViewById<SeekBar>(R.id.seekAlpha)
        seekAlpha.max = 80                      // 20..100 %
        seekAlpha.progress = prefs.alphaPct - 20
        seekAlpha.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) prefs.alphaPct = 20 + p
                updateUi()
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        findViewById<Button>(R.id.btnPick).setOnClickListener { pickApp() }
        findViewById<Button>(R.id.btnAuto).setOnClickListener {
            prefs.pinnedPkg = ""
            updateUi()
        }
        findViewById<Button>(R.id.btnUsage).setOnClickListener {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
    }

    override fun onResume() {
        super.onResume()
        updateUi()
        // إخفاء الشريط ما دام التطبيق مفتوحًا
        NavAccessibilityService.instance?.setSuppressed(true)
    }

    override fun onPause() {
        super.onPause()
        val svc = NavAccessibilityService.instance
        svc?.setSuppressed(false)
        svc?.refresh() // يلتقط إذن الاستخدام إن مُنح للتو
    }

    private fun updateUi() {
        tvStatus.text = if (isServiceEnabled())
            "✅ خدمة الوصول مفعّلة — الشريط يعمل"
        else
            "⚠️ خدمة الوصول غير مفعّلة. فعّل «شريط التنقل العائم» من الإعدادات لكي يظهر الشريط وتعمل الأزرار."
        tvSize.text = "حجم الأزرار: ${prefs.sizeDp} dp"
        tvAlpha.text = "درجة الظهور: ${prefs.alphaPct}%"
        val pinned = prefs.pinnedPkg
        tvSmart.text = if (pinned.isEmpty()) "الزر الذكي: تلقائي (الأكثر استخدامًا)"
        else "الزر الذكي: مثبّت على ${labelOf(pinned)}"
        tvUsage.text = if (AppUsage.hasAccess(this)) "✅ إذن إحصاءات الاستخدام ممنوح"
        else "إذن إحصاءات الاستخدام غير ممنوح — الزر التلقائي لا يعمل بدونه (يمكنك تثبيت تطبيق يدويًا)."
    }

    private fun labelOf(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) { pkg }

    private fun isServiceEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val cn = ComponentName(this, NavAccessibilityService::class.java)
        return enabled.split(':').any {
            it.equals(cn.flattenToString(), true) || it.equals(cn.flattenToShortString(), true)
        }
    }

    private fun pickApp() {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = packageManager.queryIntentActivities(intent, 0)
            .map { it.activityInfo.packageName to it.loadLabel(packageManager).toString() }
            .filter { it.first != packageName }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }
        AlertDialog.Builder(this)
            .setTitle("اختر التطبيق")
            .setItems(apps.map { it.second }.toTypedArray()) { _, i ->
                prefs.pinnedPkg = apps[i].first
                updateUi()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }
}
