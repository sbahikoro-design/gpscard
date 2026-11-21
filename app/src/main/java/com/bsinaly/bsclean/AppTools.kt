package com.bsinaly.bsclean

import android.Manifest
import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.usage.StorageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.Process
import android.os.StatFs
import android.os.storage.StorageManager
import android.provider.Settings

object AppTools {

    data class AppCache(val pkg: String, val label: String, val cache: Long, val system: Boolean)

    // ---------- Autorisations ----------
    fun hasStorage(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else ctx.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    fun hasUsage(ctx: Context): Boolean {
        val aom = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        @Suppress("DEPRECATION")
        val mode = if (Build.VERSION.SDK_INT >= 29)
            aom.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        else aom.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun hasAccessibility(ctx: Context): Boolean {
        val s = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return s.split(':').any {
            val cn = ComponentName.unflattenFromString(it)
            cn != null && cn.packageName == ctx.packageName
        }
    }

    // ---------- Stockage ----------
    fun storage(): Pair<Long, Long> { // (total, libre)
        val st = StatFs(Environment.getDataDirectory().path)
        return st.totalBytes to st.availableBytes
    }

    // ---------- RAM ----------
    fun ram(ctx: Context): Pair<Long, Long> { // (total, disponible)
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        return mi.totalMem to mi.availMem
    }

    /** Arrête les processus d'arrière-plan des autres applis. Retourne le nombre d'applis traitées. */
    fun killBackground(ctx: Context): Int {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        var n = 0
        val pm = ctx.packageManager
        val apps = try { pm.getInstalledApplications(0) } catch (e: Exception) { emptyList<ApplicationInfo>() }
        for (a in apps) {
            if (a.packageName == ctx.packageName) continue
            if ((a.flags and ApplicationInfo.FLAG_PERSISTENT) != 0) continue
            try { am.killBackgroundProcesses(a.packageName); n++ } catch (_: Exception) {}
        }
        return n
    }

    // ---------- Cache des applis ----------
    fun loadAppCaches(ctx: Context): List<AppCache> {
        if (Build.VERSION.SDK_INT < 26) return emptyList()
        val ssm = ctx.getSystemService(StorageStatsManager::class.java) ?: return emptyList()
        val pm = ctx.packageManager
        val user = Process.myUserHandle()
        val out = mutableListOf<AppCache>()
        val apps = try { pm.getInstalledApplications(0) } catch (e: Exception) { emptyList<ApplicationInfo>() }
        for (a in apps) {
            try {
                val st = ssm.queryStatsForPackage(StorageManager.UUID_DEFAULT, a.packageName, user)
                if (st.cacheBytes > 12 * 1024) {
                    out.add(
                        AppCache(
                            a.packageName,
                            pm.getApplicationLabel(a).toString(),
                            st.cacheBytes,
                            (a.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                        )
                    )
                }
            } catch (_: Exception) {}
        }
        return out.sortedByDescending { it.cache }
    }

    /** Vide le cache de BS CLEAN lui-même. */
    fun clearOwnCache(ctx: Context): Long {
        var s = 0L
        listOfNotNull(ctx.cacheDir, ctx.externalCacheDir, ctx.codeCacheDir).forEach { d ->
            d.walkBottomUp().forEach { f -> if (f.isFile) s += f.length() }
            d.listFiles()?.forEach { it.deleteRecursively() }
        }
        return s
    }
}
