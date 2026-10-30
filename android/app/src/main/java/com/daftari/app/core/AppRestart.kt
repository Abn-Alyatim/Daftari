package com.daftari.app.core

import android.app.Activity
import android.content.Intent

/** A role switch reopens every store on the other book's files: relaunch the app in a fresh process (the unlock is carried by a 30 s one-shot grant). */
object AppRestart {
    fun restart(activity: Activity) {
        val launch = activity.packageManager.getLaunchIntentForPackage(activity.packageName) ?: return
        val intent = Intent.makeRestartActivityTask(launch.component)
        activity.startActivity(intent)
        activity.finishAffinity()
        Runtime.getRuntime().exit(0)
    }
}
