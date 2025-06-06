package com.minikano.f50_sms

import android.content.Context
import com.minikano.f50_sms.modules.mainModule
import com.minikano.f50_sms.utils.KanoLog
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer

class KanoWebServer(private val context: Context, port: Int,private val proxyServerIp:String) {

    companion object {
        @Volatile
        var running: Boolean = false
    }

    private val server = embeddedServer(CIO, port = port) {
        mainModule(context,proxyServerIp)
    }

    fun start(){
        Thread {
            try {
                server.start(wait = true)
                running = true
            } catch (e: Exception) {
                KanoLog.e("kano_ZTE_LOG", "Server failed: ${e.message}", e)
            }
        }.start()
    }
    fun stop() = server.stop(1000, 2000)
}
