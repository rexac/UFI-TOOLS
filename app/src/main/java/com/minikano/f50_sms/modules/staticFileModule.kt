package com.minikano.f50_sms.modules

import android.content.Context
import com.minikano.f50_sms.utils.KanoLog
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.defaultForFilePath
import io.ktor.server.application.call
import io.ktor.server.request.uri
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

//静态资源
fun Route.staticFileModule(context: Context) {
    val TAG = "[$BASE_TAG]_staticFileModule"

    get("{...}") {
        val rawPath = call.request.uri.removePrefix("/")
        val path = if (rawPath.isBlank()) "index.html" else rawPath

        try {
            val inputStream = context.assets.open(path)
            val bytes = inputStream.readBytes()
            val contentType = ContentType.defaultForFilePath(path)
            call.respondBytes(bytes, contentType)
        } catch (e: Exception) {
            KanoLog.e(TAG,"静态资源：$rawPath 不存在",e)
            call.respond(HttpStatusCode.NotFound, "404 Not Found")
        }
    }
}