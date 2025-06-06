package com.minikano.f50_sms.modules.plugins

import android.content.Context
import com.minikano.f50_sms.utils.KanoLog
import com.minikano.f50_sms.modules.BASE_TAG
import com.minikano.f50_sms.modules.auth.authenticatedRoute
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import org.json.JSONObject

fun Route.pluginsModule(context: Context) {
    val TAG = "[$BASE_TAG]_pluginsModule"

    authenticatedRoute(context){
      //保存自定义头部
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
            KanoLog.d(TAG, "配置出错： ${e.message}")
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
            KanoLog.d(TAG, "读取自定义头部出错： ${e.message}")
            call.response.headers.append("Access-Control-Allow-Origin", "*")
            call.respondText(
                """{"error":"读取自定义头部出错"}""",
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError
            )
        }
    }
}