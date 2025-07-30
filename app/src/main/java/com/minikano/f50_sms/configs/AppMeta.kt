package com.minikano.f50_sms.configs

import android.content.Context
import android.os.Build
import com.minikano.f50_sms.utils.KanoLog

object AppMeta {
    var versionName: String = "unknown"
        private set
    var versionCode: Int = 0
        private set
    var model: String = Build.MODEL
        private set

    fun init(context: Context) {
        try {
            val pkgInfo = context.applicationContext.packageManager.getPackageInfo(context.packageName, 0)
            versionName = pkgInfo.versionName.toString()
            @Suppress("DEPRECATION")
            versionCode = pkgInfo.versionCode
            model = if (Build.MODEL.contains("MU5352")) "U30 Lite" else Build.MODEL
        } catch (e: Exception) {
            KanoLog.e("kano_ZTE_LOG","AppMeta init failed！！")
        }
    }
}