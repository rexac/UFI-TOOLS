package com.minikano.f50_sms.modules.plugins

import android.content.Context
import androidx.core.content.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.minikano.f50_sms.configs.AppMeta
import com.minikano.f50_sms.utils.KanoLog
import com.minikano.f50_sms.modules.BASE_TAG
import com.minikano.f50_sms.modules.auth.authenticatedRoute
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
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

val Context.dataStore by preferencesDataStore(name = "kano_ZTE_store")
val KANO_CUSTOM_HEAD = stringPreferencesKey("kano_custom_head")

sealed class DataStoreResult<out T> {
    data class Success<T>(val data: T? = null) : DataStoreResult<T>()
    data class Error(val exception: Throwable) : DataStoreResult<Nothing>()
}

suspend fun saveCustomHeadToDataStore(
    context: Context,
    text: String
): DataStoreResult<Unit> {
    return try {
        context.dataStore.edit { preferences ->
            preferences[KANO_CUSTOM_HEAD] = text
        }
        DataStoreResult.Success(Unit)
    } catch (e: Exception) {
        DataStoreResult.Error(e)
    }
}

fun saveCustomHeadBlocking(
    context: Context,
    text: String
): DataStoreResult<Unit> {
    return try {
        runBlocking(Dispatchers.IO) {
            context.dataStore.edit { preferences ->
                preferences[KANO_CUSTOM_HEAD] = text
            }
        }
        DataStoreResult.Success(Unit)
    } catch (e: Exception) {
        DataStoreResult.Error(e)
    }
}

suspend fun getCustomHeadFromDataStore(context: Context): String? {
    return try {
        context.dataStore.data
            .map { it[KANO_CUSTOM_HEAD] }
            .first()
    } catch (e: Exception) {
        null
    }
}

fun Route.pluginsModule(context: Context) {
    val TAG = "[$BASE_TAG]_pluginsModule"

    authenticatedRoute(context){
        //保存自定义头部
        post("/api/set_custom_head") {
            try {
                val body = call.receiveText()

                val json = JSONObject(body)
                val text = json.optString("text", "").trim()

                val result = saveCustomHeadBlocking(context,text)
                if (result is DataStoreResult.Error) {
                   throw Exception(result.exception)
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

        //从插件市场获取插件
        get("/api/plugins_store"){
            try {
                val download_url = "${AppMeta.GLOBAL_SERVER_URL}/d/UFI-TOOLS-UPDATE/plugins/ufi-tools-plugins"
                val url = "${AppMeta.GLOBAL_SERVER_URL}/api/fs/list"
                val path = "/UFI-TOOLS-UPDATE/plugins/ufi-tools-plugins"

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

                val alistResponse = KanoRequest.postJson(url, requestBody)

                val alistBody = alistResponse.body?.string()

                // 拼装 JSON 响应
                val resultJson = """{
                    |"download_url":"$download_url",
                    |"res":$alistBody
                    |}""".trimMargin()

                call.response.headers.append("Access-Control-Allow-Origin", "*")
                if (resultJson != null) {
                    call.respondText(resultJson, ContentType.Application.Json, HttpStatusCode.OK)
                }else{
                    throw Exception("响应为空")
                }
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
    }

    //读取自定义头部
    get("/api/get_custom_head") {
        try {
            val sharedPref =
                context.getSharedPreferences("kano_ZTE_store", Context.MODE_PRIVATE)

            //Deprecated
            val oldText = sharedPref.getString("kano_custom_head", "") ?: ""

            if(oldText.isNotEmpty()){
                val result = saveCustomHeadBlocking(context,oldText)
                if (result is DataStoreResult.Error) {
                    KanoLog.e(TAG, "迁移kano_custom_head 到 DataStore出错： ${result.exception.message}")
                }
                if (result is DataStoreResult.Success) {
                    sharedPref.edit(commit = true){
                        remove("kano_custom_head")
                    }
                }
            }

            val textFromDataStore = getCustomHeadFromDataStore(context) ?: ""

            val json = JSONObject(mapOf("text" to textFromDataStore)).toString()

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