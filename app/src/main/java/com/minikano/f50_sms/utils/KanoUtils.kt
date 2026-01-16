package com.minikano.f50_sms.utils

import android.app.usage.NetworkStatsManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.BatteryManager
import android.os.StatFs
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import com.minikano.f50_sms.ADBService.Companion.isExecutingDisabledFOTA
import com.minikano.f50_sms.modules.deviceInfo.MyStorageInfo
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Calendar
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import androidx.core.content.edit
import com.minikano.f50_sms.configs.AppMeta.updateIsDefaultOrWeakToken
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

class KanoUtils {
    companion object {
        fun HmacSignature(secret: String, data: String): String {
            val hmacMd5Bytes = hmac("HmacMD5", secret, data)
            val mid = hmacMd5Bytes.size / 2
            val part1 = hmacMd5Bytes.sliceArray(0 until mid)
            val part2 = hmacMd5Bytes.sliceArray(mid until hmacMd5Bytes.size)
            val sha1 = sha256(part1)
            val sha2 = sha256(part2)
            val combined = sha1 + sha2
            val finalHash = sha256(combined)
            return finalHash.joinToString("") { "%02x".format(it) }
        }

        fun hmac(algorithm: String, key: String, data: String): ByteArray {
            val mac = Mac.getInstance(algorithm)
            val secretKeySpec = SecretKeySpec(key.toByteArray(), algorithm)
            mac.init(secretKeySpec)
            return mac.doFinal(data.toByteArray())
        }

        fun sha256(data: ByteArray): ByteArray {
            val digest = MessageDigest.getInstance("SHA-256")
            return digest.digest(data)
        }

        fun sha256Hex(input: String): String {
            val bytes = input.toByteArray(Charsets.UTF_8)
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            return digest.joinToString("") { "%02x".format(it) }
        }

        //获取电池电量
        fun getBatteryPercentage(context: Context): Int {
            val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val batteryStatus = context.registerReceiver(null, filter) ?: return -1

            val level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1)

            return ((level / scale.toFloat()) * 100).toInt()
        }

        private var lastStorageInfo: MyStorageInfo? = null
        private var lastStorageUpdateTime: Long = 0

        fun getCachedRemovableStorageInfo(context: Context): MyStorageInfo? {
            val now = System.currentTimeMillis()
            if (lastStorageInfo == null || now - lastStorageUpdateTime > 10_000) {
                val dirs = context.getExternalFilesDirs(null)
                for (file in dirs) {
                    val path = file?.absolutePath ?: continue
                    if (!path.contains("/storage/emulated/0")) {
                        val statFs = StatFs(path)
                        val total = statFs.blockSizeLong * statFs.blockCountLong
                        val available = statFs.blockSizeLong * statFs.availableBlocksLong

                        lastStorageInfo = MyStorageInfo(path, total, available)
                        lastStorageUpdateTime = now
                        break
                    }
                }
            }
            return lastStorageInfo
        }

        fun getStartOfDayMillis(): Long {
            val cal = Calendar.getInstance()
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            return cal.timeInMillis
        }

        fun getTodayDataUsage(
            context: Context,
        ): Long {
            val networkStatsManager =
                context.getSystemService(Context.NETWORK_STATS_SERVICE) as NetworkStatsManager

            val startTime = getStartOfDayMillis()
            val endTime = System.currentTimeMillis()

            var totalBytes = 0L

            try {
                val summary = networkStatsManager.querySummaryForDevice(
                    ConnectivityManager.TYPE_MOBILE, null, startTime, endTime
                )
                totalBytes = summary.rxBytes + summary.txBytes
            } catch (e: Exception) {
                e.printStackTrace()
            }

            return totalBytes
        }

        // 解析 URL 编码的请求体
        fun parseUrlEncoded(data: String): Map<String, String> {
            val params = mutableMapOf<String, String>()
            val pairs = data.split("&")

            for (pair in pairs) {
                val keyValue = pair.split("=")
                if (keyValue.size == 2) {
                    val key = keyValue[0]
                    val value = keyValue[1]
                    params[key] = java.net.URLDecoder.decode(value, Charsets.UTF_8.name())  // 解码
                }
            }

            return params
        }


