package com.minikano.f50_sms.utils

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.serialization.*
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap

//定时任务管理器（只有请求中兴WEBAPI的功能）
object TaskSchedulerManager {
    @SuppressLint("StaticFieldLeak")
    var scheduler: TaskScheduler? = null
    var sharedPrefs: SharedPreferences? = null
    fun init(context: Context) {
        sharedPrefs = context.getSharedPreferences("kano_ZTE_store", Context.MODE_PRIVATE)
        if (scheduler == null) {
            scheduler = TaskScheduler(context.applicationContext, perfName = "kano_ZTE_store")
            scheduler!!.restoreTasks()
            scheduler!!.start()
            Log.d("kano_ZTE_LOG_TaskScheduler", "定时任务调度器已启动，共有 ${scheduler?.listAllTasks()?.size} 个任务")
            Log.d("kano_ZTE_LOG_TaskScheduler","TaskSchedulerManager 初始化成功！")
        }
    }

    fun get(): TaskScheduler? {
        return scheduler
    }

    fun stop() {
        scheduler?.stop()
        scheduler = null
        Log.d("kano_ZTE_LOG_TaskScheduler","scheduler 实例已停止！")
    }
}

@Serializable
data class ScheduledTask(
    val key:Long,
    val id: String,
    var time: String,                         // "HH:mm:ss" 或 "yyyy-MM-dd HH:mm:ss"
    var repeatDaily: Boolean = true,          // 是否每天重复
    var actionMap: Map<String, String> = emptyMap(),
    @Transient var task: () -> Unit = {},     // 执行的任务，不参与序列化
    var lastRunTimestamp: Long? = null,       // 上次执行时间戳，用于周期任务
    var hasTriggered: Boolean = false         // 非周期任务每日只触发一次
)

class TaskScheduler (
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default),
    private val perfName: String = "kano_ZTE_store"
) {
    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(perfName, Context.MODE_PRIVATE)
    }

    private val taskMap = ConcurrentHashMap<String, ScheduledTask>()
    private var job: Job? = null

    private val json = Json { ignoreUnknownKeys = true }

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                val now = System.currentTimeMillis()
                val calendar = Calendar.getInstance().apply { timeInMillis = now }

                // 当前分钟 + 1，秒和毫秒清零
                calendar.add(Calendar.MINUTE, 1)
                calendar.set(Calendar.SECOND, 0)
                calendar.set(Calendar.MILLISECOND, 0)

                val nextMinuteMillis = calendar.timeInMillis
                val delayMillis = nextMinuteMillis - now

                // 执行任务检查
                triggerIfMatchedTasks()

                // 延迟直到下一个整分钟
                delay(delayMillis)
            }
        }
    }

    private fun triggerIfMatchedTasks() {
        val now = Date()
        val currentMillis = System.currentTimeMillis()
        val nowMinuteStr = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(now)
        val onlyTime = SimpleDateFormat("HH:mm", Locale.getDefault()).format(now)

        for ((_, task) in taskMap) {
            val shouldRun = when {
                isFullDateTime(task.time) -> task.time == nowMinuteStr && !task.hasTriggered
                else -> task.time == onlyTime && !task.hasTriggered
            }

            if (shouldRun) {
                task.task()
                Log.d("kano_ZTE_LOG_TaskScheduler", "定时任务${task.id}在${task.time} 执行了")
                task.lastRunTimestamp = currentMillis
                task.hasTriggered = true
                persistTasks()
            }
        }

        if (onlyTime == "00:00") {
            var shouldPersist = false
            for (t in taskMap.values) {
                if (t.repeatDaily && t.hasTriggered) {
                    t.hasTriggered = false
                    shouldPersist = true
                }
            }
            if (shouldPersist) persistTasks()
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    // 添加一次性或重复任务（基于时间字符串）
    fun addTask(
        key: Long,
        id: String,
        time: String,
        repeatDaily: Boolean = true,
        actionMap: Map<String, String>
    ) {
        val formattedTime = when {
            time.matches(Regex("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}")) -> time.substring(0, 16) // "yyyy-MM-dd HH:mm:ss"
            time.matches(Regex("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}")) -> time // "yyyy-MM-dd HH:mm"
            time.matches(Regex("\\d{2}:\\d{2}:\\d{2}")) -> time.substring(0, 5) // "HH:mm:ss"
            time.matches(Regex("\\d{2}:\\d{2}")) -> time // "HH:mm"
            else -> {
                Log.w("kano_ZTE_LOG_TaskScheduler", "时间格式不正确: $time，默认使用原值")
                time
            }
        }

        val taskObj = ScheduledTask(
            key = key,
            id = id,
            time = formattedTime,
            repeatDaily = repeatDaily,
            actionMap = actionMap,
        )
        taskMap[id] = taskObj
        persistTasks()
        restoreTasks()
    }

    fun removeTask(id: String) {
        taskMap.remove(id)
        persistTasks()
    }

    fun getTask(id: String): ScheduledTask? = taskMap[id]

    fun listAllTasks(): List<ScheduledTask> = taskMap.values.toList()

    fun clearAllTasks() {
        taskMap.clear()
        persistTasks()
    }

    private fun persistTasks() {
        val serializableList = taskMap.values.map {
            it.copy(task = {}) // 排除 lambda
        }
        val jsonStr = json.encodeToString(serializableList)
        prefs.edit().putString("kano_scheduled_tasks", jsonStr).apply()
    }

    @OptIn(DelicateCoroutinesApi::class)
    fun restoreTasks() {
        if(TaskSchedulerManager.sharedPrefs == null){ return }
        val ADB_IP = TaskSchedulerManager.sharedPrefs!!.getString("gateway_ip", "")?.substringBefore(":").orEmpty()
        val ADMIN_PWD = TaskSchedulerManager.sharedPrefs!!.getString("ADMIN_PWD", "Wa@9w+YWRtaW4=") ?: "Wa@9w+YWRtaW4="

        val jsonStr = prefs.getString("kano_scheduled_tasks", null) ?: return
        try {
            val restoredList = json.decodeFromString<List<ScheduledTask>>(jsonStr)
            restoredList.forEach { saved ->
                saved.task = {
                    GlobalScope.launch {
                        try {
                            Log.d("kano_ZTE_LOG_TaskScheduler", "开始请求 http://$ADB_IP:8080")
                            val req = KanoGoformRequest("http://$ADB_IP:8080")
                            val cookie = req.login(ADMIN_PWD)
                            if (cookie != null) {
                                val result = req.postData(cookie, saved.actionMap)
                                req.logout(cookie)
                                if (result?.getString("result") == "success") {
                                    Log.d("kano_ZTE_LOG_TaskScheduler", "zte_web_API执行成功")
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("kano_ZTE_LOG_TaskScheduler", "任务 ${saved.id} 执行失败: ${e.message}")
                        }
                    }
                }
                taskMap[saved.id] = saved
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // 辅助函数
    private fun formatTime(date: Date): String =
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(date)

    private fun isFullDateTime(value: String): Boolean =
        value.matches(Regex("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}"))
}