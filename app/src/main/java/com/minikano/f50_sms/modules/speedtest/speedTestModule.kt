package com.minikano.f50_sms.modules.speedtest

import android.content.Context
import com.minikano.f50_sms.utils.KanoUtils
import com.minikano.f50_sms.modules.BASE_TAG
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.application.call
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondOutputStream
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlin.random.Random

object SpeedTestCache {
    val buffer = ByteArray(1024 * 1024) { 0xAB.toByte() }
}

fun Route.speedTestModule(context: Context) {
    val TAG = "[$BASE_TAG]_speedTestModule"

    //测速
    get("/api/speedtest") {
        val parms = call.request.queryParameters
        val totalChunks = KanoUtils.getChunkCount(parms["ckSize"]).coerceIn(1, 1024)
        val enableCors = parms.contains("cors")
        val buffer = SpeedTestCache.buffer

        if (enableCors) {
            call.response.headers.append("Access-Control-Allow-Origin", "*")
            call.response.headers.append("Access-Control-Allow-Methods", "GET, POST")
        }

        val contentLength = buffer.size.toLong() * totalChunks
        call.response.headers.append(HttpHeaders.ContentLength, contentLength.toString())
        call.response.headers.append(HttpHeaders.ContentType, ContentType.Application.OctetStream.toString())
        call.response.headers.append(HttpHeaders.ContentDisposition, "attachment; filename=random.dat")
        call.response.headers.append(HttpHeaders.CacheControl, "no-store, no-cache, must-revalidate")
        call.response.headers.append(HttpHeaders.Pragma, "no-cache")
        call.response.headers.append("Content-Transfer-Encoding", "binary")

        call.respondOutputStream(contentType = ContentType.Application.OctetStream) {
            repeat(totalChunks) {
                write(buffer)
            }
            flush()
        }
    }
}