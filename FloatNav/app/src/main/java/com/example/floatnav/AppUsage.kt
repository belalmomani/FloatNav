package com.example.floatnav

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process

object AppUsage {

    @Suppress("DEPRECATION")
    fun hasAccess(ctx: Context): Boolean {
        val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        } else {
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** أكثر تطبيق قابل للتشغيل استخدامًا خلال آخر 7 أيام، أو null إن لم يتوفر الإذن/البيانات. */
    fun topApp(ctx: Context): String? {
        if (!hasAccess(ctx)) return null
        return try {
            val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()
            val stats = usm.queryAndAggregateUsageStats(now - 7L * 24 * 3600 * 1000, now)
            val pm = ctx.packageManager
            val home = pm.resolveActivity(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0
            )?.activityInfo?.packageName
            stats.values
                .filter {
                    it.totalTimeInForeground > 0 &&
                        it.packageName != ctx.packageName &&
                        it.packageName != home &&
                        it.packageName != "com.android.systemui" &&
                        pm.getLaunchIntentForPackage(it.packageName) != null
                }
                .maxByOrNull { it.totalTimeInForeground }
                ?.packageName
        } catch (e: Exception) {
            null
        }
    }
}
