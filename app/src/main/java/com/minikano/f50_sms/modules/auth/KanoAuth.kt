package com.minikano.f50_sms.modules.auth

import android.content.Context
import com.minikano.f50_sms.utils.KanoUtils
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path

object KanoAuth {
    val PREFS_NAME = "kano_ZTE_store"
    val PREF_LOGIN_TOKEN = "login_token"
    val PREF_TOKEN_ENABLED = "login_token_enabled"
    val REQUEST_SECRET_KEY = "minikano_kOyXz0Ciz4V7wR0IeKmJFYFQ20jd"

    fun checkAuth(call: ApplicationCall, context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val token = prefs.getString(PREF_LOGIN_TOKEN, "admin")
        val tokenEnabled = prefs.getString(PREF_TOKEN_ENABLED, "true")?.toBoolean() ?: true

        val uri = call.request.path()
        val method = call.request.httpMethod.value

        val apiWhiteList: List<String> = listOf(
            "/api/get_custom_head",
            "/api/version_info",
            "/api/need_token",
            "/api/get_theme",
            "/api/uploads",
            "/api/SELinux"
        )

        val noAuthRequired = !uri.startsWith("/api/") || apiWhiteList.any { uri.startsWith(it) }

        if (!tokenEnabled || noAuthRequired) return true

        val headers = call.request.headers
        val timestampStr = headers["kano-t"]
        val clientSignature = headers["kano-sign"]
        val authHeader = headers["authorization"]

        if (timestampStr.isNullOrBlank() || clientSignature.isNullOrBlank() || authHeader.isNullOrBlank() || token.isNullOrBlank()) {
            return false
        }

        if ((authHeader != KanoUtils.sha256Hex(token))) {
            return false
        }

        val clientTimestamp = timestampStr.toLongOrNull() ?: return false
        val raw = "minikano$method$uri$clientTimestamp"
        val expectedSignature = KanoUtils.HmacSignature(REQUEST_SECRET_KEY, raw)

        return expectedSignature.equals(clientSignature, ignoreCase = true)
    }
}