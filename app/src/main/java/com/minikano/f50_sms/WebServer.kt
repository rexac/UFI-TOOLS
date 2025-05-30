package com.minikano.f50_sms

import android.content.Context
import android.os.Build
import android.os.StatFs
import android.util.Log
import com.minikano.f50_sms.ShellKano.Companion.fillInputAndSend
import com.minikano.f50_sms.ShellKano.Companion.runShellCommand
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStreamWriter
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Random
import kotlin.concurrent.thread

class WebServer(context: Context, port: Int, gatewayIp: String) : NanoHTTPD(port) {

    private val targetServer = "http://$gatewayIp"  // 目标服务器地址
    private val targetServerIP = gatewayIp  // 目标服务器地址
    private val PREFS_NAME = "kano_ZTE_store"
    private val PREF_LOGIN_TOKEN = "login_token"
    private val PREF_TOKEN_ENABLED = "login_token_enabled"
    private val REQUEST_SECRET_KEY = "minikano_kOyXz0Ciz4V7wR0IeKmJFYFQ20jd"
    private val allowedTimeSkew = 114 * 1000
    @Volatile
    var downloadInProgress = false

    @Volatile
    var download_percent = 0

    @Volatile
    var downloadResultPath: String? = null

    @Volatile
    var downloadError: String? = null

    @Volatile
    var currentDownloadingUrl: String = ""
/*
    init {
        try {
            val password = "kano123".toCharArray()

            val assetManager = context.assets
            val keystoreInputStream = assetManager.open("certs/192_168_0_1_ssl.bks")

            val ks = KeyStore.getInstance("BKS") // 改为 BKS
            ks.load(keystoreInputStream, password)
            keystoreInputStream.close()

            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            kmf.init(ks, password)

            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(kmf.keyManagers, null, null)

            makeSecure(sslContext.serverSocketFactory, null)

            Log.i("kano_ZTE_LOG", "SSL 启用成功")

        } catch (e: Exception) {
            Log.e("kano_ZTE_LOG", "SSL失败: ${e.message}", e)
        }
    }
 */

    val apiWhiteList:List<String> = listOf(
        "/api/get_custom_head"
    )

    override fun serve(session: IHTTPSession?): Response {
        val oUri = session?.uri.orEmpty()
        val method = session?.method.toString()

        Log.d("kano_security_server", "Got request: ${session?.method} ${session?.uri}")

        // 静态资源：不鉴权，直接返回
        if (!oUri.startsWith("/api")) {
            return serveStaticFile(oUri.ifEmpty { "/" })
        }

        // 动态接口：需要鉴权
        val sharedPrefs = context_app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val token = sharedPrefs.getString(PREF_LOGIN_TOKEN, "admin")
        val tokenEnabled = sharedPrefs.getString(PREF_TOKEN_ENABLED, "true").toBoolean()

        val noAuth = apiWhiteList.any { !oUri.startsWith(it) }

        if (tokenEnabled && noAuth) {
            val headers = session?.headers ?: return unauthorized()
            val timestampStr = headers["kano-t"]
            val clientSignature = headers["kano-sign"]
            val authHeader = headers["authorization"]

            // 鉴权必需字段不能为空
            if (timestampStr == null || clientSignature == null) {
                return unauthorized()
            }
            if (authHeader == null || token == null) {
                return unauthorized()
            }
            if (authHeader != KanoUtils.sha256Hex(token)) {
                return unauthorized()
            }

            // 验证时间戳
            val clientTimestamp = timestampStr.toLongOrNull() ?: return unauthorized()
            /*
            val currentTimestamp = System.currentTimeMillis()
            if (kotlin.math.abs(currentTimestamp - clientTimestamp) > allowedTimeSkew) {
                Log.w("kano_security_server", "请求时间戳不符合范围，已拦截")
                return unauthorized()
            }
             */
            // 构造原始签名数据
            val raw = "minikano$method$oUri$clientTimestamp"
            val expectedSignature = KanoUtils.HmacSignature(REQUEST_SECRET_KEY, raw)

            if (!expectedSignature.equals(clientSignature, ignoreCase = true)) {
                Log.w("kano_security_server", "签名不正确，已拦截,raw:$raw")
                return unauthorized()
            }
        }

//        // 动态接口：需要鉴权
//        val sharedPrefs = context_app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
//        val token = sharedPrefs.getString(PREF_LOGIN_TOKEN, "admin")
//        val tokenEnabled = sharedPrefs.getString(PREF_TOKEN_ENABLED, "true").toBoolean()
//
//        if (tokenEnabled) {
//            val headers = session?.headers ?: return unauthorized()
//            val authHeader = headers["authorization"]
//            if (authHeader != token) {
//                return unauthorized()
//            }
//        }

        // 去掉 /api 前缀
        val uri = oUri.removePrefix("/api")

        //客户端IP
        if(method == "GET" && uri =="/ip"){
            return try {
                val ip = session?.headers?.get("http-client-ip") ?: throw Exception("无法获取客户端IP地址")
                Log.d("kano_ZTE_LOG", "获取客户端IP成功: $ip")
                val response = newFixedLengthResponse(
                    Response.Status.OK, "application/json", """{"ip":"${ip}"}"""
                )
                response.addHeader("Access-Control-Allow-Origin", "*")
                response
            } catch (e: Exception) {
                Log.d("kano_ZTE_LOG", "获取客户端IP出错： ${e.message}")
                val response = newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR,
                    "application/json",
                    """{"error":"获取客户端IP失败"}"""
                )
                response.addHeader("Access-Control-Allow-Origin", "*")
                response
            }
        }

