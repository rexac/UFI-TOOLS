package com.minikano.f50_sms.modules.config

import android.content.Context
import com.minikano.f50_sms.modules.BASE_TAG
import com.minikano.f50_sms.utils.KanoLog
import com.minikano.f50_sms.utils.KanoRequest
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import org.json.JSONObject
import androidx.core.content.edit
import com.minikano.f50_sms.configs.AppMeta

fun Route.configModule(context: Context) {
    val TAG = "[$BASE_TAG]_configModule"
    val PREFS_NAME = "kano_ZTE_store"
    val PREF_LOGIN_TOKEN = "login_token"

    //设置口令
    post("/api/set_token") {
        try {
            val body = call.receiveText()
            val json = JSONObject(body)

            val token = json.optString("token", "").trim()
            if (token.isEmpty() || token.isBlank()) {
                throw IllegalArgumentException("请提供 token")
            }

            KanoLog.d(TAG, "接收到 token=$token")

            val perf = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            perf.edit {
                putString(PREF_LOGIN_TOKEN, token)
            }

            call.response.headers.append("Access-Control-Allow-Origin", "*")
            call.respondText(
                """{"result":"success"}""",
                ContentType.Application.Json,
                HttpStatusCode.OK
            )
        } catch (e: Exception) {
            KanoLog.d(TAG, "设置口令出错：${e.message}")
            call.response.headers.append("Access-Control-Allow-Origin", "*")
            call.respondText(
                """{"error":"${e.message ?: "未知错误"}"}""",
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError
            )
        }
    }

    //获取全局服务器地址
    get("/api/get_res_server") {
        try {
            // 拼装 JSON 响应
            val resultJson = """{
                "res_server": "${AppMeta.GLOBAL_SERVER_URL}"
            }""".trimIndent()

            call.response.headers.append("Access-Control-Allow-Origin", "*")
            call.respondText(resultJson, ContentType.Application.Json, HttpStatusCode.OK)
        } catch (e: Exception) {
            KanoLog.d(TAG, "请求出错：${e.message}")
            call.response.headers.append("Access-Control-Allow-Origin", "*")
            call.respondText(
                """{"error":"请求出错"}""",
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError
            )
        }
    }

    //设置资源服务器
    post("/api/set_res_server") {
        try {
            val body = call.receiveText()
            val json = JSONObject(body)

            val resServerUrl = json.optString("res_server", "").trim()
            if (resServerUrl.isEmpty() || resServerUrl.isBlank()) {
                throw IllegalArgumentException("请提供 res_server")
            }

            KanoLog.d(TAG, "接收到 res_server=$resServerUrl")

            AppMeta.setGlobalServerUrl(context, resServerUrl)

            call.response.headers.append("Access-Control-Allow-Origin", "*")
            call.respondText(
                """{"result":"success"}""",
                ContentType.Application.Json,
                HttpStatusCode.OK
            )
        } catch (e: Exception) {
            KanoLog.d(TAG, "设置resServerUrl出错：${e.message}")
            call.response.headers.append("Access-Control-Allow-Origin", "*")
            call.respondText(
                """{"error":"${e.message ?: "未知错误"}"}""",
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError
            )
        }
    }

    //获取日志开关状态
    get("/api/get_log_status") {
        try {
            // 拼装 JSON 响应
            val resultJson = """{
                "debug_log_enabled": "${AppMeta.isEnableLog}"
            }""".trimIndent()

            call.response.headers.append("Access-Control-Allow-Origin", "*")
            call.respondText(resultJson, ContentType.Application.Json, HttpStatusCode.OK)
        } catch (e: Exception) {
            KanoLog.d(TAG, "请求出错：${e.message}")
            call.response.headers.append("Access-Control-Allow-Origin", "*")
            call.respondText(
                """{"error":"请求出错"}""",
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError
            )
        }
    }

    //设置日志开关状态
    post("/api/set_log_status") {
        try {
            val body = call.receiveText()
            val json = JSONObject(body)

            val debugEnabled = json.optBoolean("debug_log_enabled", false)

            KanoLog.d(TAG, "接收到 debug_log_enabled=$debugEnabled")

            AppMeta.setIsEnableLog(context, debugEnabled)

            call.response.headers.append("Access-Control-Allow-Origin", "*")
            call.respondText(
                """{"result":"success"}""",
                ContentType.Application.Json,
                HttpStatusCode.OK
            )
        } catch (e: Exception) {
            KanoLog.d(TAG, "设置debug_log_enabled出错：${e.message}")
            call.response.headers.append("Access-Control-Allow-Origin", "*")
            call.respondText(
                """{"error":"${e.message ?: "未知错误"}"}""",
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError
            )
        }
    }
}