package com.minikano.f50_sms.modules.advanced

import android.content.Context
import com.minikano.f50_sms.configs.SMBConfig
import com.minikano.f50_sms.modules.BASE_TAG
import com.minikano.f50_sms.utils.KanoLog
import com.minikano.f50_sms.utils.KanoUtils
import com.minikano.f50_sms.utils.KanoUtils.Companion.sendShellCmd
import com.minikano.f50_sms.utils.RootShell
import com.minikano.f50_sms.utils.ShellKano
import com.minikano.f50_sms.utils.SmbThrottledRunner
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondOutputStream
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONObject
import java.io.File
import java.io.OutputStreamWriter
import java.io.PipedInputStream
import java.io.PipedOutputStream

fun Route.advancedToolsModule(context: Context, targetServerIP: String) {
    val TAG = "[$BASE_TAG]_advanceToolsModule"

    //更改samba分享地址为根目录
    get("/api/smbPath") {
        try {
            val enabled = call.request.queryParameters["enable"]
                ?: throw Exception("缺少 query 参数 enable")

            KanoLog.d(TAG, "enable 传入参数：$enabled")

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
                try{
                    val cmd =
                        "cat $smbPath > /data/samba/etc/smb.conf"
                    val result = sendShellCmd(cmd,3)
                        ?: throw Exception("修改 smb.conf 失败")
                    jsonResult = """{"result":"执行成功，等待1-2分钟即可生效！"}"""
                } catch (e:Exception){
                    val cmd =
                        "${outFileAdb.absolutePath} -s localhost shell cat $smbPath > /data/samba/etc/smb.conf"
                    val result = ShellKano.runShellCommand(cmd, context = context)
                        ?: throw Exception("修改 smb.conf 失败,请开启ADB后再试")
                    jsonResult = """{"result":"执行成功，等待1-2分钟即可生效！"}"""
                }
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
                    throw Exception("执行命令失败，没有找到 socat 创建的 sock (高级功能是否开启？)")
                }

                val result = RootShell.sendCommandToSocket(script, socketPath.absolutePath)
                    ?: throw Exception("删除 smb.conf 失败")
                KanoLog.d(TAG, "sendCommandToSocket Output:\n$result")
            }

            KanoLog.d(TAG, "刷新 SMB 中...")
            SmbThrottledRunner.runOnceInThread(context)

            call.respondText(jsonResult, ContentType.Application.Json)

        } catch (e: Exception) {
            KanoLog.d(TAG, "smbPath 执行出错：${e.message}")
            call.respondText(
                """{"error":"执行错误：${e.message}"}""",
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError
            )
        }
    }

    //禁用系统更新
    get("/api/disable_fota") {
        try {
            val res = KanoUtils.disableFota(context)

            if(!res) throw Exception("禁用系统更新失败")

            val jsonResult = """{"result":"执行成功,如需强力禁用请使用高级功能！"}"""

            call.respondText(jsonResult, ContentType.Application.Json)

        } catch (e: Exception) {
            KanoLog.d(TAG, "禁用系统更新出错：${e.message}")
            call.respondText(
                """{"error":"禁用系统更新出错：${e.message}"}""",
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError
            )
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

            KanoLog.d(TAG, "TTYD获取ip+port信息： $host:$port 返回code:$code")

            call.response.headers.append("Access-Control-Allow-Origin", "*")
            call.respondText(
                """{"code":"$code","ip":"$host:$port"}""",
                ContentType.Application.Json,
                HttpStatusCode.OK
            )
        } catch (e: Exception) {
            KanoLog.d(TAG, "获取TTYD信息出错： ${e.message}")
            call.response.headers.append("Access-Control-Allow-Origin", "*")
            call.respondText(
                """{"error":"获取TTYD信息出错:${e.message}"}""",
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError
            )
        }
    }

    //用户shell
    post("/api/user_shell") {
        try {
            val body = call.receiveText()

            val json = try {
                JSONObject(body)
            } catch (e: Exception) {
                throw Exception("解析请求的json出错")
            }

            val text = json.optString("command", "").trim()

            KanoLog.d(TAG, "获取到的command： ${text}")

            if (text.isNotEmpty()) {

                val result =
                    sendShellCmd(text)
                        ?: throw Exception("请检查命令输入格式")

                KanoLog.d(TAG, "执行结果： ${result}")

                val parsedResult = Json.encodeToString(result)

                call.response.headers.append("Access-Control-Allow-Origin", "*")
                call.respondText(
                    """{"result":$parsedResult}""",
                    ContentType.Application.Json,
                    HttpStatusCode.OK
                )

            } else {
                throw Exception("命令不能为空")
            }

        } catch (e: Exception) {
            KanoLog.d(TAG, "shell执行出错： ${e.message}")
            call.response.headers.append("Access-Control-Allow-Origin", "*")
            call.respondText(
                """{"error":"shell执行出错: ${e.message}"}""",
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError
            )
        }
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
                        "${outFile_adb.absolutePath} -s localhost shell settings put system screen_off_timeout 2147483647",
                        context
                    )
                    Thread.sleep(10)
                    ShellKano.runShellCommand(
                        "${outFile_adb.absolutePath} -s localhost shell input keyevent KEYCODE_WAKEUP",
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
                        KanoLog.d(TAG, "工程模式打开结果：$Eng_result")
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
                                TAG,
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

    //rootShell执行
    post("/api/root_shell") {
        try {
            val body = call.receiveText()

            val json = try {
                JSONObject(body)
            } catch (e: Exception) {
                throw Exception("解析请求的json出错")
            }

            val text = json.optString("command", "").trim()

            KanoLog.d(TAG, "获取到的command： ${text}")

            if (text.isNotEmpty()) {

                val socketPath = File(context.filesDir, "kano_root_shell.sock")
                if (!socketPath.exists()) {
                    throw Exception("执行命令失败，没有找到 socat 创建的 sock (高级功能是否开启？)")
                }

                val result =
                    RootShell.sendCommandToSocket(
                        text,
                        socketPath.absolutePath
                    )
                        ?: throw Exception("请检查命令输入格式")

                KanoLog.d(TAG, "执行结果： ${result}")

                val parsedResult = Json.encodeToString(result)

                call.response.headers.append("Access-Control-Allow-Origin", "*")
                call.respondText(
                    """{"result":$parsedResult}""",
                    ContentType.Application.Json,
                    HttpStatusCode.OK
                )

            } else {
                throw Exception("命令不能为空")
            }

        } catch (e: Exception) {
            KanoLog.d(TAG, "shell执行出错： ${e.message}")
            call.response.headers.append("Access-Control-Allow-Origin", "*")
            call.respondText(
                """{"error":"shell执行出错: ${e.message}"}""",
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError
            )
        }
    }
}