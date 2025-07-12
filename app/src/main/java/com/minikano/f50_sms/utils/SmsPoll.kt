package com.minikano.f50_sms.utils

import android.content.Context
import android.net.Uri
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class SmsInfo(val address: String, val body: String, val timestamp: Long)

object SmsPoll {
    private var lastSms: SmsInfo? = null

    //store
    private val PREFS_NAME = "kano_ZTE_store"

    fun checkNewSmsAndSend(context: Context) {
        val sms = getLatestSms(context) ?: return

        val now = System.currentTimeMillis()
        val minute = 2
        val withinMin = now - sms.timestamp <= minute * 60 * 1000
        val isNew = lastSms == null || sms != lastSms

        if (withinMin && isNew) {
            KanoLog.d("kano_ZTE_LOG", "收到新短信: ${sms.address} - ${sms.body}")
            lastSms = sms
            // 在这里做转发处理
            val sharedPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val sms_forward_method = sharedPrefs.getString("kano_sms_forward_method", "") ?: ""
            if(sms_forward_method =="SMTP") {
                forwardByEmail(lastSms, context)
            }
            else if(sms_forward_method == "CURL"){
                forwardSmsByCurl(lastSms,context)
            }
            else if(sms_forward_method == "DINGTALK"){
                forwardSmsByDingTalk(lastSms,context)
            }
        } else {
            KanoLog.d(
                "kano_ZTE_LOG",
                "无新短信，短信是否${minute}分钟内：$withinMin,短信是否为新：$isNew"
            )
        }
    }

    //通过curl转发
    fun forwardSmsByCurl(sms_data: SmsInfo?, context: Context) {
        if (sms_data == null) return
        val sharedPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        val originalCurl = sharedPrefs.getString("kano_sms_curl", null)
        if (originalCurl.isNullOrEmpty()) {
            KanoLog.e("kano_ZTE_LOG", "curl 配置错误：kano_sms_curl 为空")
            return
        }

        KanoLog.d("kano_ZTE_LOG", "开始转发短信...（CURL）")
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault())
        val smsText = sms_data.body.trimStart()
        val smsFrom = sms_data.address
        val smsTime = formatter.format(Instant.ofEpochMilli(sms_data.timestamp))

        //替换并发送
        val replacedCurl = originalCurl
            .replace("\n","")
            .replace("{{sms-body}}", smsText)
            .replace("{{sms-time}}", smsTime)
            .replace("{{sms-from}}", smsFrom).trimIndent()

        KanoCURL(context).send(replacedCurl)
    }

    //通过SMTP邮件转发
    fun forwardByEmail(sms_data: SmsInfo?, context: Context) {
        if (sms_data == null) return
        val sharedPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        val smtpHost = sharedPrefs.getString("kano_smtp_host", null)
        if (smtpHost.isNullOrEmpty()) {
            KanoLog.e("kano_ZTE_LOG", "SMTP 配置错误：kano_smtp_host 为空")
            return
        }

        val smtpTo = sharedPrefs.getString("kano_smtp_to", null)
        if (smtpTo.isNullOrEmpty()) {
            KanoLog.e("kano_ZTE_LOG", "SMTP 配置错误：kano_smtp_to 为空")
            return
        }

        val smtpPort = sharedPrefs.getString("kano_smtp_port", null)
        if (smtpPort.isNullOrEmpty()) {
            KanoLog.e("kano_ZTE_LOG", "SMTP 配置错误：kano_smtp_port 为空")
            return
        }

        val username = sharedPrefs.getString("kano_smtp_username", null)
        if (username.isNullOrEmpty()) {
            KanoLog.e("kano_ZTE_LOG", "SMTP 配置错误：kano_smtp_username 为空")
            return
        }

        val password = sharedPrefs.getString("kano_smtp_password", null)
        if (password.isNullOrEmpty()) {
            KanoLog.e("kano_ZTE_LOG", "SMTP 配置错误：kano_smtp_password 为空")
            return
        }

        val smtpClient = KanoSMTP(smtpHost, smtpPort, username, password)

        KanoLog.d("kano_ZTE_LOG", "开始转发短信...(SMTP)")

        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault())
        val previewText = sms_data.body.trimStart().let {
            if (it.length > 37) it.take(37) + "…" else it
        }
        smtpClient.sendEmail(
            to = smtpTo,
            subject = previewText,
            body = """
                <div>
                    <p>${sms_data!!.body.trimStart()}</p>
                    <p>📩 <b>来自：</b>${sms_data.address}</p>
                    <p>⏰ <b>时间：</b>${formatter.format(Instant.ofEpochMilli(sms_data.timestamp))}</p>
                    <div style="text-align: center;">
                        <i>Powered by <a href="https://github.com/kanoqwq/UFI-TOOLS" target="_blank">UFI-TOOLS</a></i>
                    </div>
                </div>
            """.trimIndent()
        )
    }

    //通过钉钉webhook转发
    fun forwardSmsByDingTalk(sms_data: SmsInfo?, context: Context) {
        if (sms_data == null) return
        val sharedPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        val webhookUrl = sharedPrefs.getString("kano_dingtalk_webhook", null)
        if (webhookUrl.isNullOrEmpty()) {
            KanoLog.e("kano_ZTE_LOG", "钉钉配置错误：kano_dingtalk_webhook 为空")
            return
        }

        val secret = sharedPrefs.getString("kano_dingtalk_secret", null)

        KanoLog.d("kano_ZTE_LOG", "开始转发短信...（钉钉）")
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault())
        val smsText = sms_data.body.trimStart()
        val smsFrom = sms_data.address
        val smsTime = formatter.format(Instant.ofEpochMilli(sms_data.timestamp))

        // 构建钉钉消息内容
        val messageContent = """
            📱 新短信通知
            
            📄 内容：$smsText
            📞 来自：$smsFrom
            ⏰ 时间：$smsTime
            
            Powered by UFI-TOOLS
        """.trimIndent()

        val dingTalkClient = KanoDingTalk(webhookUrl, secret)
        dingTalkClient.sendMessage(messageContent)
    }

    fun getLatestSms(context: Context): SmsInfo? {
        val uri = Uri.parse("content://sms/inbox")
        val projection = arrayOf("address", "body", "date")
        val sortOrder = "date DESC"

        return try {
            val cursor = context.contentResolver.query(uri, projection, null, null, sortOrder)
            cursor?.use {
                if (it.moveToFirst()) {
                    val address = it.getString(it.getColumnIndexOrThrow("address"))
                    val body = it.getString(it.getColumnIndexOrThrow("body"))
                    val date = it.getLong(it.getColumnIndexOrThrow("date"))
                    SmsInfo(address, body, date)
                } else null
            }
        } catch (e: Exception) {
            KanoLog.e("kano_ZTE_LOG", "没有短信权限，读不到短信呢", e)
            null
        }
    }
}