package com.minikano.f50_sms.modules

import android.content.Context
import kotlin.random.Random.Default
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.defaultForFilePath
import io.ktor.server.application.Application
import kotlinx.coroutines.Dispatchers
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receiveText
import io.ktor.server.request.uri
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import java.net.HttpURLConnection
import java.net.URL
import android.os.Build
import android.os.StatFs
import com.minikano.f50_sms.ADBService
import com.minikano.f50_sms.KanoLog
import com.minikano.f50_sms.KanoRequest
import com.minikano.f50_sms.KanoUtils
import com.minikano.f50_sms.RootShell
import com.minikano.f50_sms.SMBConfig
import com.minikano.f50_sms.ShellKano
import com.minikano.f50_sms.SmbThrottledRunner
import com.minikano.f50_sms.SmsInfo
import com.minikano.f50_sms.SmsPoll
import com.minikano.f50_sms.modules.auth.authenticatedRoute
import io.ktor.http.HttpHeaders
import io.ktor.server.plugins.origin
import io.ktor.server.response.header
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondOutputStream
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.close
import io.ktor.utils.io.jvm.javaio.copyTo
import io.ktor.utils.io.jvm.javaio.toOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.io.OutputStreamWriter
import java.io.PipedInputStream
import java.io.PipedOutputStream
import kotlin.concurrent.thread

object ApkState {
    var downloadResultPath: String? = null
    var downloadInProgress = false
    var download_percent = 0
    var downloadError: String? = null
    var currentDownloadingUrl: String = ""
}

data class MyStorageInfo(
    val path: String, val totalBytes: Long, val availableBytes: Long
)

