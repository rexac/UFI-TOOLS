package com.minikano.f50_sms.modules.deviceInfo

import android.content.Context
import android.os.Build
import android.os.StatFs
import com.minikano.f50_sms.utils.KanoLog
import com.minikano.f50_sms.utils.KanoUtils
import com.minikano.f50_sms.utils.ShellKano
import com.minikano.f50_sms.modules.BASE_TAG
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.plugins.origin
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

data class MyStorageInfo(
    val path: String, val totalBytes: Long, val availableBytes: Long
)

fun Route.baseDeviceInfoModule(context: Context) {
    val TAG = "[$BASE_TAG]_baseDeviceInfoModule"

    get("/api/baseDeviceInfo"){
        //客户端IP
        var ipRes:String? = null
        try {
            val headers = call.request.headers

            val ip = headers["http-client-ip"]
                ?: headers["x-forwarded-for"]
                ?: headers["remote-addr"]
                ?: call.request.origin.remoteAddress

            KanoLog.d(TAG, "获取客户端IP成功: $ip")
            ipRes = ip
        } catch (e: Exception) {
            KanoLog.e(TAG, "获取客户端IP出错: ${e.message}")
            ipRes = null
        }

        //cpu温度
        var cpuTempRes:String? = null
        try {
            val temp = ShellKano.executeShellFromAssetsSubfolder(context, "shell/temp.sh")
            val temp1 =
                ShellKano.runShellCommand("cat /sys/class/thermal/thermal_zone1/temp")
            KanoLog.d(TAG, "获取CPU温度成功: $temp")
            cpuTempRes = temp ?: temp1
            cpuTempRes = cpuTempRes?.replace("\n","")

        } catch (e: Exception) {
            KanoLog.d(TAG, "获取CPU温度出错： ${e.message}")
            cpuTempRes = null
        }

        //cpu使用率
        var cpuUsageRes:Float? = null
        try {
            val stat1 = ShellKano.runShellCommand("cat /proc/stat")
                ?: throw Exception("stat1没有数据")
            val (total1, idle1) = KanoUtils.parseCpuStat(stat1)
                ?: throw Exception("parseCpuStat执行失败")

            val stat2 = ShellKano.runShellCommand("cat /proc/stat")
                ?: throw Exception("stat2没有数据")
            val (total2, idle2) = KanoUtils.parseCpuStat(stat2)
                ?: throw Exception("parseCpuStat执行失败")

            val totalDiff = total2 - total1
            val idleDiff = idle2 - idle1
            val usage =
                if (totalDiff > 0) (totalDiff - idleDiff).toFloat() / totalDiff else 0f

            KanoLog.d(TAG, "CPU 使用率：%.2f%%".format(usage * 100))
            cpuUsageRes = usage * 100
        } catch (e: Exception) {
            KanoLog.d(TAG, "获取cpu使用率出错： ${e.message}")
            cpuUsageRes = null
        }

        //内存使用率
        var memUsageRes:Float? = null
        try {
            val info = ShellKano.runShellCommand("cat /proc/meminfo")
                ?: throw Exception("没有info")
            val usage = KanoUtils.parseMeminfo(info)
            KanoLog.d(TAG, "内存使用率：%.2f%%".format(usage * 100))
            memUsageRes = usage * 100
        } catch (e: Exception) {
            KanoLog.d(TAG, "获取内存信息出错： ${e.message}")
            memUsageRes = null

        }
        //存储与日流量获取
        var dailyDataRes:Long? = null
        var availableSizeRes:Long? = null
        var usedSizeRes:Long? = null
        var totalSizeRes:Long? = null
        var externalTotalRes:Long? = null
        var externalUsedRes:Long? = null
        var externalAvailableRes:Long? = null
        try {
            // 内部存储
            val internalStorage = context.filesDir
            val statFs = StatFs(internalStorage.absolutePath)
            val totalSize = statFs.blockSizeLong * statFs.blockCountLong
            val availableSize = statFs.blockSizeLong * statFs.availableBlocksLong
            val usedSize = totalSize - availableSize

            // 获取日用流量
            val dailyData = KanoUtils.getCachedTodayUsage(context)

            // 外部存储（可移动设备）
            val exStorageInfo = KanoUtils.getRemovableStorageInfo(context)
            val externalTotal = exStorageInfo?.totalBytes ?: 0
            val externalAvailable = exStorageInfo?.availableBytes ?: 0
            val externalUsed = externalTotal - externalAvailable

            KanoLog.d(TAG, "日用流量：$dailyData")
            KanoLog.d(TAG, "内部存储：$usedSize/$totalSize")
            KanoLog.d(TAG, "外部存储：$externalAvailable/$externalTotal")

            dailyDataRes = dailyData
            availableSizeRes = availableSize
            usedSizeRes = usedSize
            totalSizeRes = totalSize
            externalTotalRes = externalTotal
            externalUsedRes = externalUsed
            externalAvailableRes = externalAvailable

        } catch (e: Exception) {
            KanoLog.d(TAG, "获取型号与电量信息出错： ${e.message}")
            dailyDataRes = null
            availableSizeRes = null
            usedSizeRes = null
            totalSizeRes = null
            externalTotalRes = null
            externalUsedRes = null
            externalAvailableRes = null
        }


        //型号与电量获取
        var versionNameRes:String? = null
        var versionCodeRes:Int? = null
        var modelRes:String? = null
        var batteryLevelRes:Int? = null
        try {
            val model = Build.MODEL
            val batteryLevel: Int = KanoUtils.getBatteryPercentage(context)

            val packageManager = context.packageManager
            val packageName = context.packageName
            val packageInfo = packageManager.getPackageInfo(packageName, 0)

            val versionName = packageInfo.versionName
            val versionCode = packageInfo.versionCode

            KanoLog.d(TAG, "型号与电量：$model $batteryLevel")

            versionNameRes = versionName
            versionCodeRes = versionCode
            modelRes = model
            batteryLevelRes = batteryLevel

        } catch (e: Exception) {
            KanoLog.d(TAG, "获取型号与电量信息出错：${e.message}")
            versionNameRes = null
            versionCodeRes = null
            modelRes = null
            batteryLevelRes = null
        }

        val jsonResult = """
            {
                "app_ver": "$versionNameRes",
                "app_ver_code": "$versionCodeRes",
                "model": "$modelRes",
                "battery": "$batteryLevelRes",
                "daily_data": $dailyDataRes,
                "internal_available_storage": $availableSizeRes,
                "internal_used_storage": $usedSizeRes,
                "internal_total_storage": $totalSizeRes,
                "external_total_storage": $externalTotalRes,
                "external_used_storage": $externalUsedRes,
                "external_available_storage": $externalAvailableRes,
                "mem_usage":$memUsageRes,
                "cpu_usage":$cpuUsageRes,
                "cpu_temp":$cpuTempRes,
                "client_ip":"$ipRes"
            }
        """.trimIndent()
        call.response.headers.append("Access-Control-Allow-Origin", "*")
        call.respondText(jsonResult, ContentType.Application.Json)
    }

    //版本信息获取
    get("/api/version_info") {
        try {
            val model = Build.MODEL
            val packageManager = context.packageManager
            val packageName = context.packageName
            val packageInfo = packageManager.getPackageInfo(packageName, 0)

            val versionName = packageInfo.versionName
            val versionCode = packageInfo.versionCode

            val jsonResult = """
            {
                "app_ver": "$versionName",
                "app_ver_code": "$versionCode",
                "model":"$model"
            }
        """.trimIndent()

            call.response.headers.append("Access-Control-Allow-Origin", "*")
            call.respondText(jsonResult, ContentType.Application.Json)
        } catch (e: Exception) {
            KanoLog.d("kano_ZTE_LOG", "获取版本信息出错：${e.message}")
            call.response.headers.append("Access-Control-Allow-Origin", "*")
            call.respondText(
                """{"error":"获取版本信息出错"}""",
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError
            )
        }
    }
}