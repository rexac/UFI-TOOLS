package com.minikano.f50_sms.utils

import android.content.Context
import android.os.Build
import android.util.Log

object DeviceModelChecker {
    private var isUnSupportDevice = false
    private val devicesBlackList = listOf(
        "MU5352"
    )
    private val frimwareWhiteList = listOf(
        "MU5352_DSV1.0.0B07",
        "MU5352_DSV1.0.0B05",
        "MU5352_DSV1.0.0B03",
        "MU300",
        "F50",
        "U30Air",
    )

    fun checkBlackList(): Boolean {
        Log.d("kano_ZTE_LOG_devcheck", "正在遍历黑名单设备...")
        val model = Build.MODEL.trim()
        val firmwareVersion = Build.DISPLAY

        devicesBlackList.forEach {
            Log.d("kano_ZTE_LOG_devcheck", "$it == $model ?")
            if (it.trim().contains(model)) {
                isUnSupportDevice = true
            }
        }
        frimwareWhiteList.forEach{
            if (firmwareVersion.contains(it.trim())) {
                Log.d("kano_ZTE_LOG_devcheck", "检测到白名单固件，放行")
                isUnSupportDevice = false
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