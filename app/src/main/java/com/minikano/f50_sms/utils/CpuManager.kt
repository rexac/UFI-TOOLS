package com.minikano.f50_sms.utils

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

object CpuManager {
    private var cachedCpuUsage: String? = null
    private var cachedCpuFreq: String? = null

    private const val updateIntervalMs = 500L
    private const val checkActiveIntervalMs = 10_000L
    private val TAG = "kano_ZTE_LOG_CpuManager"

    @Volatile
    private var activeFlag = false  // 全局标志：是否近期有使用需求

    private var scope: CoroutineScope? = null
    private var job: Job? = null

    /**
     * 启动 CPU 监控协程（仅限未启动时）
     */
    fun ensureRunning(context: Context) {
        activeFlag = true

        if (scope != null && job?.isActive == true) {
            return // 已经在运行，无需重复启动
        }

        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        job = scope?.launch {
            launch {
                while (isActive) {
                    try {
                        var usage = ShellKano.executeShellFromAssetsSubfolder(
                            context, "shell/cpu_usage.sh", "cpu_usage.sh"
                        )
                        if (usage.isNullOrBlank() || usage.length < 10) {
                            usage = ShellKano.executeShellFromAssetsSubfolder(
                                context, "shell/cpu_usage_fallback.sh", "cpu_usage_fallback.sh"
                            )
                        }

                        val freq = ShellKano.executeShellFromAssetsSubfolder(
                            context, "shell/cpu_freq.sh", "cpu_freq.sh"
                        )
                        cachedCpuFreq = freq
                        cachedCpuUsage = usage
                        KanoLog.d(TAG, "CPU 频率数据更新：$freq")
                        KanoLog.d(TAG, "CPU 使用数据更新：$usage")
                    } catch (e: Exception) {
                        KanoLog.d(TAG, "获取cpu核心频率信息出错： ${e.message}")
                    }

                    delay(updateIntervalMs)
                }
            }

            // 后台监控活跃标志
            launch {
                while (isActive) {
                    delay(checkActiveIntervalMs)
                    if (!activeFlag) {
                        KanoLog.d(TAG, "无活跃请求，自动停止CPU监控")
                        stopUpdating()
                        break
                    }
                    activeFlag = false // 每轮检查后清空标志
                }
            }
        }
    }

    /**
     * 手动停止更新
     */
    fun stopUpdating() {
        scope?.cancel()
        scope = null
        job = null
    }

    fun getCpuUsage(): String? = cachedCpuUsage
    fun getCpuFreq(): String? = cachedCpuFreq
}