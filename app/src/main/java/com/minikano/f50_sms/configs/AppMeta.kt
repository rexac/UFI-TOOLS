package com.minikano.f50_sms.configs

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import com.minikano.f50_sms.modules.PREFS_NAME
import com.minikano.f50_sms.utils.DeviceModelChecker

import com.minikano.f50_sms.utils.KanoLog
import com.minikano.f50_sms.utils.getBooleanCompat
import java.io.File
import androidx.core.content.edit
import com.minikano.f50_sms.utils.KanoUtils
import com.minikano.f50_sms.utils.KanoUtils.Companion.isSha256Hex

object AppMeta {
    var versionName: String = "unknown"
        private set
    var versionCode: Int = 0
        private set
    var model: String = Build.MODEL
        private set
    var isDeviceRooted:Boolean = false
        private set
    var isReadUseTerms:Boolean = false

    var isEnableLog:Boolean = false
        private set

    var GLOBAL_SERVER_URL = "https://pan.kanokano.cn"
        private set

    var isDefaultOrWeakToken = false
        private set

    private const val PREFS_NAME = "kano_ZTE_store"
    private const val GLOBAL_SERVER_URL_KEY = "GLOBAL_SERVER_URL"
    private val PREF_ISDEBUG = "kano_is_debug"

    private val PREF_IS_WEAK_TOKEN = "is_weak_token"

    fun updateIsDefaultOrWeakToken(context: Context,value: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        //持久化
        prefs.edit(commit = true) {
            putBoolean(PREF_IS_WEAK_TOKEN,value)
        }
        isDefaultOrWeakToken = value
    }

    fun setGlobalServerUrl(context: Context,url: String) {
        if(url.isEmpty() || url.isBlank()) throw Exception("url is empty")
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit(commit = true) { putString(GLOBAL_SERVER_URL_KEY, url) }
        GLOBAL_SERVER_URL = url
    }

    fun setIsEnableLog(context: Context,flag: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit(commit = true) { putBoolean(PREF_ISDEBUG, flag)}
        isEnableLog = flag
    }

    fun setIsEnableLog(prefs: SharedPreferences, flag: Boolean) {
        prefs.edit(commit = true) { putBoolean(PREF_ISDEBUG, flag) }
        isEnableLog = flag
    }

    fun init(context: Context) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

            val isWeak = prefs.getBoolean(PREF_IS_WEAK_TOKEN, false)
            updateIsDefaultOrWeakToken(context,isWeak)

            //预处理口令
            KanoUtils.transformLoginToken(context, prefs)

            val globalServerAddress = prefs.getString(GLOBAL_SERVER_URL_KEY, null)
            if (globalServerAddress != null) {
                GLOBAL_SERVER_URL = globalServerAddress
            }

            val pkgInfo = context.applicationContext.packageManager.getPackageInfo(context.packageName, 0)
            versionName = pkgInfo.versionName.toString()

            @Suppress("DEPRECATION")
            versionCode = pkgInfo.versionCode
            model = if (Build.MODEL.contains("MU5352")) "U30 Lite" else Build.MODEL

            val socketPath = File(context.filesDir, "kano_root_shell.sock")
            isDeviceRooted = socketPath.exists()

            isReadUseTerms = prefs.getString("isReadUseTerms", "false").toBoolean()

            isEnableLog = prefs.getBooleanCompat(PREF_ISDEBUG, false)

            // //获取口令，检查是否为弱口令
            // updateIsDefaultOrWeakToken(KanoUtils.isWeakToken(token))
        } catch (e: Exception) {
            KanoLog.e("UFI_TOOLS_LOG","AppMeta init failed！！",e)
        }
    }
}