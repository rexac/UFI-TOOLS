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
    // Hop-by-hop headers (RFC 7230 6.1)
    "connection",
    "keep-alive",
    "proxy-authenticate",
    "proxy-authorization",
    "te",
    "trailers",
    "transfer-encoding",
    "upgrade",

    // 自动生成或不该手动指定的头部
    "host",
    "content-length",
    "expect",

    // 浏览器相关敏感头部（由代理自己控制）
    "referer",
    "origin",
    "sec-fetch-site",
    "sec-fetch-mode",
    "sec-fetch-dest",
    "sec-fetch-user",
    "sec-ch-ua",
    "sec-ch-ua-mobile",
    "sec-ch-ua-platform",

    // 与缓存、压缩、代理行为密切相关
    "via",
    "x-forwarded-for",
    "x-forwarded-proto",
    "x-real-ip",

    // 认证相关（伪造）
    "authorization",

    // 安全策略相关（前端注入，混淆）
    "content-security-policy",
    "content-security-policy-report-only",
    "clear-site-data"
)

fun isSafeHeader(header: String): Boolean {
    return header.lowercase() !in unsafeHeaderNames
}
/**
 * 只是反代简单的接口，html网页反代不完全
 * */
fun Route.anyProxyModule(context: Context) {
    val TAG = "[$BASE_TAG]_atModule"
    //AT指令
    route("/api/proxy/{...}") {
        handle {
            val rawPath = call.request.uri.removePrefix("/api/proxy/")
            val targetUrl = rawPath.removePrefix("--")

            val method = call.request.httpMethod.value
            val okHttpClient = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val request = chain.request().newBuilder()
                        .removeHeader("Accept-Encoding") // 防止 OkHttp 自动解压
                        .addHeader("Accept-Encoding", "identity") // or do not accept compression
                        .build()
                    chain.proceed(request)
                }
                .build()

            val requestBody = if (call.request.httpMethod in listOf(
                    HttpMethod.Post,
                    HttpMethod.Put,
                    HttpMethod.Patch
                )
            ) {
                val bodyBytes = call.receiveChannel().toByteArray()
                bodyBytes.toRequestBody(call.request.contentType()?.toString()?.toMediaTypeOrNull())
            } else null

            // 构造请求头
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

                val bodyBytes = response.body?.bytes() ?: byteArrayOf()
                val contentType =
                    response.body?.contentType()?.toString()?.let { ContentType.parse(it) }
                val statusCode = response.code

                // 如果是 HTML，则替换资源路径
                if (contentType?.match(ContentType.Text.Html) == true) {
                    val html = bodyBytes.toString(Charsets.UTF_8)
                    val baseUrl = targetUrl.substringBeforeLast("/").substringBefore("?")
                    val proxyPrefix = "/api/proxy/--$baseUrl"
                    val rewrittenHtml = html.replace("""(src|href)\s*=\s*["']/(.*?)["']""".toRegex()) {
                        val attr = it.groupValues[1]
                        val path = it.groupValues[2]
                        """$attr="$proxyPrefix/$path""""
                    }
                    call.respondText(rewrittenHtml, contentType, HttpStatusCode.fromValue(statusCode))
                } else {
                    call.respondBytes(bodyBytes, contentType, HttpStatusCode.fromValue(statusCode))
                }
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadGateway, "Proxy error: ${e.message}")
            }
        }
    }
}