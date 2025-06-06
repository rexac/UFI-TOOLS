package com.minikano.f50_sms.modules.smsForward

import android.content.Context
import com.minikano.f50_sms.utils.KanoLog
import com.minikano.f50_sms.utils.SmsInfo
import com.minikano.f50_sms.utils.SmsPoll
import com.minikano.f50_sms.modules.BASE_TAG
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import org.json.JSONObject

fun Route.smsModule(context: Context) {
    val TAG = "[$BASE_TAG]_smsModule"

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

            KanoLog.d(TAG, "SMTP配置已保存：$smtpHost:$smtpPort [$smtpUsername]")

            val test_msg = SmsInfo("1010721", "UFI-TOOLS TEST消息", 0)
            SmsPoll.forwardByEmail(test_msg, context)

            call.response.headers.append("Access-Control-Allow-Origin", "*")
            call.respondText(
                """{"result":"success"}""",
                ContentType.Application.Json,
                HttpStatusCode.OK
            )

        } catch (e: Exception) {
            KanoLog.d(TAG, "SMTP配置出错： ${e.message}")
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

            KanoLog.d(TAG, "是否找到{{sms}}：${originalCurl.contains("{{sms}}")}")
            KanoLog.d(TAG, "curl配置：$originalCurl")

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
            KanoLog.d(TAG, "curl配置出错：${e.message}")
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
            KanoLog.d(TAG, "短信转发 enable 传入参数：$enable")

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
            KanoLog.d(TAG, "请求出错： ${e.message}")
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
            KanoLog.d(TAG, "请求出错： ${e.message}")
            call.response.headers.append("Access-Control-Allow-Origin", "*")
            call.respondText(
                """{"error":"请求出错"}""",
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError
            )
        }
    }

}