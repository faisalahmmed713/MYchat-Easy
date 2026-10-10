package com.digitalaidit.mychateasy

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * Everything the bubble needs to keep working, with a status check where Android allows one,
 * and the exact settings screen to open on each phone brand (falling back to the app's settings).
 */
object Setup {

    class Step(
        val id: String,
        val icon: String,
        val title: String,
        val why: String,
        val required: Boolean,
        val canCheck: Boolean   // false: Android doesn't tell apps, so the user confirms with "Done"
    )

    val brand: String get() = (Build.MANUFACTURER + " " + Build.BRAND).lowercase()
    private fun isBrand(vararg names: String) = names.any { brand.contains(it) }
    val brandName: String get() = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }

    fun steps(): List<Step> {
        val list = mutableListOf(
            Step("accessibility", "🫧", "Turn on the bubble", "Lets MYchat Easy show its bubble next to text boxes in every app.", true, true)
        )
        if (Build.VERSION.SDK_INT >= 33) list.add(Step("notifications", "🔔", "Allow notifications", "Shows a quiet \"bubble is ready\" notification that keeps the bubble running.", false, true))
        list.add(Step("battery", "🔋", "No battery limits", "Stops your phone from closing the bubble to save battery.", true, true))
        if (needsAutostart()) list.add(Step("autostart", "🚀", "Allow auto-start", "Starts the bubble again after the phone restarts.", false, false))
        if (needsPowerScreen()) list.add(Step("power", "⚡", "Allow background activity", backgroundText(), false, false))
        if (Build.VERSION.SDK_INT >= 30) list.add(Step("unused", "⏸", "Keep permissions if unused", "Stops Android from removing permissions when the app isn't opened for a while.", false, true))
        list.add(Step("overlay", "🪟", "Allow floating windows", "Lets MYchat Easy show over other apps on phones that ask for it.", false, true))
        list.add(Step("mic", "🎤", "Allow the microphone", "Lets you speak instead of typing in the bubble.", false, true))
        list.add(Step("lock", "🔒", "Lock MYchat Easy in Recent apps", lockText(), false, false))
        return list
    }

    private fun needsAutostart() = isBrand("vivo", "iqoo", "xiaomi", "redmi", "poco", "oppo", "realme", "oneplus", "huawei", "honor", "asus", "letv", "meizu", "tecno", "infinix", "itel")
    private fun needsPowerScreen() = isBrand("vivo", "iqoo", "oppo", "realme", "oneplus", "huawei", "honor", "samsung", "xiaomi", "redmi", "poco")

    private fun backgroundText() = when {
        isBrand("vivo", "iqoo") -> "Battery › Background power consumption › MYchat Easy › Allow."
        isBrand("xiaomi", "redmi", "poco") -> "Battery saver › MYchat Easy › No restrictions."
        isBrand("samsung") -> "Battery › Background usage limits › add MYchat Easy to Never sleeping apps."
        isBrand("oppo", "realme", "oneplus") -> "Battery › MYchat Easy › Allow background activity."
        isBrand("huawei", "honor") -> "App launch › MYchat Easy › Manage manually, turn on all three."
        else -> "Allow MYchat Easy to run in the background."
    }

    private fun lockText() = when {
        isBrand("vivo", "iqoo") -> "Open Recent apps, pull the MYchat Easy card down (or long-press it) and tap the lock. Then \"Clear all\" won't close it."
        isBrand("xiaomi", "redmi", "poco") -> "Open Recent apps, long-press the MYchat Easy card and tap the lock."
        isBrand("oppo", "realme", "oneplus") -> "Open Recent apps, tap the ⋮ on the MYchat Easy card and choose Lock."
        isBrand("huawei", "honor") -> "Open Recent apps and pull the MYchat Easy card down to lock it."
        else -> "If your phone can lock apps in Recent apps, lock MYchat Easy so clearing apps doesn't close it."
    }

    // ---------- status ----------
    fun isDone(ctx: Context, id: String, store: Store): Boolean = when (id) {
        "accessibility" -> BubbleService.isEnabled(ctx)
        "notifications" -> Build.VERSION.SDK_INT < 33 || ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        "battery" -> try { (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(ctx.packageName) } catch (e: Exception) { true }
        "unused" -> try { Build.VERSION.SDK_INT < 30 || ctx.packageManager.isAutoRevokeWhitelisted } catch (e: Exception) { true }
        "overlay" -> Settings.canDrawOverlays(ctx)
        "mic" -> ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        else -> store.ui("setup_$id", "") == "done"   // autostart, power, lock: confirmed by the user
    }

    fun markDone(store: Store, id: String) = store.setUi("setup_$id", "done")

    // ---------- opening the right screen ----------
    const val REQ_NOTIF = 7101
    const val REQ_MIC = 7102

    fun open(act: Activity, id: String) {
        val pkg = act.packageName
        when (id) {
            "accessibility" -> start(act, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            "notifications" -> if (Build.VERSION.SDK_INT >= 33) act.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
            "mic" -> act.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            "battery" -> start(act, Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$pkg")))
            "unused" -> start(act, Intent("android.intent.action.AUTO_REVOKE_PERMISSIONS", Uri.parse("package:$pkg")))
            "overlay" -> start(act, Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$pkg")))
            "autostart" -> firstThatOpens(act, autostartIntents(pkg))
            "power" -> firstThatOpens(act, powerIntents(pkg))
            "lock" -> { }
        }
    }

    fun appInfo(act: Activity) = start(act, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${act.packageName}")))

    private fun start(act: Activity, i: Intent): Boolean = try {
        act.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
    } catch (e: Exception) { appInfo(act); false }

    private fun component(pkg: String, cls: String) = Intent().setComponent(ComponentName(pkg, cls))

    private fun firstThatOpens(act: Activity, intents: List<Intent>) {
        for (i in intents) {
            try { act.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return } catch (_: Exception) { }
        }
        appInfo(act)
    }

    private fun autostartIntents(pkg: String): List<Intent> = listOf(
        // vivo / iQOO
        component("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
        component("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.SoftPermissionDetailActivity").putExtra("packagename", pkg),
        component("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"),
        component("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
        // Xiaomi / Redmi / POCO
        component("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
        // Oppo / Realme / OnePlus
        component("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
        component("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
        component("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
        component("com.oplus.battery", "com.oplus.startupapp.view.StartupAppListActivity"),
        // Huawei / Honor
        component("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
        component("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
        component("com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
        // Asus, Letv, Meizu, Transsion
        component("com.asus.mobilemanager", "com.asus.mobilemanager.entry.FunctionActivity"),
        component("com.letv.android.letvsafe", "com.letv.android.letvsafe.AutobootManageActivity"),
        component("com.meizu.safe", "com.meizu.safe.permission.SmartBGActivity"),
        component("com.transsion.phonemaster", "com.cyin.himgr.autostart.AutoStartActivity")
    )

    private fun powerIntents(pkg: String): List<Intent> = listOf(
        // vivo background power consumption
        component("com.vivo.abe", "com.vivo.applicationbehaviorengine.ui.ExcessivePowerManagerActivity"),
        component("com.iqoo.powersaving", "com.iqoo.powersaving.PowerSavingManagerActivity"),
        // Xiaomi battery saver for this app
        component("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity").putExtra("package_name", pkg).putExtra("package_label", "MYchat Easy"),
        // Samsung battery
        component("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity"),
        component("com.samsung.android.sm", "com.samsung.android.sm.battery.ui.BatteryActivity"),
        // Oppo / Realme / OnePlus
        component("com.coloros.oppoguardelf", "com.coloros.powermanager.fuelgaue.PowerUsageModelActivity"),
        component("com.oplus.battery", "com.oplus.powermanager.fuelgaue.PowerUsageModelActivity"),
        // Huawei / Honor app launch
        component("com.huawei.systemmanager", "com.huawei.systemmanager.power.ui.HwPowerManagerActivity"),
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
    )

    /** Packages we may open, so Android 11+ lets us see them. Listed in the manifest's <queries>. */
    val OEM_PACKAGES = listOf(
        "com.vivo.permissionmanager", "com.iqoo.secure", "com.vivo.abe", "com.iqoo.powersaving",
        "com.miui.securitycenter", "com.miui.powerkeeper",
        "com.coloros.safecenter", "com.oppo.safe", "com.oplus.battery", "com.coloros.oppoguardelf",
        "com.huawei.systemmanager", "com.hihonor.systemmanager",
        "com.samsung.android.lool", "com.samsung.android.sm",
        "com.asus.mobilemanager", "com.letv.android.letvsafe", "com.meizu.safe", "com.transsion.phonemaster"
    )

    fun allRequiredDone(ctx: Context, store: Store) = steps().filter { it.required }.all { isDone(ctx, it.id, store) }
}