        //获取内存信息
        fun parseMeminfo(meminfo: String): Float {
            val memMap = mutableMapOf<String, Long>()

            meminfo.lines().forEach { line ->
                val parts = line.split(Regex("\\s+"))
                if (parts.size >= 2) {
                    val key = parts[0].removeSuffix(":")
                    val value = parts[1].toLongOrNull() ?: return@forEach
                    memMap[key] = value
                }
            }

            val total = memMap["MemTotal"] ?: return 0f
            val free = memMap["MemFree"] ?: 0
            val cached = memMap["Cached"] ?: 0
            val buffers = memMap["Buffers"] ?: 0

            val used = total - free - cached - buffers
            return used.toFloat() / total
        }

        fun parseCpuStat(raw: String): Pair<Long, Long>? {
            val line = raw.lines().firstOrNull { it.startsWith("cpu ") } ?: return null
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size < 8) return null

            val user = parts[1].toLongOrNull() ?: return null
            val nice = parts[2].toLongOrNull() ?: return null
            val system = parts[3].toLongOrNull() ?: return null
            val idle = parts[4].toLongOrNull() ?: return null
            val iowait = parts[5].toLongOrNull() ?: 0
            val irq = parts[6].toLongOrNull() ?: 0
            val softirq = parts[7].toLongOrNull() ?: 0

