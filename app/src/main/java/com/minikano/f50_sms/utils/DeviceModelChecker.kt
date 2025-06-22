package com.minikano.f50_sms.utils

import android.content.Context
import android.os.Build
import android.util.Log

object DeviceModelChecker {
    private var isUnSupportDevice = false
    private  val devicesBlackList = listOf(
        "MU5352"
    )

    fun checkBlackList(): Boolean {
        Log.d("kano_ZTE_LOG_devcheck", "正在遍历黑名单设备...")
        devicesBlackList.forEach {
            val model = Build.MODEL.trim()
            Log.d("kano_ZTE_LOG_devcheck", "$it == $model ?")
            if (it.trim().contains(model)) {
                isUnSupportDevice = true
            }
        }
        return isUnSupportDevice
    }

    fun checkIsNotUFI(context: Context):Boolean{
        val isUFI_0 = KanoUtils.isAppInstalled(context,"com.zte.web")
        val isUFI = ShellKano.runShellCommand("pm list package")
        Log.d("kano_ZTE_LOG_devcheck", "isUFI_0：${isUFI_0},has com.zte.web? :${isUFI?.contains("com.zte.web")} ")
        return !(isUFI != null && isUFI.contains("com.zte.web")) || !isUFI_0
    }
}