        //cpu温度
        if (method == "GET" && uri == "/temp") {
            return try {
                val temp = ShellKano.executeShellFromAssetsSubfolder(context_app, "shell/temp.sh")
                val temp1 = ShellKano.runShellCommand("cat /sys/class/thermal/thermal_zone1/temp")
                Log.d("kano_ZTE_LOG", "获取CPU温度成功: $temp")
                val response = newFixedLengthResponse(
                    Response.Status.OK, "application/json", """{"temp":${temp ?: temp1}}"""
                )
                response.addHeader("Access-Control-Allow-Origin", "*")
                response
            } catch (e: Exception) {
                Log.d("kano_ZTE_LOG", "获取CPU温度出错： ${e.message}")
                val response = newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR,
                    "application/json",
                    """{"error":"获取CPU温度失败"}"""
                )
                response.addHeader("Access-Control-Allow-Origin", "*")
                response
            }
        }

        //cpu使用率
        if (method == "GET" && uri == "/cpu") {
            return try {
                val stat1 =
                    runShellCommand("cat /proc/stat") ?: throw Exception("stat1没有数据")
                val (total1, idle1) = KanoUtils.parseCpuStat(stat1)
                    ?: throw Exception("parseCpuStat执行失败")
                val stat2 =
                    runShellCommand("cat /proc/stat") ?: throw Exception("stat2没有数据")
                val (total2, idle2) = KanoUtils.parseCpuStat(stat2)
                    ?: throw Exception("parseCpuStat执行失败")
                val totalDiff = total2 - total1
                val idleDiff = idle2 - idle1
                val usage = if (totalDiff > 0) (totalDiff - idleDiff).toFloat() / totalDiff else 0f

                Log.d("kano_ZTE_LOG", "CPU 使用率：%.2f%%".format(usage * 100))
                val response = newFixedLengthResponse(
                    Response.Status.OK, "application/json", """{"cpu":${usage * 100}}"""
                )
                response.addHeader("Access-Control-Allow-Origin", "*")
                response
            } catch (e: Exception) {
                Log.d("kano_ZTE_LOG", "获取cpu使用率出错： ${e.message}")
                val response = newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR,
                    "application/json",
                    """{"error":"获取cpu使用率出错"}"""
                )
                response.addHeader("Access-Control-Allow-Origin", "*")
                response
            }
        }

        //内存使用率
        if (method == "GET" && uri == "/mem") {
            return try {
                val info = runShellCommand("cat /proc/meminfo") ?: throw Exception("没有info")
                val usage = KanoUtils.parseMeminfo(info)
                Log.d("kano_ZTE_LOG", "内存使用率：%.2f%%".format(usage * 100))
                val response = newFixedLengthResponse(
                    Response.Status.OK, "application/json", """{"mem":${usage * 100}}"""
                )
                response.addHeader("Access-Control-Allow-Origin", "*")
                response
            } catch (e: Exception) {
                Log.d("kano_ZTE_LOG", "获取内存信息出错： ${e.message}")
                val response = newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR,
                    "application/json",
                    """{"error":"获取内存信息失败"}"""
                )
                response.addHeader("Access-Control-Allow-Origin", "*")
                response
            }
        }

        //自启无线adb
        if (method == "GET" && uri == "/adb_wifi_setting") {
            return try {
                val sharedPrefs = context_app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val ADB_IP_ENABLED = sharedPrefs.getString("ADB_IP_ENABLED", "false")
                val response = newFixedLengthResponse(
                    Response.Status.OK, "application/json", """{"enabled":$ADB_IP_ENABLED}"""
                )
                response.addHeader("Access-Control-Allow-Origin", "*")
                response
            } catch (e: Exception) {
                Log.d("kano_ZTE_LOG", "获取网络adb信息出错： ${e.message}")
                val response = newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR,
                    "application/json",
                    """{"error":"获取网络adb信息失败"}"""
                )
                response.addHeader("Access-Control-Allow-Origin", "*")
                response
            }
        }

        //发送AT指令
        if (method == "GET" && uri == "/AT") {
            // 创建一个 Piped 流来异步返回数据
            val pipedInput = PipedInputStream()
            val pipedOutput = PipedOutputStream(pipedInput)

            // 使用协程处理耗时任务
            CoroutineScope(Dispatchers.IO).launch {
                val writer = OutputStreamWriter(pipedOutput, Charsets.UTF_8)
                try {
                    // 解析 query 参数
                    val rawParams = session?.parameters ?: throw Exception("缺少 query 参数")
                    val AT_command_arr =
                        rawParams["command"] ?: throw Exception("qeury 缺少 command 参数")
                    val AT_slot_arr = rawParams["slot"] ?: throw Exception("qeury 缺少 slot 参数")
                    val AT_command = AT_command_arr[0]
                    val AT_slot = AT_slot_arr.getOrNull(0)?.toIntOrNull() ?: 0 // 如果取不到或不是数字，就用 0
                    Log.d("kano_ZTE_LOG", "AT_command 传入参数：${AT_command}")

                    //复制依赖
                    val outFile_at = KanoUtils.copyFileToFilesDir(context_app, "shell/sendat")
                        ?: throw Exception("复制sendat 到filesDir失败")

                    outFile_at.setExecutable(true)

                    //AT+CGEQOSRDP=1
                    if (!AT_command.toString().startsWith("AT", ignoreCase = true)) {
                        throw Exception("解析失败，AT指令需要以 “AT” 开头")
                    }
                    val command =
                        "${outFile_at.absolutePath} -n $AT_slot -c '${AT_command.trimStart()}'"
                    val result = runShellCommand(command, true) ?: throw Exception("没有输出")
                    var res = result.replace("\"", "\\\"")      // 转义引号
                        .replace("\n", "")          // 去掉换行
                        .replace("\r", "")          // 去掉回车
                        .trimStart()
                    if (res.lowercase().endsWith("ok")) {
                        res = res.dropLast(2).trimEnd() + " OK"
                    }
                    // 去掉开头的逗号
                    if (res.startsWith(",")) {
                        res = res.removePrefix(",").trimStart()
                    }
                    Log.d("kano_ZTE_LOG", "AT_cmd：$command")
                    Log.d("kano_ZTE_LOG", "AT_result：$res")
                    val jsonResult = """{"result":"${res}"}"""
                    writer.write(jsonResult)
                } catch (e: Exception) {
                    writer.write("""{"error":"AT指令执行错误：${e.message}"}""")
                } finally {
                    writer.flush()
                    pipedOutput.close()
                }
            }

            val response = newChunkedResponse(
                Response.Status.OK, "application/json", pipedInput
            )
            response.addHeader("Access-Control-Allow-Origin", "*")
            return response
        }

        //更改samba分享地址为根目录
        if (method == "GET" && uri == "/smbPath") {
            // 创建一个 Piped 流来异步返回数据
            val pipedInput = PipedInputStream()
            val pipedOutput = PipedOutputStream(pipedInput)

            // 使用协程处理耗时任务
            CoroutineScope(Dispatchers.IO).launch {
                val writer = OutputStreamWriter(pipedOutput, Charsets.UTF_8)
                try {
                    // 解析 query 参数
                    val rawParams = session?.parameters ?: throw Exception("缺少 query 参数")
                    val qeury_arr =
                        rawParams["enable"] ?: throw Exception("qeury 缺少 enable 参数")
                    val enabled = qeury_arr[0]
                    Log.d("kano_ZTE_LOG", "enable 传入参数：${enabled}")

                    //复制依赖
                    val outFile_adb = KanoUtils.copyFileToFilesDir(context_app, "shell/adb")
                        ?: throw Exception("复制adb 到filesDir失败")
                    val smb_path = SMBConfig.writeConfig(context_app)
                        ?: throw Exception("复制smb.conf 到filesDir失败")
                    val outFile_ttyd = KanoUtils.copyFileToFilesDir(context_app, "shell/ttyd")
                        ?: throw Exception("复制ttyd 到filesDir失败")
                    val outFile_socat =
                        KanoUtils.copyFileToFilesDir(context_app, "shell/socat") ?: throw Exception(
                            "socat 到filesDir失败"
                        )
                    val outFile_smb_sh =
                        KanoUtils.copyFileToFilesDir(context_app, "shell/samba_exec.sh", false)
                            ?: throw Exception("复制samba_exec.sh 到filesDir失败")

                    outFile_smb_sh.setExecutable(true)
                    outFile_adb.setExecutable(true)
                    outFile_ttyd.setExecutable(true)
                    outFile_socat.setExecutable(true)
                    var jsonResult = """{"result":"执行成功，重启生效！"}"""

                    if (enabled == "1") {
                        //输入指令，点击发送
                        runShellCommand(
                            "${outFile_adb.absolutePath} -s localhost shell cat ${smb_path} > /data/samba/etc/smb.conf",
                            context = context_app
                        ) ?: throw Exception("修改smb.conf失败")
                        jsonResult = """{"result":"执行成功，等待一会儿即可生效！"}"""
                    } else {
                        val script = """
                            sh /sdcard/ufi_tools_boot.sh
                            chattr -i /data/samba/etc/smb.conf
                            chmod 644 /data/samba/etc/smb.conf
                            rm -f /data/samba/etc/smb.conf
                            sync
                        """.trimIndent()
                        val socketPath =
                            File(context_app.filesDir.absolutePath, "kano_root_shell.sock")
                        if (!socketPath.exists()) {
                            throw Exception("执行失败，没有找到socat创建的sock")
                        }
                        val result = RootShell.sendCommandToSocket(script, socketPath.absolutePath)
                            ?: throw Exception("删除smb.conf失败")
                        Log.d("kano_ZTE_LOG", "sendCommandToSocket Output:\n$result")
                    }

                    //刷新SMB
                    Log.d("kano_ZTE_LOG", "刷新SMB中...")
                    SmbThrottledRunner.runOnceInThread(context_app)

                    writer.write(jsonResult)
                } catch (e: Exception) {
                    writer.write("""{"error":"执行错误：${e.message}"}""")
                } finally {
                    writer.flush()
                    pipedOutput.close()
                }
            }

            val response = newChunkedResponse(
                Response.Status.OK, "application/json", pipedInput
            )
            response.addHeader("Access-Control-Allow-Origin", "*")
            return response
        }

        //adb自启
        if (method == "POST" && uri == "/adb_wifi_setting") {
            return try {
                val map = HashMap<String, String>()
                session?.parseBody(map)  // 必须调用 parseBody 才能安全读 inputStream

                val body = map["postData"] ?: throw Exception("postData is null")
                val json = JSONObject(body)

                val enabled = json.optBoolean("enabled", false)
                val password = json.optString("password", "")

                Log.d("kano_ZTE_LOG", "接收到ADB_WIFI配置：enabled=$enabled, password=$password")

                val sharedPrefs = context_app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

                val host = targetServerIP.substringBefore(":")

                //保存
                if (enabled == true) {
                    sharedPrefs.edit().putString("ADMIN_PWD", password)
                        .putString("ADB_IP_ENABLED", "true").apply()
                } else {
                    sharedPrefs.edit().remove("ADMIN_PWD")
                        .putString("ADB_IP_ENABLED", "false").apply()
                }

                Log.d(
                    "kano_ZTE_LOG", "ADMIN_PWD:${
                        sharedPrefs.getString("ADMIN_PWD", "")
                    }"
                )

                val response = newFixedLengthResponse(
                    Response.Status.OK,
                    "application/json",
                    """{"result":"success","enabled":"$enabled"}"""
                )
                response.addHeader("Access-Control-Allow-Origin", "*")
                response
            } catch (e: Exception) {
                Log.d("kano_ZTE_LOG", "解析ADB_WIFI POST 请求出错：${e.message}")
                val response = newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR,
                    "application/json",
                    """{"error":"参数解析失败"}"""
                )
                response.addHeader("Access-Control-Allow-Origin", "*")
                response
            }
        }

        //型号与电量
        if (method == "GET" && uri == "/battery_and_model") {
            return try {
                val model = Build.MODEL // 设备型号
                val batteryLevel: Int = KanoUtils.getBatteryPercentage(context_app)// 充电状态
                val packageManager = context_app.packageManager
                val packageName = context_app.packageName
                val versionName = packageManager.getPackageInfo(packageName, 0).versionName
                val versionCode = packageManager.getPackageInfo(packageName, 0).versionCode

                Log.d("kano_ZTE_LOG", "型号与电量：$model $batteryLevel")

                val response = newFixedLengthResponse(
                    Response.Status.OK,
                    "application/json",
                    """{"app_ver":"$versionName","app_ver_code":"$versionCode","model":"$model","battery":"$batteryLevel"}"""
                )
                response.addHeader("Access-Control-Allow-Origin", "*")
                response
            } catch (e: Exception) {
                Log.d("kano_ZTE_LOG", "获取型号与电量信息出错： ${e.message}")
                val response = newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR,
                    "application/json",
                    """{"error":"获取型号与电量信息出错"}"""
                )
                response.addHeader("Access-Control-Allow-Origin", "*")
                response
            }
        }

        //测速
        // from https://github.com/bg6cq/speedtest/blob/master/backend/garbage.php
        // 优化后的测速接口
        if (method == "GET" && uri == "/speedtest") {
            return try {
                val parms = session?.parameters ?: throw Exception("缺少 query 参数")
                val ckSize = KanoUtils.getChunkCount(parms["ckSize"]?.firstOrNull())
                val enableCors = parms.containsKey("cors")

                // Headers
                val headers = mutableMapOf<String, String>()
                if (enableCors) {
                    headers["Access-Control-Allow-Origin"] = "*"
                    headers["Access-Control-Allow-Methods"] = "GET, POST"
                }

                headers["Content-Description"] = "File Transfer"
                headers["Content-Type"] = "application/octet-stream"
                headers["Content-Disposition"] = "attachment; filename=random.dat"
                headers["Content-Transfer-Encoding"] = "binary"
                headers["Cache-Control"] =
                    "no-store, no-cache, must-revalidate, max-age=0, s-maxage=0"
                headers["Pragma"] = "no-cache"

                val chunkSizeBytes = 1024 * 1024  // 每个chunk 1MB
                val totalChunks = ckSize

                // 预生成一个随机1MB buffer
                val fixedBuffer = ByteArray(chunkSizeBytes).apply {
                    Random().nextBytes(this)
                }

                // 构造高效输出流
                val stream = object : InputStream() {
                    private var chunksSent = 0
                    private var bufferPos = 0

                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        if (chunksSent >= totalChunks) return -1

                        val bytesRemaining = chunkSizeBytes - bufferPos
                        val bytesToCopy = minOf(len, bytesRemaining)

                        System.arraycopy(fixedBuffer, bufferPos, b, off, bytesToCopy)
                        bufferPos += bytesToCopy

                        if (bufferPos >= chunkSizeBytes) {
                            chunksSent++
                            bufferPos = 0
                        }

                        return bytesToCopy
                    }

                    override fun read(): Int {
                        // fallback 单字节读取，仍然使用已有buffer
                        if (chunksSent >= totalChunks) return -1
                        val byteValue = fixedBuffer[bufferPos++].toInt() and 0xFF
                        if (bufferPos >= chunkSizeBytes) {
                            bufferPos = 0
                            chunksSent++
                        }
                        return byteValue
                    }
                }

                val response =
                    newChunkedResponse(Response.Status.OK, "application/octet-stream", stream)

                headers.forEach { (k, v) -> response.addHeader(k, v) }

                response
            } catch (e: Exception) {
                Log.d("kano_ZTE_LOG", "测速出错： ${e.message}")
                val response = newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR, "application/json", """{"error":"测速出错"}"""
                )
                response.addHeader("Access-Control-Allow-Origin", "*")
                response
            }
        }

        //判断是否存在TTYD
        if (method == "GET" && uri == "/hasTTYD") {
            return try {
                // 解析 query 参数
                val rawParams = session?.parameters ?: throw Exception("缺少 port 参数")
                val portParam = rawParams["port"] ?: throw Exception("qeury 缺少 port 参数")

                val host = targetServerIP.substringBefore(":")

                val code = getStatusCode("http://$host:${portParam[0]}")

                Log.d("kano_ZTE_LOG", "TTYD获取ip+port信息： ${"$host:$portParam 返回code:$code"}")

                val response = newFixedLengthResponse(
                    Response.Status.OK,
                    "application/json",
                    """{"code":"$code","ip":"$host:${portParam[0]}"}"""
                )
                response.addHeader("Access-Control-Allow-Origin", "*")
                response
            } catch (e: Exception) {
                Log.d("kano_ZTE_LOG", "获取TTYD信息出错： ${e.message}")
                val response = newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR,
                    "application/json",
                    """{"error":"获取TTYD信息出错:${e.message}"}"""
                )
                response.addHeader("Access-Control-Allow-Origin", "*")
                response
            }
        }

        //存储获取
        if (method == "GET" && uri == "/storage_and_dailyData") {
            return try {
                //内部存储
                val internalStorage: File = context_app.filesDir
                val statFs = StatFs(internalStorage.absolutePath)
                val totalSize = (statFs.blockSizeLong * statFs.blockCountLong)
                val availableSize = (statFs.blockSizeLong * statFs.availableBlocksLong)
                val usedSize = totalSize - availableSize

                val dailyData = (getCachedTodayUsage(context_app))

                //外部存储
                val ex_storage_info = KanoUtils.getRemovableStorageInfo(context_app)
                var ex_storage_total_size = ex_storage_info?.totalBytes ?: 0
                var ex_storage_avalible_size = ex_storage_info?.availableBytes ?: 0
                var ex_storage_used_size = ex_storage_total_size - ex_storage_avalible_size


                Log.d("kano_ZTE_LOG", "日用流量：$dailyData")
                Log.d("kano_ZTE_LOG", "内部存储：$usedSize/$totalSize")
                Log.d(
                    "kano_ZTE_LOG",
                    "外部存储：${(ex_storage_info?.availableBytes ?: 0)}/${(ex_storage_info?.totalBytes ?: 0)}"
                )

                val response = newFixedLengthResponse(
                    Response.Status.OK,
                    "application/json",
                    """{"daily_data":$dailyData,"internal_available_storage":$availableSize,"internal_used_storage":$usedSize,"internal_total_storage":$totalSize,"external_total_storage":${(ex_storage_info?.totalBytes ?: 0)},"external_used_storage":$ex_storage_used_size,"external_available_storage":${(ex_storage_info?.availableBytes ?: 0)}}""".trimIndent()
                )
                response.addHeader("Access-Control-Allow-Origin", "*")
                response
            } catch (e: Exception) {
                Log.d("kano_ZTE_LOG", "获取型号与电量信息出错： ${e.message}")
                val response = newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR,
                    "application/json",
                    """{"error":"获取型号与电量信息出错"}"""
                )
                response.addHeader("Access-Control-Allow-Origin", "*")
                response
            }
        }

        //检查更新
        if (method == "GET" && uri == "/check_update") {
            return try {
                val path = "/UFI-TOOLS-UPDATE"
                val download_url = "https://pan.kanokano.cn/d${path}/"
                //changelog
                val changelog_url = "https://pan.kanokano.cn/d${path}/changelog.txt"
                val changelog = KanoRequest.getTextFromUrl(changelog_url)
                val json =
                    """{"path":"$path","password":"","page":1,"per_page":0,"refresh":false}""".trimIndent()
                val res = KanoRequest.postJson(
                    "https://pan.kanokano.cn/api/fs/list", json
                )
                val response = newFixedLengthResponse(
                    Response.Status.OK,
                    "application/json",
                    """{"base_uri":"${download_url}","alist_res":${res.body?.string()},"changelog":"${
                        changelog?.replace(
                            Regex("\r?\n"),
                            "<br>"
                        )
                    }"}""".trimIndent()
                )
                response.addHeader("Access-Control-Allow-Origin", "*")
                response
            } catch (e: Exception) {
                Log.d("kano_ZTE_LOG", "请求出错： ${e.message}")
                val response = newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR, "application/json", """{"error":"请求出错"}"""
                )
                response.addHeader("Access-Control-Allow-Origin", "*")
                response
            }
        }

        //从URL下载apk
        if (method == "POST" && uri == "/download_apk") {
            return try {
                val map = HashMap<String, String>()
                session?.parseBody(map)
                val body = map["postData"] ?: throw Exception("postData is null")
                val json = JSONObject(body)

                val apk_url = json.optString("apk_url", "").trim()
                if (apk_url.isEmpty()) {
                    throw Exception("请提供apk_url")
                }

                Log.d("kano_ZTE_LOG", "接收到apk_url=$apk_url")

                synchronized(this) {
                    if (downloadInProgress && apk_url == currentDownloadingUrl) {
                        Log.d("kano_ZTE_LOG", "已在下载该 APK，忽略重复请求")
                    } else {
                        downloadInProgress = true
                        download_percent = 0
                        downloadResultPath = null
                        downloadError = null
                        currentDownloadingUrl = apk_url

                        val outputFile =
                            File(context_app.getExternalFilesDir(null), "downloaded_app.apk")
                        if (outputFile.exists()) outputFile.delete()

                        thread {
                            try {
                                val path =
                                    KanoRequest.downloadFile(apk_url, outputFile) { percent ->
                                        download_percent = percent
                                    }
                                if (path != null) {
                                    downloadResultPath = path
                                    Log.d("kano_ZTE_LOG", "下载完成：$path")
                                } else {
                                    downloadError = "下载失败"
                                    Log.d("kano_ZTE_LOG", "下载失败")
                                }
                            } catch (e: Exception) {
                                downloadError = e.message ?: "未知错误"
                                Log.d("kano_ZTE_LOG", "下载异常：${e.message}")
                            } finally {
                                downloadInProgress = false
                            }
                        }
                    }
                }

                val response = newFixedLengthResponse(
                    Response.Status.OK, "application/json", """{"result":"download_started"}"""
                )
                response.addHeader("Access-Control-Allow-Origin", "*")
                response
            } catch (e: Exception) {
                Log.d("kano_ZTE_LOG", "执行 /install_apk 出错：${e.message}")
                val response = newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR,
                    "application/json",
                    """{"error":"${e.message}"}"""
                )
                response.addHeader("Access-Control-Allow-Origin", "*")
                response
            }
        }

        //下载进度
        if (method == "GET" && uri == "/download_apk_status") {
            val status = when {
                downloadInProgress -> "downloading"
                downloadError != null -> "error"
                downloadResultPath != null -> "done"
                else -> "idle"
            }
            val response = newFixedLengthResponse(
                Response.Status.OK, "application/json", """{
                "status":"$status",
                "percent":$download_percent,
                "error":"${downloadError ?: ""}"
                }""".trimIndent()
            )
            response.addHeader("Access-Control-Allow-Origin", "*")
            return response
        }

        //安装下载好的APK
        if (method == "POST" && uri == "/install_apk") {
            val pipedInput = PipedInputStream()
            val pipedOutput = PipedOutputStream(pipedInput)
            if (downloadResultPath != null) {
                //安装APK
                // 使用协程处理耗时任务
                CoroutineScope(Dispatchers.IO).launch {
                    Log.d("kano_ZTE_LOG", "开始安装apk，位置：$downloadResultPath")
                    val writer = OutputStreamWriter(pipedOutput, Charsets.UTF_8)
                    try {
                        //复制依赖
                        val outFile_adb = KanoUtils.copyFileToFilesDir(context_app, "shell/adb")
                            ?: throw Exception("复制adb 到filesDir失败")

                        outFile_adb.setExecutable(true)

                        //复制APK到SD卡根目录
                        val adb_command_copy =
                            "${outFile_adb.absolutePath} -s localhost shell sh -c 'cp ${downloadResultPath} /sdcard/ufi_tools_latest.apk'"
                        val adb_result_copy = runShellCommand(adb_command_copy, context_app)
                        Log.d("kano_ZTE_LOG", "adb_执行命令：$adb_command_copy")
                        Log.d("kano_ZTE_LOG", "adb_result：$adb_result_copy")

                        //创建sh文件
                        val sh_text = """#!/system/bin/sh
                        pm install -r /sdcard/ufi_tools_latest.apk >> /sdcard/ufi_tools_update.log
                        am start -n com.minikano.f50_sms/.MainActivity  >> /sdcard/ufi_tools_update.log
                        echo "done"""".trimIndent()

                        //复制sh到SD卡根目录
                        val file =
                            ShellKano.createShellScript(context_app, "ufi_tools_update.sh", sh_text)
                        val shAbsolutePath = file.absolutePath
                        Log.d("kano_ZTE_LOG", "Script created at: $shAbsolutePath")
                        val adb_command_copy_sh =
                            "${outFile_adb.absolutePath} -s localhost shell sh -c 'cp ${shAbsolutePath} /sdcard/ufi_tools_update.sh'"
                        val adb_result_copy_sh = runShellCommand(adb_command_copy_sh, context_app)
                        Log.d("kano_ZTE_LOG", "copy_sh执行命令：$adb_command_copy_sh")
                        Log.d("kano_ZTE_LOG", "copy_sh_result：$adb_result_copy_sh")

                        //模拟操作
                        fun click_stage() {
                            //打开工程模式活动
                            var Eng_result: Any? = null
                            // 唤醒屏幕
                            runShellCommand(
                                "${outFile_adb.absolutePath} -s localhost shell input keyevent KEYCODE_WAKEUP",
                                context_app
                            )
                            Thread.sleep(10)
                            // 解锁
                            runShellCommand(
                                "${outFile_adb.absolutePath} -s localhost shell input keyevent 82",
                                context_app
                            )
                            Thread.sleep(10)
                            // 点击一下，防止系统卡住
                            runShellCommand(
                                "${outFile_adb.absolutePath} -s localhost shell input tap 0 0",
                                context_app
                            )
                            Thread.sleep(10)
                            repeat(5) {
                                Eng_result = runShellCommand(
                                    "${outFile_adb.absolutePath} -s localhost shell am start -n com.sprd.engineermode/.EngineerModeActivity",
                                    context_app
                                )
                                Log.d("kano_ZTE_LOG", "工程模式打开结果：$Eng_result")
                            }
                            if (Eng_result == null) {
                                throw Exception("工程模式活动打开失败")
                            }
                            Thread.sleep(400)
                            val res_debug_log_btn = ShellKano.parseUiDumpAndClick(
                                "DEBUG&LOG", outFile_adb.absolutePath, context_app
                            )
                            if (res_debug_log_btn == -1) throw Exception("点击 DEBUG&LOG 失败")
                            if (res_debug_log_btn == 0) {
                                val res = ShellKano.parseUiDumpAndClick(
                                    "Adb shell", outFile_adb.absolutePath, context_app
                                )
                                if (res == -1) throw Exception("点击 Adb Shell 按钮失败")
                            }
                        }

                        fun tryClickStage(maxRetry: Int = 2) {
                            var retry = 0
                            while (retry <= maxRetry) {
                                try {
                                    click_stage()
                                    return // 成功则直接退出
                                } catch (e: Exception) {
                                    Log.w(
                                        "kano_ZTE_LOG",
                                        "click_stage 执行失败，尝试第 ${retry + 1} 次，错误：${e.message}"
                                    )
                                    // 退回操作
                                    repeat(10) {
                                        runShellCommand(
                                            "${outFile_adb.absolutePath} -s localhost shell input keyevent KEYCODE_BACK",
                                            context_app
                                        )
                                    }
                                    Thread.sleep(1000)
                                    retry++
                                }
                            }
                            throw Exception("click_stage 多次重试失败")
                        }

                        tryClickStage()

                        //输入指令，点击发送
                        var jsonResult = """{"result":"success"}"""
                        try {
                            val escapedCommand =
                                "sh /sdcard/ufi_tools_update.sh".replace(
                                    "\"", "\\\""
                                )
                            fillInputAndSend(
                                escapedCommand,
                                outFile_adb.absolutePath,
                                context_app,
                                "",
                                listOf("START", "开始"),
                                needBack = false,
                                useClipBoard = true
                            )
                        } catch (e: Exception) {
                            jsonResult = """{"error":"adb安装apk执行错误：${e.message}"}"""
                        }
                        //清理临时文件
                        writer.write(jsonResult)
                    } catch (e: Exception) {
                        writer.write("""{"error":"adb安装apk执行错误：${e.message}"}""")
                    } finally {
                        writer.flush()
                        pipedOutput.close()
                    }
                }
            }

            val response = newChunkedResponse(
                Response.Status.OK, "application/json", pipedInput
            )
            response.addHeader("Access-Control-Allow-Origin", "*")
            return response
        }

        //ADB初始化状态
        if (method == "GET" && uri == "/adb_alive") {
            val response = newFixedLengthResponse(
                Response.Status.OK,
                "application/json",
                """{"result":"${ADBService.adbIsReady}"}""".trimIndent()
            )
            response.addHeader("Access-Control-Allow-Origin", "*")
            return response
        }

        //执行shell脚本
        if (method == "GET" && uri == "/one_click_shell") {
            // 创建一个 Piped 流来异步返回数据
            val pipedInput = PipedInputStream()
            val pipedOutput = PipedOutputStream(pipedInput)

            // 使用协程处理耗时任务
            CoroutineScope(Dispatchers.IO).launch {
                val writer = OutputStreamWriter(pipedOutput, Charsets.UTF_8)
                try {
                    //复制依赖
                    val outFile_adb = KanoUtils.copyFileToFilesDir(context_app, "shell/adb")
                        ?: throw Exception("复制adb 到filesDir失败")

                    outFile_adb.setExecutable(true)

                    fun click_stage1() {
                        var Eng_result: Any? = null
                        // 唤醒屏幕
                        runShellCommand(
                            "${outFile_adb.absolutePath} -s localhost shell input keyevent KEYCODE_WAKEUP",
                            context_app
                        )
                        Thread.sleep(10)
                        // 解锁
                        runShellCommand(
                            "${outFile_adb.absolutePath} -s localhost shell input keyevent 82",
                            context_app
                        )
                        Thread.sleep(10)
                        // 点击一下，防止系统卡住
                        runShellCommand(
                            "${outFile_adb.absolutePath} -s localhost shell input tap 0 0",
                            context_app
                        )
                        Thread.sleep(10)
                        repeat(10) {
                            Eng_result = runShellCommand(
                                "${outFile_adb.absolutePath} -s localhost shell am start -n com.sprd.engineermode/.EngineerModeActivity",
                                context_app
                            )
                            Log.d("kano_ZTE_LOG", "工程模式打开结果：$Eng_result")
                        }
                        if (Eng_result == null) {
                            throw Exception("工程模式活动打开失败")
                        }
                        Thread.sleep(400)
                        val res_debug_log_btn = ShellKano.parseUiDumpAndClick(
                            "DEBUG&LOG", outFile_adb.absolutePath, context_app
                        )
                        if (res_debug_log_btn == -1) throw Exception("点击 DEBUG&LOG 失败")
                        if (res_debug_log_btn == 0) {
                            val res = ShellKano.parseUiDumpAndClick(
                                "Adb shell", outFile_adb.absolutePath, context_app
                            )
                            if (res == -1) throw Exception("点击 Adb Shell 按钮失败")
                        }
                    }

                    fun tryClickStage1(maxRetry: Int = 2) {
                        var retry = 0
                        while (retry <= maxRetry) {
                            try {
                                click_stage1()
                                return // 成功则直接退出
                            } catch (e: Exception) {
                                Log.w(
                                    "kano_ZTE_LOG",
                                    "click_stage1 执行失败，尝试第 ${retry + 1} 次，错误：${e.message}"
                                )
                                // 退回操作
                                repeat(10) {
                                    runShellCommand(
                                        "${outFile_adb.absolutePath} -s localhost shell input keyevent KEYCODE_BACK",
                                        context_app
                                    )
                                }
                                Thread.sleep(1000)
                                retry++
                            }
                        }
                        throw Exception("click_stage1 多次重试失败")
                    }

                    tryClickStage1()

                    //输入指令，点击发送
                    var jsonResult = """{"result":"执行成功"}"""
                    try {
                        val escapedCommand =
                            "sh /sdcard/one_click_shell.sh".replace(
                                "\"", "\\\""
                            )
                        fillInputAndSend(
                            escapedCommand,
                            outFile_adb.absolutePath,
                            context_app,
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

            val response = newChunkedResponse(
                Response.Status.OK, "application/json", pipedInput
            )
            response.addHeader("Access-Control-Allow-Origin", "*")
            return response
        }

        //获取短信转发方式
        if (method == "GET" && uri == "/sms_forward_method"){
            val sharedPrefs =
                context_app.getSharedPreferences("kano_ZTE_store", Context.MODE_PRIVATE)
            val sms_forward_method = sharedPrefs.getString("kano_sms_forward_method", "") ?: ""
            val json = """
                {
                    "sms_forward_method": "${sms_forward_method.replace("\"","\\\"")}"
                }
            """.trimIndent()

            return newFixedLengthResponse(
                Response.Status.OK,
                "application/json",
                json
            )

        }

        //短信转发参数存入-邮件
        if (method == "POST" && uri == "/sms_forward_mail") {
            return try {
                val map = HashMap<String, String>()
                session?.parseBody(map)
                val body = map["postData"] ?: throw Exception("postData is null")
                val json = JSONObject(body)

                val smtpHost = json.optString("smtp_host", "").trim()
                val smtpPort = json.optString("smtp_port", "465").trim()
                val smtpTo = json.optString("smtp_to", "").trim()
                val smtpUsername = json.optString("smtp_username", "").trim()
                val smtpPassword = json.optString("smtp_password", "").trim()

                if (smtpTo.isEmpty() || smtpHost.isEmpty() || smtpUsername.isEmpty() || smtpPassword.isEmpty()) {
                    throw Exception("缺少必要参数")
                }

                // 存储到 SharedPreferences
                val sharedPrefs =
                    context_app.getSharedPreferences("kano_ZTE_store", Context.MODE_PRIVATE)
                sharedPrefs.edit().apply {
                    //切换到smtp的配置
                    putString("kano_sms_forward_method", "SMTP")
                    putString("kano_smtp_host", smtpHost)
                    putString("kano_smtp_port", smtpPort)
                    putString("kano_smtp_to", smtpTo)
                    putString("kano_smtp_username", smtpUsername)
                    putString("kano_smtp_password", smtpPassword)
                    apply()
                }

                Log.d("kano_ZTE_LOG", "SMTP配置已保存：$smtpHost:$smtpPort [$smtpUsername]")

                //发送测试消息
                val test_msg = SmsInfo("1010721", "UFI-TOOLS TEST消息", 0)
                SmsPoll.forwardByEmail(test_msg,context_app)

                val response = newFixedLengthResponse(
                    Response.Status.OK,
                    "application/json",
                    """{"result":"success"}""".trimIndent()
                )
                response.addHeader("Access-Control-Allow-Origin", "*")
                response
            } catch (e: Exception) {
                Log.d("kano_ZTE_LOG", "SMTP配置出错： ${e.message}")
                val response = newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR,
                    "application/json",
                    """{"error":"SMTP配置出错"}"""
                )
                response.addHeader("Access-Control-Allow-Origin", "*")
                response
            }
        }

        //读取smtp配置
        if (method == "GET" && uri == "/sms_forward_mail") {
            val sharedPrefs =
                context_app.getSharedPreferences("kano_ZTE_store", Context.MODE_PRIVATE)

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

            return newFixedLengthResponse(
                Response.Status.OK,
                "application/json",
                json
            )
        }

        //短信转发参数存入-curl
        if (method == "POST" && uri == "/sms_forward_curl") {
            return try {
                val files = HashMap<String, String>()
                session?.parseBody(files) ?: throw Exception("Body is null")

                val bodyBytes = files["postData"]?.toByteArray(Charsets.UTF_8)
                val body = bodyBytes?.toString(Charsets.UTF_8) ?: throw Exception("postData is null")

                val json = JSONObject(body)

                val originalCurl = json.getString("curl_text")

                Log.d("kano_ZTE_LOG", "是否找到{{sms}}：${originalCurl.contains("{{sms}}")}")
                Log.d("kano_ZTE_LOG", "curl配置：${originalCurl}")

                if(!originalCurl.contains("{{sms-body}}")) throw Exception("没有找到“{{sms-body}}”占位符")
                if(!originalCurl.contains("{{sms-time}}")) throw Exception("没有找到“{{sms-time}}”占位符")
                if(!originalCurl.contains("{{sms-from}}")) throw Exception("没有找到“{{sms-from}}”占位符")

                // 存储到 SharedPreferences
                val sharedPrefs =
                    context_app.getSharedPreferences("kano_ZTE_store", Context.MODE_PRIVATE)
                sharedPrefs.edit().apply {
                    //切换到curl的配置
                    putString("kano_sms_forward_method", "CURL")
                    putString("kano_sms_curl", originalCurl)
                    apply()
                }

                //发送测试消息
                val test_msg = SmsInfo("18888888888", "UFI-TOOLS TEST消息", System.currentTimeMillis())
                SmsPoll.forwardSmsByCurl(test_msg,context_app)

                json.put("curl_text", originalCurl)

                val response = newFixedLengthResponse(
                    Response.Status.OK,
                    "application/json",
                    """{"result":"success"}"""
                )
                response.addHeader("Access-Control-Allow-Origin", "*")
                response

            } catch (e: Exception) {
                Log.d("kano_ZTE_LOG", "curl配置出错：${e.message}")
                val response = newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR,
                    "application/json",
                    """{"error":"curl配置出错：${e.message}"}"""
                )
                response.addHeader("Access-Control-Allow-Origin", "*")
                response
            }
        }

        //读取短信转发curl配置
        if (method == "GET" && uri == "/sms_forward_curl") {
            val sharedPrefs =
                context_app.getSharedPreferences("kano_ZTE_store", Context.MODE_PRIVATE)

            val curl_text = sharedPrefs.getString("kano_sms_curl", "") ?: ""

            val json = JSONObject(mapOf("curl_text" to curl_text)).toString()

            return newFixedLengthResponse(
                Response.Status.OK,
                "application/json",
                json
            )
        }

        //短信转发总开关
        if (method == "POST" && uri == "/sms_forward_enabled"){
            return try {
                // 解析 query 参数
                val rawParams = session?.parameters ?: throw Exception("缺少 query 参数")
                val enable_arr =
                    rawParams["enable"] ?: throw Exception("qeury 缺少 enable 参数")
                val enable = enable_arr[0]
                Log.d("kano_ZTE_LOG", "短信转发 enable 传入参数：${enable}")
                val sharedPrefs =
                    context_app.getSharedPreferences("kano_ZTE_store", Context.MODE_PRIVATE)
                sharedPrefs.edit().apply {
                    putString("kano_sms_forward_enabled", enable)
                    apply()
                }
                val response = newFixedLengthResponse(
                    Response.Status.OK,
                    "application/json",
                    """{"result":"success"}""".trimIndent()
                )
                response.addHeader("Access-Control-Allow-Origin", "*")
                response
            } catch (e: Exception) {
                Log.d("kano_ZTE_LOG", "请求出错： ${e.message}")
                val response = newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR, "application/json", """{"error":"请求出错"}"""
                )
                response.addHeader("Access-Control-Allow-Origin", "*")
                response
            }
        }

        //获取短信转发状态
        if (method == "GET" && uri == "/sms_forward_enabled"){
            return try {
                // 解析 query 参数
                val sharedPrefs =
                    context_app.getSharedPreferences("kano_ZTE_store", Context.MODE_PRIVATE)
                val str = sharedPrefs.getString("kano_sms_forward_enabled","0")
                val response = newFixedLengthResponse(
                    Response.Status.OK,
                    "application/json",
                    """{"enabled":"$str"}""".trimIndent()
                )
                response.addHeader("Access-Control-Allow-Origin", "*")
                response
            } catch (e: Exception) {
                Log.d("kano_ZTE_LOG", "请求出错： ${e.message}")
                val response = newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR, "application/json", """{"error":"请求出错"}"""
                )
                response.addHeader("Access-Control-Allow-Origin", "*")
                response
            }
        }

        //读取、保存自定义头部
        if(method == "GET" && uri == "/get_custom_head"){
            return try {
                val sharedPref = context_app.getSharedPreferences("kano_ZTE_store", Context.MODE_PRIVATE)
                val text = sharedPref.getString("kano_custom_head","")
                val json = JSONObject(mapOf("text" to text)).toString()
                return newFixedLengthResponse(
                    Response.Status.OK,
                    "application/json",
                    json
                )
            }
            catch (e: Exception) {
                Log.d("kano_ZTE_LOG", "SMTP配置出错： ${e.message}")
                val response = newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR,
                    "application/json",
                    """{"error":"SMTP配置出错"}"""
                )
                response.addHeader("Access-Control-Allow-Origin", "*")
                response
            }
        }

        //读取、保存自定义头部
        if(method == "POST" && uri == "/set_custom_head"){
            return try {
                val sharedPref = context_app.getSharedPreferences("kano_ZTE_store", Context.MODE_PRIVATE)
                val map = HashMap<String, String>()
                session?.parseBody(map)
                val body = map["postData"] ?: throw Exception("postData is null")
                val json = JSONObject(body)

                val text = json.optString("text", "").trim()

                sharedPref.edit().apply {
                    //切换到curl的配置
                    putString("kano_custom_head", text)
                    apply()
                }

                val response = newFixedLengthResponse(
                    Response.Status.OK,
                    "application/json",
                    """{"result":"success"}""".trimIndent()
                )
                response.addHeader("Access-Control-Allow-Origin", "*")
                return response
            }
            catch (e: Exception) {
                Log.d("kano_ZTE_LOG", "配置出错： ${e.message}")
                val response = newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR,
                    "application/json",
                    """{"error":"配置出错"}"""
                )
                response.addHeader("Access-Control-Allow-Origin", "*")
                response
            }
        }


        // 获取查询参数
        val queryString = session?.queryParameterString
        val fullUrl = if (queryString.isNullOrEmpty()) {
            "$targetServer$uri"
        } else {
            "$targetServer$uri?$queryString"
        }

        //其余的请求全都丢给zteWebProject
        // 处理 OPTIONS 请求
        if (method == "OPTIONS") {
            val response = newFixedLengthResponse(Response.Status.OK, "text/plain", "")
            response.addHeader("Access-Control-Allow-Origin", "*")
            response.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
            response.addHeader("Access-Control-Allow-Headers", "Content-Type, X-Requested-With")
            return response
        }

        Log.d("kano_ZTE_LOG", fullUrl)

        // 构造目标 URL
        return try {
            val url = URL(fullUrl)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = method
            // 复制请求头
            session?.headers?.forEach { (key, value) ->
                if (!key.equals("host", ignoreCase = true)) { // 跳过 Host 头，避免影响目标服务器
                    conn.setRequestProperty(key, value)
                }
            }
            conn.setRequestProperty("Referer", targetServer) // 添加 Referer 头

            // 处理 POST 请求体
            if (method == "POST" || method == "PUT") {
                val contentLength = session?.headers?.get("content-length")?.toIntOrNull() ?: 0
                if (contentLength > 0) {
                    // 手动读取请求体
                    val inputStream = session?.inputStream
                    val requestBody = ByteArray(contentLength)
                    if (inputStream != null) {
                        inputStream.read(requestBody)
                    }

                    // 将请求体转换为字符串
                    val requestBodyStr = String(requestBody, Charsets.UTF_8)
                    Log.d("kano_ZTE_LOG", "Request Length: ${requestBodyStr.length}")
                    Log.d("kano_ZTE_LOG", "Request Body: $requestBodyStr")

                    // 解析 URL 编码格式的请求体
                    val params = KanoUtils.parseUrlEncoded(requestBodyStr)
                    Log.d("kano_ZTE_LOG", "Parsed Body: $params")

                    // 发送请求体到目标服务器
                    conn.doOutput = true
                    conn.setRequestProperty(
                        "Content-Length", requestBodyStr.toByteArray().size.toString()
                    )
                    conn.outputStream.use { it.write(requestBodyStr.toByteArray()) }
                }
            }

            conn.connect()

            val responseCode = conn.responseCode
            val responseStream: InputStream = if (responseCode in 200..299) {
                conn.inputStream
            } else {
                conn.errorStream
            }

            val response = newFixedLengthResponse(
                Response.Status.lookup(responseCode),
                conn.contentType ?: "text/plain",
                responseStream,
                conn.contentLength.toLong()
            )

            // 将目标服务器的所有响应头转发给客户端
            conn.headerFields.forEach { (key, value) ->
                if (key != null && value != null && key.equals("Set-Cookie", ignoreCase = true)) {
                    value.forEach { cookie ->
                        response.addHeader("kano-cookie", cookie)  // 转发 Set-Cookie
                    }
                }
            }

            response.addHeader("Access-Control-Allow-Origin", "*")
            response.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
            response.addHeader("Access-Control-Allow-Headers", "Content-Type, X-Requested-With")

            response
        } catch (e: Exception) {
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR, "text/plain", "Proxy error: ${e.message}"
            )
        }
    }

    // 静态文件处理逻辑
    // 添加一个变量保存 context 的 assets
    private val assetManager = context.assets
    private val context_app = context

    private fun unauthorized(): Response {
        return newFixedLengthResponse(
            Response.Status.UNAUTHORIZED,
            MIME_PLAINTEXT,
            "401 Unauthorized"
        )
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

    private fun serveStaticFile(uri: String): Response {
        val path = if (uri == "/") "index.html" else uri.removePrefix("/")
        val assetPath = path

        return try {
            val inputStream = assetManager.open(assetPath)
            val mime = getMimeTypeForFile(path)
            newChunkedResponse(Response.Status.OK, mime, inputStream)
        } catch (e: Exception) {
            newFixedLengthResponse(
                Response.Status.NOT_FOUND, "text/plain", "404 Not Found: ${e.message}"
            )
        }
    }

    private var cachedTotal = 0L
    private var lastUpdate = 0L

    fun getCachedTodayUsage(context: Context): Long {
        val now = System.currentTimeMillis()
        if (now - lastUpdate > 10_000) { // 每 10 秒更新一次
            cachedTotal = KanoUtils.getTodayDataUsage(context)
            lastUpdate = now
        }
        return cachedTotal
    }

    data class MyStorageInfo(
        val path: String, val totalBytes: Long, val availableBytes: Long
    )

}