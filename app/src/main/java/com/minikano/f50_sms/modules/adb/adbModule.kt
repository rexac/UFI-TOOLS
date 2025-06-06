package com.minikano.f50_sms.modules.adb

import android.content.Context
import com.minikano.f50_sms.ADBService
import com.minikano.f50_sms.utils.KanoLog
import com.minikano.f50_sms.modules.BASE_TAG
import com.minikano.f50_sms.modules.PREFS_NAME
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import org.json.JSONObject

//静态资源
fun Route.adbModule(context: Context) {
    val TAG = "[$BASE_TAG]_adbModule"

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
            KanoLog.d(TAG, "获取网络adb信息出错： ${e.message}")

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
                TAG,
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

            KanoLog.d(TAG, "ADMIN_PWD:${sharedPrefs.getString("ADMIN_PWD", "")}")

            // 响应
            call.response.headers.append("Access-Control-Allow-Origin", "*")
            call.respondText(
                """{"result":"success","enabled":"$enabled"}""",
                ContentType.Application.Json
            )
        } catch (e: Exception) {
            KanoLog.d(TAG, "解析ADB_WIFI POST 请求出错：${e.message}")
            call.response.headers.append("Access-Control-Allow-Origin", "*")
            call.respondText(
                """{"error":"参数解析失败"}""",
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError
            )
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

}