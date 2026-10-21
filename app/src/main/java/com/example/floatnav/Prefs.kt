package com.example.floatnav

import android.content.Context
import android.content.SharedPreferences

class Prefs(context: Context) {
    val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("nav_prefs", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = sp.getBoolean("enabled", true)
        set(v) = sp.edit().putBoolean("enabled", v).apply()

    var sizeDp: Int
        get() = sp.getInt("size_dp", 48).coerceIn(32, 72)
        set(v) = sp.edit().putInt("size_dp", v.coerceIn(32, 72)).apply()

    var alphaPct: Int
        get() = sp.getInt("alpha_pct", 70).coerceIn(20, 100)
        set(v) = sp.edit().putInt("alpha_pct", v.coerceIn(20, 100)).apply()

    /** حزمة التطبيق المثبّت يدويًا؛ فارغ = تلقائي */
    var pinnedPkg: String
        get() = sp.getString("pinned_pkg", "") ?: ""
        set(v) = sp.edit().putString("pinned_pkg", v).apply()

    var kbX: Int
        get() = sp.getInt("kb_x", -1)
        set(v) = sp.edit().putInt("kb_x", v).apply()

    var kbY: Int
        get() = sp.getInt("kb_y", -1)
        set(v) = sp.edit().putInt("kb_y", v).apply()
}
