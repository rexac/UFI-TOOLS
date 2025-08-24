package com.minikano.f50_sms.utils

import android.content.Context
import android.os.Build
import com.minikano.f50_sms.ADBService.Companion.isExecutedSambaMount
import java.util.concurrent.atomic.AtomicBoolean
import jcifs.smb.SmbFile
import jcifs.context.SingletonContext
import java.io.File

object SmbThrottledRunner {
    private val running = AtomicBoolean(false)
    private val PREF_GATEWAY_IP = "gateway_ip"
    private val PREFS_NAME = "kano_ZTE_store"

    fun runOnceInThread(context: Context) {
        if (running.get()) {
            KanoLog.d("kano_ZTE_LOG", "SMB 命令正在执行中，跳过")
            return
        }
        val sharedPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        val gatewayIP = sharedPrefs.getString(PREF_GATEWAY_IP, "192.168.0.1:445")

        KanoLog.d("kano_ZTE_LOG", "SMB 命令正在执行中,IP:${gatewayIP}，跳过")

        val host = gatewayIP?.substringBefore(":")

        running.set(true)

        Thread {
            try {
                KanoLog.d("kano_ZTE_LOG", "开始执行 SMB 命令,连接到：\"smb://$host/internal_storage/\"")

                val ctx = SingletonContext.getInstance()
                val smbFile = SmbFile("smb://$host/internal_storage/", ctx)

                if (smbFile.exists()) {
                    KanoLog.d("kano_ZTE_LOG", "SMB路径存在")
                    if(!isExecutedSambaMount) {
                        try {
                            val socketPath = File(context.filesDir, "kano_root_shell.sock")
                            if (!socketPath.exists()) {
                                throw Exception("执行命令失败，没有找到 socat 创建的 sock (高级功能是否开启？)")
                            }
                            val result =
                                RootShell.sendCommandToSocket(
                                    """
SRC_LIST="/sdcard/DCIM /mnt/media_rw /storage/sdcard0"
TGT_LIST="/data/SAMBA_SHARE/机内存储 /data/SAMBA_SHARE/外部存储 /data/SAMBA_SHARE/SD卡"

i=1
for src in ${'$'}SRC_LIST; do
  tgt=${'$'}(echo ${'$'}TGT_LIST | cut -d' ' -f${'$'}i)
  i=${'$'}((i + 1))

  [ ! -d "${'$'}tgt" ] && mkdir -p "${'$'}tgt"

  mount | grep " ${'$'}tgt " >/dev/null 2>&1
  if [ ${'$'}? -ne 0 ]; then
      mount --bind "${'$'}src" "${'$'}tgt"
      echo "Mounted ${'$'}src -> ${'$'}tgt"
  else
      echo "${'$'}tgt already mounted"
  fi
done
                        """.trimIndent(),
                                    socketPath.absolutePath
                                )
                                    ?: throw Exception("请检查命令输入格式")

                            KanoLog.d("kano_ZTE_LOG", "smb挂载执行结果： $result")
                            isExecutedSambaMount = true
                        } catch (e: Exception) {
                            KanoLog.e("kano_ZTE_LOG", "smb挂载执行失败", e)
                        }
                    }
                } else {
                    KanoLog.d("kano_ZTE_LOG", "SMB路径不存在")
                }
            } catch (e: Exception) {
                KanoLog.e("kano_ZTE_LOG", "SMB命令错误：${e.message}")
            } finally {
                running.set(false)
                KanoLog.d("kano_ZTE_LOG", "SMB 命令执行完成")
            }
        }.start()
    }
}