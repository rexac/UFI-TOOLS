package com.minikano.f50_sms.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/*
* 感谢群内 执念 大哥提供的思路
* */

// 数据类
data class CpuStat(val cpu: String, val total: Long, val idle: Long)
data class ThermalZone(val type: String, val temp: Int)
data class MemoryInfo(
    val total: Long,
    val available: Long,
    val used: Long,
    val usagePercent: Double,
    val swapTotal: Long,
    val swapFree: Long,
    val swapUsed: Long,
    val swapUsagePercent: Double
)

fun buildJsonObject(block: JSONObject.() -> Unit): JSONObject {
    return JSONObject().apply(block)
}

private fun buildThermalJson(zones: List<ThermalZone>): String {
    if (zones.isEmpty()) return "[]"
    val jsonParts = Array(zones.size) { i ->
        val zone = zones[i]
        """{"type":"${zone.type}","temp":${zone.temp}}"""
    }
    return "[${jsonParts.joinToString(",")}]"
}

//获取json格式的cpu频率
suspend fun getCpuFreqJson(): String = withContext(Dispatchers.IO) {
    val json = JSONObject()
    val cpuDir = File("/sys/devices/system/cpu")

    cpuDir.listFiles { _, name -> name.matches(Regex("cpu[0-9]+")) }?.forEach { coreDir ->
        val coreName = coreDir.name
        val cur = File(coreDir, "cpufreq/scaling_cur_freq").takeIf { it.exists() }
            ?.readText()?.trim()?.toIntOrNull()?.div(1000) ?: 0

        val max = File(coreDir, "cpufreq/cpuinfo_max_freq").takeIf { it.exists() }
            ?.readText()?.trim()?.toIntOrNull()?.div(1000) ?: 0

        json.put(coreName, JSONObject().apply {
            put("cur", cur)
            put("max", max)
        })
    }
    return@withContext json.toString()
}

suspend fun calculateCpuUsage(): String = withContext(Dispatchers.IO) {
    // 第一次读取
    val stats1 = readProcStat()
    delay(100) // 等待 100ms
    // 第二次读取
    val stats2 = readProcStat()

    val json = buildJsonObject {
        stats1.forEach { (cpu, stat1) ->
            val stat2 = stats2[cpu] ?: return@forEach

            val totalDiff = stat2.total - stat1.total
            val idleDiff = stat2.idle - stat1.idle

            val usage = if (totalDiff == 0L) {
                0.0
            } else {
                ((totalDiff - idleDiff) * 100.0) / totalDiff
            }

            put(cpu, "%.1f".format(usage))
        }
    }

    return@withContext json.toString()
}

private fun readProcStat(): Map<String, CpuStat> {
    val stats = mutableMapOf<String, CpuStat>()
    File("/proc/stat").bufferedReader().useLines { lines ->
        lines.filter { it.startsWith("cpu") }.forEach { line ->
            val parts = line.trim().split("\\s+".toRegex())
            if (parts.size > 4) {
                val cpuName = parts[0]
                // 计算总时间（所有字段之和）
                val total = parts.subList(1, parts.size).sumOf { it.toLongOrNull() ?: 0 }
                // 空闲时间 = idle + iowait (第4列 + 第5列)
                val idle = parts[4].toLongOrNull() ?: (0 +
                        (parts.getOrNull(5)?.toLongOrNull() ?: 0))
                stats[cpuName] = CpuStat(cpuName, total, idle)
            }
        }
    }
    return stats
}

suspend fun getMemoryUsage(): String = withContext(Dispatchers.IO) {
    val memInfo = readProcMeminfo()

    // 计算内存使用率
    val used = memInfo.total - memInfo.available
    val usagePercent = if (memInfo.total > 0) {
        used.toDouble() * 100 / memInfo.total
    } else 0.0

    // 计算交换空间使用率
    val swapUsed = memInfo.swapTotal - memInfo.swapFree
    val swapUsagePercent = if (memInfo.swapTotal > 0) {
        swapUsed.toDouble() * 100 / memInfo.swapTotal
    } else 0.0

    // 构建JSON
    return@withContext buildJsonObject {
        put("mem_total_kb", memInfo.total)
        put("mem_available_kb", memInfo.available)
        put("mem_used_kb", used)
        put("mem_usage_percent", "%.1f".format(usagePercent))
        put("swap_total_kb", memInfo.swapTotal)
        put("swap_free_kb", memInfo.swapFree)
        put("swap_used_kb", swapUsed)
        put("swap_usage_percent", "%.1f".format(swapUsagePercent))
    }.toString()
}

private fun readProcMeminfo(): MemoryInfo {
    var total = 0L
    var available = 0L
    var swapTotal = 0L
    var swapFree = 0L

    File("/proc/meminfo").bufferedReader().useLines { lines ->
        lines.forEach { line ->
            when {
                line.startsWith("MemTotal:") -> total = parseMemValue(line)
                line.startsWith("MemAvailable:") -> available = parseMemValue(line)
                line.startsWith("SwapTotal:") -> swapTotal = parseMemValue(line)
                line.startsWith("SwapFree:") -> swapFree = parseMemValue(line)
            }
        }
    }

    return MemoryInfo(total, available, 0, 0.0, swapTotal, swapFree, 0, 0.0)
}

private fun parseMemValue(line: String): Long {
    return line.split("\\s+".toRegex())
        .getOrNull(1)
        ?.toLongOrNull() ?: 0L
}

//CPU温度
suspend fun readThermalZones(): Pair<Int, String> = withContext(Dispatchers.IO) {
    val thermalDir = File("/sys/class/thermal")
    val zones = mutableListOf<ThermalZone>()

    thermalDir.listFiles()?.filter { it.name.startsWith("thermal_zone") }?.forEach { zoneDir ->
        val typeFile = File(zoneDir, "type")
        val tempFile = File(zoneDir, "temp")

        if (typeFile.exists() && tempFile.exists()) {
            try {
                val sensorType = typeFile.readText().trim()
                val tempValue = tempFile.readText().trim().toIntOrNull() ?: -1
                if (tempValue >= 0 && sensorType.isNotEmpty()) {
                    zones.add(ThermalZone(sensorType, tempValue))
                }
            } catch (_: Exception) { }
        }
    }

    val sortedZones = zones.sortedByDescending { it.temp }
    val maxTemp = sortedZones.firstOrNull()?.temp ?: -1
    val json = buildThermalJson(zones)

    return@withContext Pair(maxTemp, json)
}

//电池电压，电流
data class BatteryInfo(
    var current_uA: Int = -1,  // 单位 μA
    var voltage_uV: Int = -1  // 单位 μV
)
suspend fun readBatteryStatus(): BatteryInfo = withContext(Dispatchers.IO) {
    val baseDir = File("/sys/class/power_supply/battery")
    val info = BatteryInfo()

    val files = mapOf(
        "current_now" to ::parseMicroAmp,
        "voltage_now" to ::parseMicroVolt
    )

    val details = mutableMapOf<String, Any>()

    files.forEach { (filename, parser) ->
        val file = File(baseDir, filename)
        if (file.exists()) {
            try {
                val raw = file.readText().trim()
                val value = parser(raw)
                details[filename] = value
                when (filename) {
                    "current_now" -> info.current_uA = value
                    "voltage_now" -> info.voltage_uV = value
                }
            } catch (_: Exception) { }
        }
    }

    return@withContext info
}

private fun parseMicroAmp(text: String): Int {
    return text.toIntOrNull() ?: -1
}

private fun parseMicroVolt(text: String): Int {
    return text.toIntOrNull() ?: -1
}