fun Application.mainModule(context: Context,proxyServerIp:String) {
    install(DefaultHeaders)

    val TAG = "kano_ZTE_LOG"
    val targetServerIP = proxyServerIp  // 目标服务器地址
    val PREFS_NAME = "kano_ZTE_store"

    routing {
        // 静态资源
        get("{...}") {
                val rawPath = call.request.uri.removePrefix("/")
                val path = if (rawPath.isBlank()) "index.html" else rawPath

                try {
                    val inputStream = context.assets.open(path)
                    val bytes = inputStream.readBytes()
                    val contentType = ContentType.defaultForFilePath(path)
                    call.respondBytes(bytes, contentType)
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.NotFound, "404 Not Found")
                }
        }

        authenticatedRoute(context){
            //转发到原厂web后端
            route("/api/goform/{...}") {
                handle {
                    val targetServer = "http://${targetServerIP}" // 替换成你的目标服务器

                    val originalPath = call.request.uri.removePrefix("/api")
                    val queryString = call.request.queryParameters.entries()
                        .joinToString("&") { (k, v) -> v.joinToString("&") { "$k=$it" } }

                    val fullUrl = if (queryString.isBlank()) {
                        "$targetServer$originalPath"
                    } else {
                        "$targetServer$originalPath?$queryString"
                    }

                    val method = call.request.httpMethod.value

                    // 处理 OPTIONS 请求
                    if (method == "OPTIONS") {
                        call.response.headers.append("Access-Control-Allow-Origin", "*")
                        call.response.headers.append("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
                        call.response.headers.append("Access-Control-Allow-Headers", "Content-Type, X-Requested-With")
                        call.respond(HttpStatusCode.OK)
                        return@handle
                    }

                    try {
                        val url = URL(fullUrl)
                        val conn = (url.openConnection() as HttpURLConnection).apply {
                            requestMethod = method
                            setRequestProperty("Referer", targetServer)
                            doInput = true

                            call.request.headers.forEach { key, values ->
                                if (!key.equals("host", ignoreCase = true)) {
                                    setRequestProperty(key, values.joinToString(","))
                                }
                            }

                            if (method == "POST" || method == "PUT") {
                                val body = call.receiveText()
                                doOutput = true
                                setRequestProperty("Content-Length", body.toByteArray().size.toString())
                                outputStream.use { it.write(body.toByteArray()) }
                            }
                        }

                        val responseCode = conn.responseCode
                        val responseStream = if (responseCode in 200..299) conn.inputStream else conn.errorStream
                        val responseBytes = responseStream.readBytes()
                        val responseContentType = conn.contentType ?: "text/plain"

                        conn.headerFields.forEach { (key, values) ->
                            if (key != null && key.equals("Set-Cookie", ignoreCase = true)) {
                                values?.forEach { cookie ->
                                    call.response.headers.append("kano-cookie", cookie)
                                }
                            }
                        }

                        call.response.headers.append("Access-Control-Allow-Origin", "*")
                        call.response.headers.append("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
                        call.response.headers.append("Access-Control-Allow-Headers", "Content-Type, X-Requested-With")

                        call.respondBytes(responseBytes, ContentType.parse(responseContentType), HttpStatusCode.fromValue(responseCode))
                    } catch (e: Exception) {
                        call.respond(HttpStatusCode.InternalServerError, "Proxy error: ${e.message}")
                    }
                }
            }

            //客户端IP
            get("/api/ip") {
                try {
                    val headers = call.request.headers

                    val ip = headers["http-client-ip"]
                        ?: headers["x-forwarded-for"]
                        ?: headers["remote-addr"]
                        ?: call.request.origin.remoteAddress

                    KanoLog.d(TAG, "获取客户端IP成功: $ip")

                    call.response.header("Access-Control-Allow-Origin", "*")
                    call.respondText(
                        """{"ip":"$ip"}""",
                        ContentType.Application.Json
                    )
                } catch (e: Exception) {
                    KanoLog.e(TAG, "获取客户端IP出错: ${e.message}")
                    call.response.header("Access-Control-Allow-Origin", "*")
                    call.respondText(
                        """{"error":"获取客户端IP失败"}""",
                        ContentType.Application.Json
                    )
                }
            }

            //cpu温度
            get("/api/temp") {
                try {
                    val temp = ShellKano.executeShellFromAssetsSubfolder(context, "shell/temp.sh")
                    val temp1 =
                        ShellKano.runShellCommand("cat /sys/class/thermal/thermal_zone1/temp")
                    KanoLog.d("kano_ZTE_LOG", "获取CPU温度成功: $temp")

                    call.respondText(
                        """{"temp":${temp ?: temp1}}""",
                        ContentType.Application.Json
                    )
                } catch (e: Exception) {
                    KanoLog.d("kano_ZTE_LOG", "获取CPU温度出错： ${e.message}")

                    call.respondText(
                        """{"error":"获取CPU温度失败"}""",
                        ContentType.Application.Json,
                        HttpStatusCode.InternalServerError
                    )
                }
            }

            //cpu使用率
            get("/api/cpu") {
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

                    KanoLog.d("kano_ZTE_LOG", "CPU 使用率：%.2f%%".format(usage * 100))

                    call.respondText(
                        """{"cpu":${usage * 100}}""",
                        ContentType.Application.Json
                    )
                } catch (e: Exception) {
                    KanoLog.d("kano_ZTE_LOG", "获取cpu使用率出错： ${e.message}")

                    call.respondText(
                        """{"error":"获取cpu使用率出错"}""",
                        ContentType.Application.Json,
                        HttpStatusCode.InternalServerError
                    )
                }
            }

            //内存使用率
            get("/api/mem") {
                try {
                    val info = ShellKano.runShellCommand("cat /proc/meminfo")
                        ?: throw Exception("没有info")
                    val usage = KanoUtils.parseMeminfo(info)

                    KanoLog.d("kano_ZTE_LOG", "内存使用率：%.2f%%".format(usage * 100))

                    call.respondText(
                        """{"mem":${usage * 100}}""",
                        ContentType.Application.Json
                    )
                } catch (e: Exception) {
                    KanoLog.d("kano_ZTE_LOG", "获取内存信息出错： ${e.message}")

                    call.respondText(
                        """{"error":"获取内存信息失败"}""",
                        ContentType.Application.Json,
                        HttpStatusCode.InternalServerError
                    )
                }
            }

            //获取网络ADB自启状态
            get("/api/adb_wifi_setting") {
                try {
                    val sharedPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    val adbIpEnabled = sharedPrefs.getString("ADB_IP_ENABLED", "false")

                    call.respondText(
                        """{"enabled":$adbIpEnabled}""",
                        ContentType.Application.Json
                    )
                } catch (e: Exception) {
                    KanoLog.d("kano_ZTE_LOG", "获取网络adb信息出错： ${e.message}")

                    call.respondText(
                        """{"error":"获取网络adb信息失败"}""",
                        ContentType.Application.Json,
                        HttpStatusCode.InternalServerError
                    )
                }
            }

            //修改网络ADB自启状态
            post("/api/adb_wifi_setting") {
                try {
                    // 获取 JSON Body
                    val body = call.receiveText()
                    val json = JSONObject(body)

                    val enabled = json.optBoolean("enabled", false)
                    val password = json.optString("password", "")

                    KanoLog.d(
                        "kano_ZTE_LOG",
                        "接收到ADB_WIFI配置：enabled=$enabled, password=$password"
                    )

                    val sharedPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

                    // 保存配置
                    if (enabled) {
                        sharedPrefs.edit()
                            .putString("ADMIN_PWD", password)
                            .putString("ADB_IP_ENABLED", "true")
                            .apply()
                    } else {
                        sharedPrefs.edit()
                            .remove("ADMIN_PWD")
                            .putString("ADB_IP_ENABLED", "false")
                            .apply()
                    }

                    KanoLog.d("kano_ZTE_LOG", "ADMIN_PWD:${sharedPrefs.getString("ADMIN_PWD", "")}")

                    // 响应
                    call.response.headers.append("Access-Control-Allow-Origin", "*")
                    call.respondText(
                        """{"result":"success","enabled":"$enabled"}""",
                        ContentType.Application.Json
                    )
                } catch (e: Exception) {
                    KanoLog.d("kano_ZTE_LOG", "解析ADB_WIFI POST 请求出错：${e.message}")
                    call.response.headers.append("Access-Control-Allow-Origin", "*")
                    call.respondText(
                        """{"error":"参数解析失败"}""",
                        ContentType.Application.Json,
                        HttpStatusCode.InternalServerError
                    )
                }
            }

            //AT指令
            get("/api/AT") {
                try {
                    val command = call.request.queryParameters["command"]
                        ?: throw Exception("缺少 query 参数 command")
                    val slot = call.request.queryParameters["slot"]?.toIntOrNull() ?: 0

                    KanoLog.d("kano_ZTE_LOG", "AT_command 传入参数：$command")

                    if (!command.trim().startsWith("AT", ignoreCase = true)) {
                        throw Exception("解析失败，AT指令需要以 “AT” 开头")
                    }

                    val outFileAt = KanoUtils.copyFileToFilesDir(context, "shell/sendat")
                        ?: throw Exception("复制 sendat 到 filesDir 失败")
                    outFileAt.setExecutable(true)

                    val atCommand = "${outFileAt.absolutePath} -n $slot -c '${command.trim()}'"
                    val result = ShellKano.runShellCommand(atCommand, true)
                        ?: throw Exception("AT 指令没有输出")

                    var res = result
                        .replace("\"", "\\\"") // 转义引号
                        .replace("\n", "")
                        .replace("\r", "")
                        .trimStart()

                    if (res.lowercase().endsWith("ok")) {
                        res = res.dropLast(2).trimEnd() + " OK"
                    }
                    if (res.startsWith(",")) {
                        res = res.removePrefix(",").trimStart()
                    }

                    KanoLog.d("kano_ZTE_LOG", "AT_cmd：$atCommand")
                    KanoLog.d("kano_ZTE_LOG", "AT_result：$res")

                    call.respondText(
                        """{"result":"$res"}""",
                        ContentType.Application.Json
                    )

                } catch (e: Exception) {
                    KanoLog.d("kano_ZTE_LOG", "AT指令执行错误：${e.message}")

                    call.respondText(
                        """{"error":"AT指令执行错误：${e.message}"}""",
                        ContentType.Application.Json,
                        HttpStatusCode.InternalServerError
                    )
                }
            }

            //更改samba分享地址为根目录
            get("/api/smbPath") {
                try {
                    val enabled = call.request.queryParameters["enable"]
                        ?: throw Exception("缺少 query 参数 enable")

                    KanoLog.d("kano_ZTE_LOG", "enable 传入参数：$enabled")

                    // 复制依赖文件
                    val outFileAdb = KanoUtils.copyFileToFilesDir(context, "shell/adb")
                        ?: throw Exception("复制 adb 到 filesDir 失败")
                    val smbPath = SMBConfig.writeConfig(context)
                        ?: throw Exception("复制 smb.conf 到 filesDir 失败")
                    val outFileTtyd = KanoUtils.copyFileToFilesDir(context, "shell/ttyd")
                        ?: throw Exception("复制 ttyd 到 filesDir 失败")
                    val outFileSocat = KanoUtils.copyFileToFilesDir(context, "shell/socat")
                        ?: throw Exception("复制 socat 到 filesDir 失败")
                    val outFileSmbSh =
                        KanoUtils.copyFileToFilesDir(context, "shell/samba_exec.sh", false)
                            ?: throw Exception("复制 samba_exec.sh 到 filesDir 失败")

                    // 设置执行权限
                    outFileAdb.setExecutable(true)
                    outFileTtyd.setExecutable(true)
                    outFileSocat.setExecutable(true)
                    outFileSmbSh.setExecutable(true)

                    var jsonResult = """{"result":"执行成功，重启生效！"}"""

                    if (enabled == "1") {
                        val cmd =
                            "${outFileAdb.absolutePath} -s localhost shell cat $smbPath > /data/samba/etc/smb.conf"
                        val result = ShellKano.runShellCommand(cmd, context = context)
                            ?: throw Exception("修改 smb.conf 失败")
                        jsonResult = """{"result":"执行成功，等待一会儿即可生效！"}"""
                    } else {
                        val script = """
                sh /sdcard/ufi_tools_boot.sh
                chattr -i /data/samba/etc/smb.conf
                chmod 644 /data/samba/etc/smb.conf
                rm -f /data/samba/etc/smb.conf
                sync
            """.trimIndent()

                        val socketPath = File(context.filesDir, "kano_root_shell.sock")
                        if (!socketPath.exists()) {
                            throw Exception("执行失败，没有找到 socat 创建的 sock")
                        }

                        val result = RootShell.sendCommandToSocket(script, socketPath.absolutePath)
                            ?: throw Exception("删除 smb.conf 失败")
                        KanoLog.d("kano_ZTE_LOG", "sendCommandToSocket Output:\n$result")
                    }

                    KanoLog.d("kano_ZTE_LOG", "刷新 SMB 中...")
                    SmbThrottledRunner.runOnceInThread(context)

                    call.respondText(jsonResult, ContentType.Application.Json)

                } catch (e: Exception) {
                    KanoLog.d("kano_ZTE_LOG", "smbPath 执行出错：${e.message}")
                    call.respondText(
                        """{"error":"执行错误：${e.message}"}""",
                        ContentType.Application.Json,
                        HttpStatusCode.InternalServerError
                    )
                }
            }

            //型号与电量获取
            get("/api/battery_and_model") {
                try {
                    val model = Build.MODEL
                    val batteryLevel: Int = KanoUtils.getBatteryPercentage(context)

                    val packageManager = context.packageManager
                    val packageName = context.packageName
                    val packageInfo = packageManager.getPackageInfo(packageName, 0)

                    val versionName = packageInfo.versionName
                    val versionCode = packageInfo.versionCode

                    KanoLog.d("kano_ZTE_LOG", "型号与电量：$model $batteryLevel")

                    val jsonResult = """
            {
                "app_ver": "$versionName",
                "app_ver_code": "$versionCode",
                "model": "$model",
                "battery": "$batteryLevel"
            }
        """.trimIndent()

                    call.response.headers.append("Access-Control-Allow-Origin", "*")
                    call.respondText(jsonResult, ContentType.Application.Json)
                } catch (e: Exception) {
                    KanoLog.d("kano_ZTE_LOG", "获取型号与电量信息出错：${e.message}")
                    call.response.headers.append("Access-Control-Allow-Origin", "*")
                    call.respondText(
                        """{"error":"获取型号与电量信息出错"}""",
                        ContentType.Application.Json,
                        HttpStatusCode.InternalServerError
                    )
                }
            }

            //测速
            get("/api/speedtest") {
                val parms = call.request.queryParameters
                val totalChunks = KanoUtils.getChunkCount(parms["ckSize"])
                val enableCors = parms.contains("cors")

                val bufferSize = 1024 * 1024 // 1MB
                val buffer = ByteArray(bufferSize).apply {
                    Default.nextBytes(this) // 或替换为静态缓存以减少 GC
                }

                // 响应头（最少必要）
                if (enableCors) {
                    call.response.headers.append("Access-Control-Allow-Origin", "*")
                    call.response.headers.append("Access-Control-Allow-Methods", "GET, POST")
                }

                call.response.headers.append(HttpHeaders.ContentType, ContentType.Application.OctetStream.toString())
                call.response.headers.append(HttpHeaders.ContentDisposition, "attachment; filename=random.dat")
                call.response.headers.append(HttpHeaders.CacheControl, "no-store, no-cache, must-revalidate")
                call.response.headers.append(HttpHeaders.Pragma, "no-cache")
                call.response.headers.append("Content-Transfer-Encoding", "binary")

                call.respondBytesWriter(contentType = ContentType.Application.OctetStream) {
                    repeat(totalChunks) {
                        writeFully(buffer, 0, bufferSize)
                    }
                }
            }

            //判断是否有ttyd
            get("/api/hasTTYD") {
                try {
                    val params = call.request.queryParameters
                    val port =
                        params["port"] ?: throw IllegalArgumentException("query 缺少 port 参数")

                    val host = targetServerIP.substringBefore(":")
                    val fullUrl = "http://$host:$port"
                    val code = KanoUtils.getStatusCode(fullUrl)

                    KanoLog.d("kano_ZTE_LOG", "TTYD获取ip+port信息： $host:$port 返回code:$code")

                    call.response.headers.append("Access-Control-Allow-Origin", "*")
                    call.respondText(
                        """{"code":"$code","ip":"$host:$port"}""",
                        ContentType.Application.Json,
                        HttpStatusCode.OK
                    )
                } catch (e: Exception) {
                    KanoLog.d("kano_ZTE_LOG", "获取TTYD信息出错： ${e.message}")
                    call.response.headers.append("Access-Control-Allow-Origin", "*")
                    call.respondText(
                        """{"error":"获取TTYD信息出错:${e.message}"}""",
                        ContentType.Application.Json,
                        HttpStatusCode.InternalServerError
                    )
                }
            }

            //存储与日流量获取
            get("/api/storage_and_dailyData") {
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

                    KanoLog.d("kano_ZTE_LOG", "日用流量：$dailyData")
                    KanoLog.d("kano_ZTE_LOG", "内部存储：$usedSize/$totalSize")
                    KanoLog.d("kano_ZTE_LOG", "外部存储：$externalAvailable/$externalTotal")

                    call.response.headers.append("Access-Control-Allow-Origin", "*")
                    call.respondText(
                        """
            {
                "daily_data": $dailyData,
                "internal_available_storage": $availableSize,
                "internal_used_storage": $usedSize,
                "internal_total_storage": $totalSize,
                "external_total_storage": $externalTotal,
                "external_used_storage": $externalUsed,
                "external_available_storage": $externalAvailable
            }
            """.trimIndent(),
                        ContentType.Application.Json,
                        HttpStatusCode.OK
                    )
                } catch (e: Exception) {
                    KanoLog.d("kano_ZTE_LOG", "获取型号与电量信息出错： ${e.message}")
                    call.response.headers.append("Access-Control-Allow-Origin", "*")
                    call.respondText(
                        """{"error":"获取型号与电量信息出错"}""",
                        ContentType.Application.Json,
                        HttpStatusCode.InternalServerError
                    )
                }
            }

            //检查更新
            get("/api/check_update") {
                try {
                    val path = "/UFI-TOOLS-UPDATE"
                    val downloadUrl = "https://pan.kanokano.cn/d$path/"
                    val changelogUrl = "https://pan.kanokano.cn/d$path/changelog.txt"

                    // 拉取 changelog 文本
                    val changelog = KanoRequest.getTextFromUrl(changelogUrl)

                    // 请求 alist 的 API
                    val requestBody = """
            {
                "path": "$path",
                "password": "",
                "page": 1,
                "per_page": 0,
                "refresh": false
            }
        """.trimIndent()

                    val alistResponse = KanoRequest.postJson(
                        "https://pan.kanokano.cn/api/fs/list",
                        requestBody
                    )

                    val alistBody = alistResponse.body?.string()

                    // 拼装 JSON 响应
                    val resultJson = """
            {
                "base_uri": "$downloadUrl",
                "alist_res": $alistBody,
                "changelog": "${changelog?.replace(Regex("\r?\n"), "<br>")}"
            }
        """.trimIndent()

                    call.response.headers.append("Access-Control-Allow-Origin", "*")
                    call.respondText(resultJson, ContentType.Application.Json, HttpStatusCode.OK)
                } catch (e: Exception) {
                    KanoLog.d("kano_ZTE_LOG", "请求出错：${e.message}")
                    call.response.headers.append("Access-Control-Allow-Origin", "*")
                    call.respondText(
                        """{"error":"请求出错"}""",
                        ContentType.Application.Json,
                        HttpStatusCode.InternalServerError
                    )
                }
            }

            //从URL下载APK
            post("/api/download_apk") {
                try {
                    val body = call.receiveText()
                    val json = JSONObject(body)

                    val apkUrl = json.optString("apk_url", "").trim()
                    if (apkUrl.isEmpty()) {
                        throw IllegalArgumentException("请提供 apk_url")
                    }

                    KanoLog.d("kano_ZTE_LOG", "接收到 apk_url=$apkUrl")

                    synchronized(this) {
                        if (ApkState.downloadInProgress && apkUrl == ApkState.currentDownloadingUrl) {
                            KanoLog.d("kano_ZTE_LOG", "已在下载该 APK，忽略重复请求")
                        } else {
                            ApkState.downloadInProgress = true
                            ApkState.download_percent = 0
                            ApkState.downloadResultPath = null
                            ApkState.downloadError = null
                            ApkState.currentDownloadingUrl = apkUrl

                            val outputFile = File(context.getExternalFilesDir(null), "downloaded_app.apk")
                            if (outputFile.exists()) outputFile.delete()

                            thread {
                                try {
                                    val path = KanoRequest.downloadFile(apkUrl, outputFile) { percent ->
                                        ApkState.download_percent = percent
                                    }
                                    if (path != null) {
                                        ApkState.downloadResultPath = path
                                        KanoLog.d("kano_ZTE_LOG", "下载完成：$path")
                                    } else {
                                        ApkState.downloadError = "下载失败"
                                        KanoLog.d("kano_ZTE_LOG", "下载失败：返回路径为空")
                                    }
                                } catch (e: Exception) {
                                    ApkState.downloadError = e.message ?: "未知错误"
                                    KanoLog.d("kano_ZTE_LOG", "【子线程】下载异常：${e.message}")
                                } finally {
                                    ApkState.downloadInProgress = false
                                }
                            }
                        }
                    }

                    call.response.headers.append("Access-Control-Allow-Origin", "*")
                    call.respondText(
                        """{"result":"download_started"}""",
                        ContentType.Application.Json,
                        HttpStatusCode.OK
                    )
                } catch (e: Exception) {
                    KanoLog.d("kano_ZTE_LOG", "【主线程】执行 /download_apk 出错：${e.message}")
                    call.response.headers.append("Access-Control-Allow-Origin", "*")
                    call.respondText(
                        """{"error":"${e.message ?: "未知错误"}"}""",
                        ContentType.Application.Json,
                        HttpStatusCode.InternalServerError
                    )
                }
            }

            //下载进度
            get("/api/download_apk_status") {
                val status = when {
                    ApkState.downloadInProgress -> "downloading"
                    ApkState.downloadError != null -> "error"
                    ApkState.downloadResultPath != null -> "done"
                    else -> "idle"
                }

                val json = """
        {
            "status":"$status",
            "percent":${ApkState.download_percent},
            "error":"${ApkState.downloadError ?: ""}"
        }
    """.trimIndent()

                call.response.headers.append("Access-Control-Allow-Origin", "*")
                call.respondText(json, ContentType.Application.Json)
            }

            //安装APK
            post("/api/install_apk") {
                val outputChannel = ByteChannel(autoFlush = true)

                launch(Dispatchers.IO) {
                    val writer = OutputStreamWriter(outputChannel.toOutputStream(), Charsets.UTF_8)
                    try {
                        ApkState.downloadResultPath = ApkState.downloadResultPath
                        if (ApkState.downloadResultPath == null) {
                            writer.write("""{"error":"未检测到已下载的 APK"}""")
                            return@launch
                        }

                        val outFileAdb = KanoUtils.copyFileToFilesDir(context, "shell/adb")
                            ?: throw Exception("复制 adb 到 filesDir 失败")
                        outFileAdb.setExecutable(true)

                        // 复制APK到 sdcard 根目录
                        val copyCmd =
                            "${outFileAdb.absolutePath} -s localhost shell sh -c 'cp ${ApkState.downloadResultPath} /sdcard/ufi_tools_latest.apk'"
                        KanoLog.d("kano_ZTE_LOG", "执行：$copyCmd")
                        ShellKano.runShellCommand(copyCmd, context)

                        // 创建并复制 shell 脚本
                        val scriptText = """
                    #!/system/bin/sh
                    pm install -r -g /sdcard/ufi_tools_latest.apk >> /sdcard/ufi_tools_update.log 2>&1
                    am start -n com.minikano.f50_sms/.MainActivity >> /sdcard/ufi_tools_update.log 2>&1
                    echo "$(date) done" >> /sdcard/ufi_tools_update.log
                """.trimIndent()

                        val scriptFile =
                            ShellKano.createShellScript(context, "ufi_tools_update.sh", scriptText)
                        val shPath = scriptFile.absolutePath

                        val copyShCmd =
                            "${outFileAdb.absolutePath} -s localhost shell sh -c 'cp $shPath /sdcard/ufi_tools_update.sh'"
                        KanoLog.d("kano_ZTE_LOG", "执行：$copyShCmd")
                        ShellKano.runShellCommand(copyShCmd, context)

                        suspend fun clickStage() {
                            repeat(5) {
                                ShellKano.runShellCommand(
                                    "${outFileAdb.absolutePath} -s localhost shell input keyevent KEYCODE_WAKEUP",
                                    context
                                )
                                delay(10)
                                ShellKano.runShellCommand(
                                    "${outFileAdb.absolutePath} -s localhost shell input keyevent 82",
                                    context
                                )
                                delay(10)
                                ShellKano.runShellCommand(
                                    "${outFileAdb.absolutePath} -s localhost shell input tap 0 0",
                                    context
                                )
                                delay(10)

                                val result = ShellKano.runShellCommand(
                                    "${outFileAdb.absolutePath} -s localhost shell am start -n com.sprd.engineermode/.EngineerModeActivity",
                                    context
                                )
                                if (result != null) {
                                    val clicked = ShellKano.parseUiDumpAndClick(
                                        "DEBUG&LOG",
                                        outFileAdb.absolutePath,
                                        context
                                    )
                                    if (clicked == 0) {
                                        ShellKano.parseUiDumpAndClick(
                                            "Adb shell",
                                            outFileAdb.absolutePath,
                                            context
                                        )
                                    }
                                    return
                                }
                                delay(400)
                            }
                            throw Exception("click_stage 多次尝试失败")
                        }

                        try {
                            clickStage()
                        } catch (e: Exception) {
                            repeat(10) {
                                ShellKano.runShellCommand(
                                    "${outFileAdb.absolutePath} -s localhost shell input keyevent KEYCODE_BACK",
                                    context
                                )
                            }
                            throw e
                        }

                        try {
                            val escapedCommand =
                                "sh /sdcard/ufi_tools_update.sh".replace("\"", "\\\"")
                            ShellKano.fillInputAndSend(
                                escapedCommand,
                                outFileAdb.absolutePath,
                                context,
                                "",
                                listOf("START", "开始"),
                                needBack = false,
                                useClipBoard = true
                            )
                            writer.write("""{"result":"success"}""")
                        } catch (e: Exception) {
                            writer.write("""{"error":${JSONObject.quote("执行 shell 命令失败: ${e.message}")}}""")
                        }
                    } catch (e: Exception) {
                        writer.write("""{"error":${JSONObject.quote("异常: ${e.message}")}}""")
                    } finally {
                        writer.flush()
                        outputChannel.close()
                    }
                }

                call.response.header(HttpHeaders.AccessControlAllowOrigin, "*")
                call.respondOutputStream(ContentType.Application.Json) {
                    outputChannel.copyTo(this)
                }
            }

            //网络ADB启动状态
            get("/api/adb_alive") {
                call.response.headers.append("Access-Control-Allow-Origin", "*")
                call.respondText(
                    """{"result":"${ADBService.adbIsReady}"}""".trimIndent(),
                    ContentType.Application.Json,
                    HttpStatusCode.OK
                )
            }

            //一键Shell
            get("/api/one_click_shell") {
                val pipedInput = PipedInputStream()
                val pipedOutput = PipedOutputStream(pipedInput)

                CoroutineScope(Dispatchers.IO).launch {
                    val writer = OutputStreamWriter(pipedOutput, Charsets.UTF_8)
                    try {
                        val outFile_adb = KanoUtils.copyFileToFilesDir(context, "shell/adb")
                            ?: throw Exception("复制adb 到filesDir失败")
                        outFile_adb.setExecutable(true)

                        fun click_stage1() {
                            var Eng_result: Any? = null
                            ShellKano.runShellCommand(
                                "${outFile_adb.absolutePath} -s localhost shell input keyevent KEYCODE_WAKEUP",
                                context
                            )
                            Thread.sleep(10)
                            ShellKano.runShellCommand(
                                "${outFile_adb.absolutePath} -s localhost shell input keyevent 82",
                                context
                            )
                            Thread.sleep(10)
                            ShellKano.runShellCommand(
                                "${outFile_adb.absolutePath} -s localhost shell input tap 0 0",
                                context
                            )
                            Thread.sleep(10)
                            repeat(10) {
                                Eng_result = ShellKano.runShellCommand(
                                    "${outFile_adb.absolutePath} -s localhost shell am start -n com.sprd.engineermode/.EngineerModeActivity",
                                    context
                                )
                                KanoLog.d("kano_ZTE_LOG", "工程模式打开结果：$Eng_result")
                            }
                            if (Eng_result == null) {
                                throw Exception("工程模式活动打开失败")
                            }
                            Thread.sleep(400)
                            val res_debug_log_btn = ShellKano.parseUiDumpAndClick(
                                "DEBUG&LOG",
                                outFile_adb.absolutePath,
                                context
                            )
                            if (res_debug_log_btn == -1) throw Exception("点击 DEBUG&LOG 失败")
                            if (res_debug_log_btn == 0) {
                                val res = ShellKano.parseUiDumpAndClick(
                                    "Adb shell",
                                    outFile_adb.absolutePath,
                                    context
                                )
                                if (res == -1) throw Exception("点击 Adb Shell 按钮失败")
                            }
                        }

                        fun tryClickStage1(maxRetry: Int = 2) {
                            var retry = 0
                            while (retry <= maxRetry) {
                                try {
                                    click_stage1()
                                    return
                                } catch (e: Exception) {
                                    KanoLog.w(
                                        "kano_ZTE_LOG",
                                        "click_stage1 执行失败，尝试第 ${retry + 1} 次，错误：${e.message}"
                                    )
                                    repeat(10) {
                                        ShellKano.runShellCommand(
                                            "${outFile_adb.absolutePath} -s localhost shell input keyevent KEYCODE_BACK",
                                            context
                                        )
                                    }
                                    Thread.sleep(1000)
                                    retry++
                                }
                            }
                            throw Exception("click_stage1 多次重试失败")
                        }

                        tryClickStage1()

                        var jsonResult = """{"result":"执行成功"}"""
                        try {
                            val escapedCommand =
                                "sh /sdcard/one_click_shell.sh".replace("\"", "\\\"")
                            ShellKano.fillInputAndSend(
                                escapedCommand,
                                outFile_adb.absolutePath,
                                context,
                                "",
                                listOf("START", "开始"),
                                useClipBoard = true
                            )
                        } catch (e: Exception) {
                            jsonResult = """{"result":"执行失败"}"""
                        }
                        writer.write(jsonResult)
                    } catch (e: Exception) {
                        writer.write("""{"error":"one_click_shell执行错误：${e.message}"}""")
                    } finally {
                        writer.flush()
                        pipedOutput.close()
                    }
                }

                call.response.headers.append("Access-Control-Allow-Origin", "*")
                call.respondOutputStream(
                    contentType = ContentType.Application.Json,
                    status = HttpStatusCode.OK
                ) {
                    pipedInput.copyTo(this)
                }
            }

            //获取短信转发方式
            get("/api/sms_forward_method") {
                val sharedPrefs =
                    context.getSharedPreferences("kano_ZTE_store", Context.MODE_PRIVATE)
                val sms_forward_method = sharedPrefs.getString("kano_sms_forward_method", "") ?: ""
                val json = """
        {
            "sms_forward_method": "${sms_forward_method.replace("\"", "\\\"")}"
        }
    """.trimIndent()

                call.response.headers.append("Access-Control-Allow-Origin", "*")
                call.respondText(json, ContentType.Application.Json, HttpStatusCode.OK)
            }

            //短信转发参数存入-邮件
            post("/api/sms_forward_mail") {
                try {
                    val body = call.receiveText()
                    val json = JSONObject(body)

                    val smtpHost = json.optString("smtp_host", "").trim()
                    val smtpPort = json.optString("smtp_port", "465").trim()
                    val smtpTo = json.optString("smtp_to", "").trim()
                    val smtpUsername = json.optString("smtp_username", "").trim()
                    val smtpPassword = json.optString("smtp_password", "").trim()

                    if (smtpTo.isEmpty() || smtpHost.isEmpty() || smtpUsername.isEmpty() || smtpPassword.isEmpty()) {
                        throw Exception("缺少必要参数")
                    }

                    val sharedPrefs =
                        context.getSharedPreferences("kano_ZTE_store", Context.MODE_PRIVATE)
                    sharedPrefs.edit().apply {
                        putString("kano_sms_forward_method", "SMTP")
                        putString("kano_smtp_host", smtpHost)
                        putString("kano_smtp_port", smtpPort)
                        putString("kano_smtp_to", smtpTo)
                        putString("kano_smtp_username", smtpUsername)
                        putString("kano_smtp_password", smtpPassword)
                        apply()
                    }

                    KanoLog.d("kano_ZTE_LOG", "SMTP配置已保存：$smtpHost:$smtpPort [$smtpUsername]")

                    val test_msg = SmsInfo("1010721", "UFI-TOOLS TEST消息", 0)
                    SmsPoll.forwardByEmail(test_msg, context)

                    call.response.headers.append("Access-Control-Allow-Origin", "*")
                    call.respondText(
                        """{"result":"success"}""",
                        ContentType.Application.Json,
                        HttpStatusCode.OK
                    )

                } catch (e: Exception) {
                    KanoLog.d("kano_ZTE_LOG", "SMTP配置出错： ${e.message}")
                    call.response.headers.append("Access-Control-Allow-Origin", "*")
                    call.respondText(
                        """{"error":"SMTP配置出错"}""",
                        ContentType.Application.Json,
                        HttpStatusCode.InternalServerError
                    )
                }
            }

            //读取smtp配置
            get("/api/sms_forward_mail") {
                val sharedPrefs =
                    context.getSharedPreferences("kano_ZTE_store", Context.MODE_PRIVATE)

                val smtpHost = sharedPrefs.getString("kano_smtp_host", "") ?: ""
                val smtpPort = sharedPrefs.getString("kano_smtp_port", "") ?: ""
                val smtpTo = sharedPrefs.getString("kano_smtp_to", "") ?: ""
                val username = sharedPrefs.getString("kano_smtp_username", "") ?: ""
                val password = sharedPrefs.getString("kano_smtp_password", "") ?: ""

                val json = """
        {
            "smtp_host": "$smtpHost",
            "smtp_port": "$smtpPort",
            "smtp_to": "$smtpTo",
            "smtp_username": "$username",
            "smtp_password": "$password"
        }
    """.trimIndent()

                call.respondText(json, ContentType.Application.Json, HttpStatusCode.OK)
            }

            //短信转发参数存入-curl
            post("/api/sms_forward_curl") {
                try {
                    val body = call.receiveText()
                    val json = JSONObject(body)

                    val originalCurl = json.getString("curl_text")

                    KanoLog.d("kano_ZTE_LOG", "是否找到{{sms}}：${originalCurl.contains("{{sms}}")}")
                    KanoLog.d("kano_ZTE_LOG", "curl配置：$originalCurl")

                    if (!originalCurl.contains("{{sms-body}}")) throw Exception("没有找到“{{sms-body}}”占位符")
                    if (!originalCurl.contains("{{sms-time}}")) throw Exception("没有找到“{{sms-time}}”占位符")
                    if (!originalCurl.contains("{{sms-from}}")) throw Exception("没有找到“{{sms-from}}”占位符")

                    // 存储到 SharedPreferences
                    val sharedPrefs =
                        context.getSharedPreferences("kano_ZTE_store", Context.MODE_PRIVATE)
                    sharedPrefs.edit().apply {
                        putString("kano_sms_forward_method", "CURL")
                        putString("kano_sms_curl", originalCurl)
                        apply()
                    }

                    // 发送测试消息
                    val test_msg =
                        SmsInfo("11451419198", "UFI-TOOLS TEST消息", System.currentTimeMillis())
                    SmsPoll.forwardSmsByCurl(test_msg, context)

                    json.put("curl_text", originalCurl)

                    call.response.headers.append("Access-Control-Allow-Origin", "*")
                    call.respondText(
                        """{"result":"success"}""",
                        ContentType.Application.Json,
                        HttpStatusCode.OK
                    )
                } catch (e: Exception) {
                    KanoLog.d("kano_ZTE_LOG", "curl配置出错：${e.message}")
                    call.response.headers.append("Access-Control-Allow-Origin", "*")
                    call.respondText(
                        """{"error":"curl配置出错：${e.message}"}""",
                        ContentType.Application.Json,
                        HttpStatusCode.InternalServerError
                    )
                }
            }

            //读取短信转发curl配置
            get("/api/sms_forward_curl") {
                val sharedPrefs =
                    context.getSharedPreferences("kano_ZTE_store", Context.MODE_PRIVATE)

                val curlText = sharedPrefs.getString("kano_sms_curl", "") ?: ""

                val json = JSONObject(mapOf("curl_text" to curlText)).toString()

                call.respondText(
                    json,
                    ContentType.Application.Json,
                    HttpStatusCode.OK
                )
            }

            //短信转发总开关
            post("/api/sms_forward_enabled") {
                try {
                    val enable = call.request.queryParameters["enable"]
                        ?: throw Exception("query 缺少 enable 参数")
                    KanoLog.d("kano_ZTE_LOG", "短信转发 enable 传入参数：$enable")

                    val sharedPrefs =
                        context.getSharedPreferences("kano_ZTE_store", Context.MODE_PRIVATE)
                    sharedPrefs.edit().apply {
                        putString("kano_sms_forward_enabled", enable)
                        apply()
                    }

                    call.response.headers.append("Access-Control-Allow-Origin", "*")
                    call.respondText(
                        """{"result":"success"}""",
                        ContentType.Application.Json,
                        HttpStatusCode.OK
                    )
                } catch (e: Exception) {
                    KanoLog.d("kano_ZTE_LOG", "请求出错： ${e.message}")
                    call.response.headers.append("Access-Control-Allow-Origin", "*")
                    call.respondText(
                        """{"error":"请求出错"}""",
                        ContentType.Application.Json,
                        HttpStatusCode.InternalServerError
                    )
                }
            }

            //获取短信转发状态
            get("/api/sms_forward_enabled") {
                try {
                    val sharedPrefs =
                        context.getSharedPreferences("kano_ZTE_store", Context.MODE_PRIVATE)
                    val str = sharedPrefs.getString("kano_sms_forward_enabled", "0") ?: "0"

                    call.response.headers.append("Access-Control-Allow-Origin", "*")
                    call.respondText(
                        """{"enabled":"$str"}""",
                        ContentType.Application.Json,
                        HttpStatusCode.OK
                    )
                } catch (e: Exception) {
                    KanoLog.d("kano_ZTE_LOG", "请求出错： ${e.message}")
                    call.response.headers.append("Access-Control-Allow-Origin", "*")
                    call.respondText(
                        """{"error":"请求出错"}""",
                        ContentType.Application.Json,
                        HttpStatusCode.InternalServerError
                    )
                }
            }

            //读取、保存自定义头部
            post("/api/set_custom_head") {
                try {
                    val body = call.receiveText()
                    val bodyBytes = body.toByteArray(Charsets.UTF_8)
                    val maxSizeInBytes = 1145 * 1024

                    if (bodyBytes.size > maxSizeInBytes) {
                        throw Exception("自定义头部超出限制: ${bodyBytes.size / 1145}KB/1024KB")
                    }

                    val json = JSONObject(body)
                    val text = json.optString("text", "").trim()

                    val sharedPref =
                        context.getSharedPreferences("kano_ZTE_store", Context.MODE_PRIVATE)
                    sharedPref.edit().apply {
                        putString("kano_custom_head", text)
                        apply()
                    }

                    call.response.headers.append("Access-Control-Allow-Origin", "*")
                    call.respondText(
                        """{"result":"success"}""",
                        ContentType.Application.Json,
                        HttpStatusCode.OK
                    )

                } catch (e: Exception) {
                    KanoLog.d("kano_ZTE_LOG", "配置出错： ${e.message}")
                    call.response.headers.append("Access-Control-Allow-Origin", "*")
                    call.respondText(
                        """{"error":"配置出错: ${e.message}"}""",
                        ContentType.Application.Json,
                        HttpStatusCode.InternalServerError
                    )
                }
            }
        }
        //读取自定义头部
        get("/api/get_custom_head") {
            try {
                val sharedPref =
                    context.getSharedPreferences("kano_ZTE_store", Context.MODE_PRIVATE)
                val text = sharedPref.getString("kano_custom_head", "") ?: ""
                val json = JSONObject(mapOf("text" to text)).toString()

                call.response.headers.append("Access-Control-Allow-Origin", "*")
                call.respondText(
                    json,
                    ContentType.Application.Json,
                    HttpStatusCode.OK
                )
            } catch (e: Exception) {
                KanoLog.d("kano_ZTE_LOG", "读取自定义头部出错： ${e.message}")
                call.response.headers.append("Access-Control-Allow-Origin", "*")
                call.respondText(
                    """{"error":"读取自定义头部出错"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.InternalServerError
                )
            }
        }

    }
}