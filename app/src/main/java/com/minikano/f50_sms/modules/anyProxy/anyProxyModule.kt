package com.minikano.f50_sms.modules.at

import android.content.Context
import com.minikano.f50_sms.modules.BASE_TAG
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.http.*
import io.ktor.server.routing.Route
import io.ktor.util.toByteArray
import okhttp3.*
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody

val unsafeHeaderNames = setOf(
    "connection", "keep-alive", "proxy-authenticate", "proxy-authorization", "te", "trailers",
    "transfer-encoding", "upgrade", "host", "content-length", "expect",
    "referer", "origin", "sec-fetch-site", "sec-fetch-mode", "sec-fetch-dest", "sec-fetch-user",
    "sec-ch-ua", "sec-ch-ua-mobile", "sec-ch-ua-platform", "via", "x-forwarded-for",
    "x-forwarded-proto", "x-real-ip", "authorization", "content-security-policy",
    "content-security-policy-report-only", "clear-site-data"
)

fun isSafeHeader(header: String): Boolean {
    return header.lowercase() !in unsafeHeaderNames
}

fun Route.anyProxyModule(context: Context) {
    val TAG = "[$BASE_TAG]_atModule"

    route("/api/proxy/{...}") {
        handle {
            val rawPath = call.request.uri.removePrefix("/api/proxy/")
            val targetUrl = rawPath.removePrefix("--")
            val method = call.request.httpMethod.value

            val okHttpClient = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val request = chain.request().newBuilder()
                        .removeHeader("Accept-Encoding")
                        .addHeader("Accept-Encoding", "identity") // 避免 GZIP 解压
                        .build()
                    chain.proceed(request)
                }.build()

            // 构建请求体（如果有）
            val requestBody = if (call.request.httpMethod in listOf(
                    HttpMethod.Post, HttpMethod.Put, HttpMethod.Patch
                )
            ) {
                val bodyBytes = call.receiveChannel().toByteArray()
                bodyBytes.toRequestBody(call.request.contentType()?.toString()?.toMediaTypeOrNull())
            } else null

            // 构建请求头
            val headersBuilder = Headers.Builder()
            for ((key, values) in call.request.headers.entries()) {
                if (key.startsWith("kano-", ignoreCase = true)) {
                    headersBuilder.addUnsafeNonAscii(key.removePrefix("kano-"), values.first())
                } else if (isSafeHeader(key)) {
                    headersBuilder.addUnsafeNonAscii(key, values.first())
                }
            }

            val request = Request.Builder()
                .url(targetUrl)
                .method(method, requestBody)
                .headers(headersBuilder.build())
                .build()

            try {
                val response = okHttpClient.newCall(request).execute()
                val responseBody = response.body
                val statusCode = response.code
                val contentType = responseBody?.contentType()?.toString()?.let { ContentType.parse(it) }

                // 处理响应头
                response.headers.names().forEach { name ->
                    val lowerName = name.lowercase()
                    val values = response.headers.values(name)

                    if (lowerName == "set-cookie") {
                        values.forEach { rawCookie ->
                            call.response.headers.append("Kano-SetCk", rawCookie)
                        }
                    } else if (isSafeHeader(name)) {
                        values.forEach { value ->
                            call.response.headers.append(name, value)
                        }
                    }
                }

                val origin = call.request.header("Origin") ?: "*"
                if (origin != "*") {
                    call.response.headers.append("Access-Control-Allow-Origin", origin)
                    call.response.headers.append("Access-Control-Allow-Credentials", "true")
                    call.response.headers.append("Access-Control-Expose-Headers", "Kano-SetCk")
                }

                if (responseBody != null) {
                    if (contentType?.match(ContentType.Text.Html) == true) {
                        // HTML 模式，替换资源路径
                        val html = responseBody.string()
                        val baseUrl = targetUrl.substringBeforeLast("/").substringBefore("?")
                        val proxyPrefix = "/api/proxy/--$baseUrl"
                        val rewrittenHtml = html.replace("""(src|href)\s*=\s*["']/(.*?)["']""".toRegex()) {
                            val attr = it.groupValues[1]
                            val path = it.groupValues[2]
                            """$attr="$proxyPrefix/$path""""
                        }
                        call.respondText(rewrittenHtml, contentType, HttpStatusCode.fromValue(statusCode))
                    } else {
                        // 非 HTML，使用流式响应
                        call.respondOutputStream(contentType, HttpStatusCode.fromValue(statusCode)) {
                            responseBody.byteStream().use { input ->
                                input.copyTo(this)
                            }
                        }
                    }
                } else {
                    call.respond(HttpStatusCode.BadGateway, "Empty response body")
                }
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadGateway, "Proxy error: ${e.message}")
            }
        }
    }
}