            val total = user + nice + system + idle + iowait + irq + softirq
            val idleAll = idle + iowait
            return Pair(total, idleAll)
        }

        fun getChunkCount(param: String?): Int {
            val default = 4
            val max = 1024

            return param?.toIntOrNull()?.let {
                when {
                    it <= 0 -> default
                    it > max -> max
                    else -> it
                }
            } ?: default
        }

        fun copyToClipboard(context: Context, label: String, text: String) {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText(label, text)
            clipboard.setPrimaryClip(clip)
        }

        fun copyFileToFilesDir(
            context: Context,
            path: String,
            skipIfExists: Boolean = true
        ): File? {
            val assetManager = context.assets
            val fileName = File(path).name
            val outFile = File(context.filesDir, fileName)

            // 如果是追加模式且目标文件已存在，则直接返回该文件，避免干扰可执行文件的运行
            if (skipIfExists && outFile.exists()) {
                KanoLog.d("UFI_TOOLS_LOG", "文件已存在，跳过复制：${outFile.absolutePath}")
                return outFile
            }

            val input = try {
                assetManager.open(path)
            } catch (e: Exception) {
                KanoLog.e("UFI_TOOLS_LOG", "assets 中不存在文件: $path")
                return null
            }

            return try {
                KanoLog.d(
                    "UFI_TOOLS_LOG",
                    "开始复制 $fileName 到 ${context.filesDir}（skipIfExists？：$skipIfExists）"
                )
                input.use { ins ->
                    FileOutputStream(outFile, skipIfExists).use { out ->
                        ins.copyTo(out)
                    }
                }
                KanoLog.d("UFI_TOOLS_LOG", "复制 $fileName 成功 -> ${outFile.absolutePath}")
                outFile
            } catch (e: Exception) {
                KanoLog.e("UFI_TOOLS_LOG", "复制 $fileName 失败: ${e.message}")
                null
            }
        }

        fun parseShellArgs(command: String): List<String> {
            val matcher = Regex("""(["'])(.*?)(?<!\\)\1|(\S+)""") // 处理单引号/双引号/无引号的参数
            return matcher.findAll(command).map {
                val quoted = it.groups[2]?.value
                val plain = it.groups[3]?.value
                when {
                    quoted != null -> quoted
                    plain != null -> plain.replace("\\", "")
                    else -> ""
                }
            }.toList()
        }

        fun isAppInstalled(context: Context, packageName: String): Boolean {
            return try {
                context.packageManager.getPackageInfo(packageName, 0)
                true
            } catch (e: PackageManager.NameNotFoundException) {
                false
            }
        }

        fun adaptIPChange(
            context: Context,
            userTouched: Boolean = false,
            onIpChanged: ((String) -> Unit)? = null
        ) {
            val prefs = context.getSharedPreferences("kano_ZTE_store", Context.MODE_PRIVATE)
            val ip_add = prefs.getString("gateway_ip", null)
            val need_auto_ip = prefs.getString("auto_ip_enabled", true.toString())
            val currentIp = IPManager.getHotspotGatewayIp("8080")

            if ((ip_add != null && need_auto_ip == "true") || userTouched) {
                KanoLog.d("UFI_TOOLS_LOG", "自动检测IP网关:$currentIp")
                if (currentIp == null) {
                    KanoLog.d("UFI_TOOLS_LOG", "自动检测IP网关失败")
                    Toast.makeText(context, "自动检测IP网关失败...", Toast.LENGTH_SHORT).show()
                    return
                }
                if ((currentIp != ip_add) || userTouched) {
                    if (userTouched) {
                        KanoLog.d("UFI_TOOLS_LOG", "用户点击，自动检测IP网关")
                        Toast.makeText(context, "自动检测IP网关~", Toast.LENGTH_SHORT).show()
                    } else {
                        KanoLog.d(
                            "UFI_TOOLS_LOG",
                            "检测到本地IP网关变动，自动修改IP网关为:$currentIp"
                        )
                        Toast.makeText(
                            context,
                            "检测到本地IP网关变动，自动修改IP网关为:$currentIp",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    prefs.edit(commit = true) { putString("gateway_ip", currentIp) }
                    if (currentIp != null) {
                        onIpChanged?.invoke(currentIp)
                    } // 通知 Compose 更新 UI
                }
            } else if (need_auto_ip == "true") {
                //说明可能是第一次启动
                prefs.edit(commit = true) { putString("gateway_ip", currentIp) }
                KanoLog.d("UFI_TOOLS_LOG", "可能是第一次启动，自动修改IP网关为:$currentIp")
            }
        }

        private fun isADBEnabled(context: Context): Boolean {
            return try {
                runBlocking {
                    val sharedPrefs =
                        context.getSharedPreferences("kano_ZTE_store", Context.MODE_PRIVATE)
                    val ADB_IP =
                        sharedPrefs.getString("gateway_ip", "")?.substringBefore(":")
                            ?: throw Exception("没有ADMIN_IP")

                    val req = KanoGoformRequest("http://$ADB_IP:8080")
                    val result = req.getData(mapOf("cmd" to "usb_port_switch"))
                    val adb_enabled = result?.getString("usb_port_switch")
                    Log.d("UFI_TOOLS_LOG", "查询ADB开启状态: $adb_enabled")
                    adb_enabled == "1"
                }
            } catch (e: Exception) {
                Log.e("UFI_TOOLS_LOG", "查询ADB开启状态执行错误: ${e.message}")
                false
            }
        }

        fun copyAssetToExternalStorage(
            context: Context,
            assetPath: String,
            skipIfExists: Boolean = false
        ): File? {
            val fileName = File(assetPath).name
            val outFile = File(context.getExternalFilesDir(null), fileName)

            // 如果是追加模式且目标文件已存在，则直接返回该文件，避免干扰可执行文件的运行
            if (skipIfExists && outFile.exists()) {
                KanoLog.d("UFI_TOOLS_LOG", "外部文件已存在，跳过复制：${outFile.absolutePath}")
                return outFile
            }

            val input = try {
                context.assets.open(assetPath)
            } catch (e: Exception) {
                KanoLog.e("UFI_TOOLS_LOG", "assets 中不存在文件: $assetPath")
                return null
            }

            return try {
                KanoLog.d(
                    "UFI_TOOLS_LOG",
                    "开始复制 $fileName 到外部存储目录（skipIfExists?：$skipIfExists）"
                )
                input.use { ins ->
                    FileOutputStream(outFile, skipIfExists).use { out ->
                        ins.copyTo(out)
                    }
                }
                KanoLog.d("UFI_TOOLS_LOG", "复制成功 -> ${outFile.absolutePath}")
                outFile
            } catch (e: Exception) {
                KanoLog.e("UFI_TOOLS_LOG", "复制失败: ${e.message}")
                null
            }
        }

        //递归复制asset中所有的目录和文件到files中
        fun copyAssetsRecursively(
            context: Context,
            assetPath: String = "",
            destDir: File = context.filesDir
        ) {
            val assetManager = context.assets
            val fileList = assetManager.list(assetPath) ?: return

            for (fileName in fileList) {
                val fullAssetPath = if (assetPath.isEmpty()) fileName else "$assetPath/$fileName"
                val outFile = File(destDir, fileName)

                if ((assetManager.list(fullAssetPath)?.isNotEmpty() == true)) {
                    // 是目录，递归复制
                    outFile.mkdirs()
                    copyAssetsRecursively(context, fullAssetPath, outFile)
                } else {
                    // 是文件，复制
                    assetManager.open(fullAssetPath).use { input ->
                        FileOutputStream(outFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    outFile.setExecutable(true)
                    outFile.setReadable(true)
                }
            }
        }

        fun getStatusCode(urlStr: String): Int {
            val url = URL(urlStr)
            val connection = url.openConnection() as HttpURLConnection
            return try {
                connection.requestMethod = "GET"
                connection.connect()
                connection.responseCode // 返回状态码
            } catch (e: Exception) {
                e.printStackTrace()
                -1 // 表示请求失败
            } finally {
                connection.disconnect()
            }
        }


        private var cachedTotal = 0L
        private var lastUpdate = 0L
        fun getCachedTodayUsage(context: Context): Long {
            val now = System.currentTimeMillis()
            if (now - lastUpdate > 10_000) { // 每 10 秒更新一次
                cachedTotal = getTodayDataUsage(context)
                lastUpdate = now
            }
            return cachedTotal
        }

        fun getSELinuxStatus(): String {
            try {
                val process = Runtime.getRuntime().exec("getenforce")
                val reader = process.inputStream.bufferedReader()
                return reader.readLine().trim()
            } catch (e: Exception) {
                e.printStackTrace()
                return "Unknown"
            }
        }

        @Serializable
        data class ShellResult(
            val done: Boolean,   // true: 正常输出; false: 报错或超时
            val content: String  // 输出内容或错误信息
        )

        fun sendShellCmd(cmd: String, timeoutSeconds: Long = 300): ShellResult {
            if (cmd.isEmpty()) return ShellResult(done = false, content = "Error: empty command")

            return try {
                val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))

                val output = StringBuilder()
                val error = StringBuilder()

                val reader = process.inputStream.bufferedReader()
                val errorReader = process.errorStream.bufferedReader()

                // 启动两个线程读取输出，避免阻塞
                val outThread = Thread {
                    reader.useLines { lines ->
                        lines.forEach { line -> output.appendLine(line) }
                    }
                }
                val errThread = Thread {
                    errorReader.useLines { lines ->
                        lines.forEach { line -> error.appendLine(line) }
                    }
                }

                outThread.start()
                errThread.start()

                // 等待执行，最多 timeoutSeconds 秒
                val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)

                if (!finished) {
                    process.destroyForcibly() // 超时杀掉进程
                    return ShellResult(done = false, content = "Error: Command timed out after $timeoutSeconds seconds")
                }

                // 确保输出线程结束
                outThread.join()
                errThread.join()

                return if (error.isNotEmpty()) {
                    ShellResult(done = false, content = error.toString().trim())
                } else {
                    ShellResult(done = true, content = output.toString().trim())
                }
            } catch (e: Exception) {
                e.printStackTrace()
                ShellResult(done = false, content = "Exception: ${e.message}")
            }
        }

        fun disableFota(context: Context):Boolean{
            if(isExecutingDisabledFOTA){
                KanoLog.w("UFI_TOOLS_LOG", "禁用FOTA操作正在执行..无需重复执行")
                return false
            }
            try {
                isExecutingDisabledFOTA = true
                // 复制依赖文件
                val outFileAdb = copyFileToFilesDir(context, "shell/adb")
                    ?: throw Exception("复制 adb 到 filesDir 失败")

                // 设置执行权限
                outFileAdb.setExecutable(true)

                val cmds = listOf(
                    "${outFileAdb.absolutePath} -s localhost shell pm disable-user --user 0 com.zte.zdm",
                    "${outFileAdb.absolutePath} -s localhost shell pm uninstall -k --user 0 com.zte.zdm",
                    "${outFileAdb.absolutePath} -s localhost shell pm uninstall -k --user 0 cn.zte.aftersale",
                    "${outFileAdb.absolutePath} -s localhost shell pm uninstall -k --user 0 com.zte.zdmdaemon",
                    "${outFileAdb.absolutePath} -s localhost shell pm uninstall -k --user 0 com.zte.zdmdaemon.install",
                    "${outFileAdb.absolutePath} -s localhost shell pm uninstall -k --user 0 com.zte.analytics",
                    "${outFileAdb.absolutePath} -s localhost shell pm uninstall -k --user 0 com.zte.neopush"
                )

                cmds.forEach{item->ShellKano.runShellCommand(item, context = context)}
                return true
            } catch (e:Exception){
                return false
            } finally {
                isExecutingDisabledFOTA = false
            }
        }

        fun isWeakToken(token: String): Boolean {
            val t = token.ifBlank { "admin" }

            val rules: List<(String) -> Boolean> = listOf(
                { it == "admin" },           // 默认弱口令
                { it.length < 8 },           // 最小长度
                { !it.any { c -> c.isDigit() } }, // 没有数字
                { !it.any { c -> c.isLetter() } } // 没有字母
            )

            return rules.any { rule -> rule(t) }
        }

        fun isUsbDebuggingEnabled(context: Context): Boolean {
            return try {
                Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1
            } catch (e: Exception) {
                try {
                    Settings.Secure.getInt(context.contentResolver, Settings.Secure.ADB_ENABLED, 0) == 1
                } catch (e: Exception) {
                    //防止权限原因读取不到，默认是Enabled
                    true
                }
            }
        }

        fun normalizePath(rawPath: String): String {
            fun decodeOnce(s: String): String {
                return try {
                    URLDecoder.decode(s, StandardCharsets.UTF_8.name())
                } catch (e: Exception) {
                    s
                }
            }

            var p = decodeOnce(rawPath)
            p = decodeOnce(p)

            p = p.replace('\\', '/')

            p = p.replace(Regex("/+"), "/")

            if (!p.startsWith("/")) p = "/$p"

            return p
        }

        fun normalizeLeadingSlashes(p: String): String {
            var s = p.replace('\\', '/')
            s = s.replace(Regex("^/+"), "/")
            if (!s.startsWith("/")) s = "/$s"
            return s
        }

        fun isSha256Hex(s: String?): Boolean {
            return !s.isNullOrBlank() && Regex("^[a-fA-F0-9]{64}$").matches(s)
        }

        fun transformLoginToken(context: Context,prefs: SharedPreferences){
            //预处理口令，如果口令存储为明文，则进行hash
            val token = prefs.getString("login_token","") ?: ""
            if(!(token.isEmpty() || token.isBlank())){
                //如果存储的口令不是hash，则进行更改
                if(!isSha256Hex(token) ){
                    val hashToken = sha256Hex(token)
                    prefs.edit(commit = true) { putString("login_token", hashToken) }
                }
            }
        }
        private val PREFS_NAME = "kano_ZTE_store"
        private val PREF_GATEWAY_IP = "gateway_ip"
        private val PREF_LOGIN_TOKEN = "login_token"
        private val PREF_TOKEN_ENABLED = "login_token_enabled"
        private val PREF_AUTO_IP_ENABLED = "auto_ip_enabled"
        private val PREF_ISDEBUG = "kano_is_debug"
        private val PREF_WAKELOCK = "wakeLock"

        fun initSharedPerfs(context: Context){
            //初始化login_token
            val spf = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            KanoUtils.transformLoginToken(context,spf)
            val existing = spf.all
            spf.edit(commit = true) {
                if (!existing.containsKey(PREF_LOGIN_TOKEN)) {
                    putString(PREF_LOGIN_TOKEN, KanoUtils.sha256Hex("admin"))
                    updateIsDefaultOrWeakToken(context,true)
                }
                if (!existing.containsKey(PREF_ISDEBUG)) {
                    putBoolean(PREF_ISDEBUG, false)
                }
                if (!existing.containsKey(PREF_GATEWAY_IP)) {
                    putString(PREF_GATEWAY_IP, "192.168.0.1:8080")
                }
                if (!existing.containsKey(PREF_TOKEN_ENABLED)) {
                    putString(PREF_TOKEN_ENABLED, true.toString())
                }
                if (!existing.containsKey(PREF_AUTO_IP_ENABLED)) {
                    putString(PREF_AUTO_IP_ENABLED, true.toString())
                }
                if (!existing.containsKey(PREF_WAKELOCK)) {
                    putString(PREF_WAKELOCK, "lock")
                }
            }
        }
    }
}