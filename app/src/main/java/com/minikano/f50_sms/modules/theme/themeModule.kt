package com.minikano.f50_sms.modules.theme

import android.content.Context
import androidx.core.content.edit
import com.minikano.f50_sms.modules.BASE_TAG
import com.minikano.f50_sms.modules.auth.authenticatedRoute
import com.minikano.f50_sms.utils.KanoLog
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.http.content.streamProvider
import io.ktor.server.application.call
import io.ktor.server.http.content.staticFiles
import io.ktor.server.request.receiveMultipart
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONObject
import java.io.File
import java.util.UUID

@Serializable
data class ThemeConfig(
    val backgroundEnabled: String = "false",
    val backgroundUrl: String = "",
    val textColor: String = "rgba(255, 255, 255, 1)",
    val textColorPer: String = "100",
    val themeColor: String = "201",
    val colorPer: String = "67",
    val saturationPer: String = "100",
    val brightPer: String = "21",
    val opacityPer: String = "21",
    val blurSwitch: String = "true",
    val overlaySwitch: String = "true"
)

val jsonFull = Json {
    encodeDefaults = true
    prettyPrint = false
    ignoreUnknownKeys = true
}

fun Route.themeModule(context: Context) {
    val TAG = "[$BASE_TAG]_themeModule"

    staticFiles("/api/uploads", File(context.filesDir, "uploads"))

    authenticatedRoute(context) {
        //上传图片
        post("/api/upload_img") {
            try {
                val multipart = call.receiveMultipart()
                var fileName: String? = null

                multipart.forEachPart { part ->
                    when (part) {
                        is PartData.FileItem -> {
                            val originalFileName = part.originalFileName as String
                            val ext = originalFileName.substringAfterLast('.', "jpg")  // 没有后缀默认 jpg
                            fileName = "${UUID.randomUUID()}.$ext"
                            val fileBytes = part.streamProvider().readBytes()
                            val uploadDir = File(context.filesDir, "uploads")
                            if (!uploadDir.exists()) uploadDir.mkdirs()
                            File(uploadDir, fileName!!).writeBytes(fileBytes)
                        }

                        else -> {}
                    }
                    part.dispose()
                }

                if (fileName != null) {
                    call.response.headers.append("Access-Control-Allow-Origin", "*")
                    val fileUrl = "/uploads/$fileName"

                    call.respondText(
                        """{"url":"$fileUrl"}""",
                        ContentType.Application.Json,
                        HttpStatusCode.OK
                    )
                } else {
                    throw Exception("图片上传失败")
                }

            } catch (e: Exception) {
                KanoLog.d(TAG, "上传图片出错： ${e.message}")
                call.response.headers.append("Access-Control-Allow-Origin", "*")
                call.respondText(
                    """{"error":"上传图片出错: ${e.message}"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.InternalServerError
                )
            }
        }

        //删除图片
        post("/api/delete_img") {
            try {
                val body = call.receiveText()
                val json = JSONObject(body)

                val fileName = json.optString("file_name")
                val uploadDir = File(context.filesDir, "uploads/$fileName")

                if (uploadDir.exists()) {
                    uploadDir.delete()
                }

                call.response.headers.append("Access-Control-Allow-Origin", "*")
                call.respondText(
                    """{"result":"success"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.OK
                )

            } catch (e: Exception) {
                KanoLog.d(TAG, "删除出错： ${e.message}")
                call.response.headers.append("Access-Control-Allow-Origin", "*")
                call.respondText(
                    """{"error":"删除出错: ${e.message}"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.InternalServerError
                )
            }
        }

        //保存主题
        post("/api/set_theme") {
            try {
                val body = call.receiveText()
                val json = JSONObject(body)

                val config = ThemeConfig(
                    backgroundEnabled = json.optString("backgroundEnabled", "false").trim(),
                    backgroundUrl = json.optString("backgroundUrl", "").trim(),
                    textColor = json.optString("textColor", "rgba(255, 255, 255, 1)").trim(),
                    textColorPer = json.optString("textColorPer", "100").trim(),
                    themeColor = json.optString("themeColor", "201").trim(),
                    colorPer = json.optString("colorPer", "67").trim(),
                    saturationPer = json.optString("saturationPer", "100").trim(),
                    brightPer = json.optString("brightPer", "21").trim(),
                    opacityPer = json.optString("opacityPer", "21").trim(),
                    blurSwitch = json.optString("blurSwitch", "true").trim(),
                    overlaySwitch = json.optString("overlaySwitch", "true").trim()
                )

                val jsonStore = jsonFull.encodeToString(config)

                val sharedPref =
                    context.getSharedPreferences("kano_ZTE_store", Context.MODE_PRIVATE)
                sharedPref.edit(commit = true) {
                    putString("kano_theme", jsonStore)
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

    //读取主题
    get("/api/get_theme") {
        try {
            val sharedPref = context.getSharedPreferences("kano_ZTE_store", Context.MODE_PRIVATE)
            val kano_theme = sharedPref.getString("kano_theme", null)
            val json = try {
                kano_theme?.let { JSONObject(it) }
            } catch (e: Exception) {
                null
            }

            KanoLog.d(TAG, "读取 SharedPreferences: $kano_theme")

            val config = if (json != null && json.length() > 0) {
                ThemeConfig(
                    backgroundEnabled = json.optString("backgroundEnabled", "false").trim(),
                    backgroundUrl = json.optString("backgroundUrl", "").trim(),
                    textColor = json.optString("textColor", "rgba(255, 255, 255, 1)").trim(),
                    textColorPer = json.optString("textColorPer", "100").trim(),
                    themeColor = json.optString("themeColor", "201").trim(),
                    colorPer = json.optString("colorPer", "67").trim(),
                    saturationPer = json.optString("saturationPer", "100").trim(),
                    brightPer = json.optString("brightPer", "21").trim(),
                    opacityPer = json.optString("opacityPer", "21").trim(),
                    blurSwitch = json.optString("blurSwitch", "true").trim(),
                    overlaySwitch = json.optString("overlaySwitch", "true").trim()
                )
            } else {
                ThemeConfig()
            }

            val text = jsonFull.encodeToString(config)
            call.response.headers.append("Access-Control-Allow-Origin", "*")
            call.respondText(
                text,
                ContentType.Application.Json,
                HttpStatusCode.OK
            )
        } catch (e: Exception) {
            KanoLog.d(TAG, "读取主题出错： ${e.message}")
            call.response.headers.append("Access-Control-Allow-Origin", "*")
            call.respondText(
                """{"error":"读取主题出错"}""",
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError
            )
        }
    }
}