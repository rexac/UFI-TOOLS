package com.minikano.f50_sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.minikano.f50_sms.MainActivity.Companion.isEnableLog
import com.minikano.f50_sms.utils.ShellKano

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d("kano_ZTE_LOG", "开机广播接收到，准备启动服务")

            val startIntent = Intent(context, WebService::class.java)
            startIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startForegroundService(startIntent)
            Log.d("kano_ZTE_LOG", "启动WebService")

            val startIntent_ADB = Intent(context, ADBService::class.java)
            startIntent_ADB.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startForegroundService(startIntent_ADB)
            Log.d("kano_ZTE_LOG", "启动ADBService")

            //激活网络ADB等
            ShellKano.runADB(context)
            Log.d("kano_ZTE_LOG", "激活网络ADB")

            //检测有无开启调试日志
            val sf = context.getSharedPreferences("kano_ZTE_store", Context.MODE_PRIVATE)
            isEnableLog = sf.getString("kano_is_debug", "false").equals("true")
        }
    }
}