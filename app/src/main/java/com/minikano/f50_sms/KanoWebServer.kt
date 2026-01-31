package com.minikano.f50_sms

import android.content.Context
import com.minikano.f50_sms.modules.mainModule
import com.minikano.f50_sms.utils.KanoLog
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import java.util.concurrent.atomic.AtomicBoolean

class KanoWebServer(private val context: Context, port: Int, private val proxyServerIp: String) {

    companion object {
        private val running = AtomicBoolean(false)
    }

    private val server = embeddedServer(CIO, port = port, host = "0.0.0.0") {
        mainModule(context, proxyServerIp)
    }

    fun start() {
        Thread {
            if (!running.compareAndSet(false, true)) {
                KanoLog.d("UFI_TOOLS_LOG", "Web server is already running.")
                return@Thread
            }
            try {
                server.start(wait = true)
            } catch (e: Exception) {
                KanoLog.e("UFI_TOOLS_LOG", "Server failed: ${e.message}", e)
                running.set(false) // 启动失败，允许再次启动
            }
        }.start()
    }

    fun stop() {
        try {
            server.stop(1000, 2000)
        } finally {
            running.set(false)
        }
    }
}
