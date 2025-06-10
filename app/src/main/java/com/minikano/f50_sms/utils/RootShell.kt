package com.minikano.f50_sms.utils

import android.net.LocalSocket
import android.net.LocalSocketAddress
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter

object RootShell {

    fun sendCommandToSocket(command: String, socketPath: String): String? {
        val socket = LocalSocket()
        val socketAddress = LocalSocketAddress(socketPath, LocalSocketAddress.Namespace.FILESYSTEM)

        try {
            KanoLog.d("kano_ZTE_LOG", "开始发送socket,目录：${socketPath},命令:${command}")

            socket.connect(socketAddress)

            KanoLog.d("kano_ZTE_LOG", "Socket")


            val outputStream = BufferedWriter(OutputStreamWriter(socket.outputStream))
            val inputStream = BufferedReader(InputStreamReader(socket.inputStream))

            // 发送命令
            outputStream.write(command)
            outputStream.write("\n")
            outputStream.write("echo __END__\n") // 标记结尾
            outputStream.flush()
            KanoLog.d("kano_ZTE_LOG", "Socket write")

            // 读取响应
            val result = StringBuilder()
            while (true) {
                val line = inputStream.readLine() ?: break
                if (line.trim() == "__END__") break
                KanoLog.d("kano_ZTE_LOG", "Socket : ${line.trim()}")
                result.appendLine(line)
            }

            return result.toString()

        } catch (e: IOException) {
            e.printStackTrace()
            KanoLog.d("kano_ZTE_LOG", "Socket Error: ${e.message}")
            return null
        } finally {
            try {
                socket.close()
            } catch (_: IOException) {
            }
        }
    }
}