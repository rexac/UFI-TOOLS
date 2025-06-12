package com.minikano.f50_sms

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.minikano.f50_sms.utils.KanoLog
import com.minikano.f50_sms.utils.KanoUtils
import com.minikano.f50_sms.utils.ShellKano
import com.minikano.f50_sms.utils.ShellKano.Companion.executeShellFromAssetsSubfolderWithArgs
import com.minikano.f50_sms.utils.SmbThrottledRunner
import com.minikano.f50_sms.utils.SmsPoll
import java.util.concurrent.Executors
import kotlin.concurrent.thread

class ADBService : Service() {
    private lateinit var runnable: Runnable
    private lateinit var handlerThread: HandlerThread
    private lateinit var handler: Handler
    private val adbExecutor = Executors.newSingleThreadExecutor()


    companion object {
        @Volatile
        var adbIsReady: Boolean = false
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1234599, createNotification())

        handlerThread = HandlerThread("KanoBackgroundHandler")
        handlerThread.start()
        handler = Handler(handlerThread.looper)
        // 串行执行任务
        handler.post {
            resetFilesFromAssets(applicationContext)

            // 等文件拷贝完成后再继续
            startAdbKeepAliveTask(applicationContext)
            val executor = Executors.newFixedThreadPool(2)
            executor.execute(runnableSMS)
            executor.execute(runnableSMB)
        }

        thread {
            //启动iperf3
            var result =
                executeShellFromAssetsSubfolderWithArgs(
                    applicationContext,
                    "shell/iperf3",
                    "-s",
                    "-D",
                )
            if (result != null) {
                KanoLog.d("kano_ZTE_LOG", "iperf3已启动")
            } else {
                KanoLog.d("kano_ZTE_LOG", "iperf3启动失败")
            }
        }

        return START_STICKY
    }

    private fun resetFilesFromAssets(context: Context) {
        val filesDir = context.filesDir

        // 删除所有文件
        filesDir.listFiles()?.forEach { file ->
            if (file.isFile) {
                file.delete()
            }
        }

        // 复制 assets 中的所有文件
        try {
            KanoUtils.copyAssetsRecursively(context, "shell", context.filesDir)
            Log.d("kano_ZTE_LOG", "已初始化 files 目录")
        } catch (e: Exception) {
            Log.e("kano_ZTE_LOG", "初始化 files 目录失败:${e.message}")
        }
    }

    private val runnableSMS = object : Runnable {
        override fun run() {
            val sharedPrefs = getSharedPreferences("kano_ZTE_store", Context.MODE_PRIVATE)
            if (sharedPrefs.getString("kano_sms_forward_enabled", "0") == "1") {
                try {
                    SmsPoll.checkNewSmsAndSend(applicationContext)
                } catch (e: Exception) {
                    KanoLog.e("kano_ZTE_LOG", "读取短信时发生错误", e)
                }
            }
            handler.postDelayed(this, 5000)
        }
    }

    private val runnableSMB = object : Runnable {
        override fun run() {
            try {
                KanoLog.d("kano_ZTE_LOG", "激活SMB内置脚本中...")
                SmbThrottledRunner.runOnceInThread(applicationContext)
            } catch (e: Exception) {
                KanoLog.e("kano_ZTE_LOG", "激活SMB内置脚本错误")
            }
            handler.postDelayed(this, 15_000)
        }
    }

    private fun startAdbKeepAliveTask(context: Context) {
        adbExecutor.execute {
            try {
                val adbPath = "shell/adb"

                while (!Thread.currentThread().isInterrupted) {
                    KanoLog.d("kano_ZTE_LOG", "保活ADB服务中...")

                    var result =
                        executeShellFromAssetsSubfolderWithArgs(context, adbPath, "devices") {
                            ShellKano.killProcessByName("adb")
                        }

                    if (result?.contains("localhost:5555\tdevice") == true) {
                        KanoLog.d("kano_ZTE_LOG", "adb存活，无需启动")
                        adbIsReady = true
                    } else {
                        KanoLog.w("kano_ZTE_LOG", "adb无设备或已退出，尝试启动")
                        adbIsReady = false

                        ShellKano.killProcessByName("adb")
                        Thread.sleep(1000)

                        executeShellFromAssetsSubfolderWithArgs(
                            context,
                            adbPath,
                            "connect",
                            "localhost"
                        ) {
                            ShellKano.killProcessByName("adb")
                        }

                        val maxWaitMs = 5_000
                        val interval = 500
                        var waited = 0

                        while (waited < maxWaitMs) {
                            result = executeShellFromAssetsSubfolderWithArgs(
                                context,
                                adbPath,
                                "devices"
                            ) {
                                ShellKano.killProcessByName("adb")
                            }

                            if (result?.contains("localhost:5555\tdevice") == true) {
                                KanoLog.d("kano_ZTE_LOG", "ADB连接成功: $result")
                                adbIsReady = true
                                break
                            } else {
                                KanoLog.d("kano_ZTE_LOG", "ADB未连接: $result")
                            }

                            Thread.sleep(interval.toLong())
                            waited += interval
                        }
                    }
                    // 每 11 秒轮询一次
                    Thread.sleep(11_000)
                }
            } catch (e: Exception) {
                KanoLog.e("kano_ZTE_LOG", "ADB 保活线程异常", e)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handlerThread.quitSafely()
        handler.removeCallbacks(runnable)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotification(): Notification {
        val channelId = "kano_adb_service"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "adb_service后台服务",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("adb_service后台运行中")
            .setContentText("正在执行adb_service定时任务")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .build()
    }
}