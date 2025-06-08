package com.minikano.f50_sms.utils

import android.content.Context
import kotlinx.coroutines.*

object CpuManager {
    private var cachedCpuUsage: String? = null
    private var cachedCpuFreq: String? = null
    private val updateIntervalMs = 300L
    private val TAG = "kano_ZTE_LOG_CpuManager"
    private var scope: CoroutineScope? = null

    fun startUpdating(context: Context) {
        // 如果已经有 scope 在运行，先取消掉
        scope?.cancel()

        // 创建新的 Scope
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        scope?.launch {
            while (isActive) {
                try {
                    val usage = ShellKano.executeShellFromAssetsSubfolder(context,"shell/cpu_usage.sh","cpu_usage.sh")
                    val freq = ShellKano.executeShellFromAssetsSubfolder(context,"shell/cpu_freq.sh","cpu_freq.sh")
                    cachedCpuFreq = freq
                    cachedCpuUsage = usage
//                    KanoLog.d(TAG, "CPU 频率数据更新：$freq")
//                    KanoLog.d(TAG, "CPU 使用数据更新：$usage")
                } catch (e: Exception) {
                    KanoLog.d(TAG, "获取cpu核心频率信息出错： ${e.message}")
                }
                delay(updateIntervalMs)
            }
        }
    }

    fun stopUpdating() {
        scope?.cancel()
        scope = null
    }

    fun getCpuUsage(): String? = cachedCpuUsage
    fun getCpuFreq(): String? = cachedCpuFreq
}