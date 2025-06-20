package com.minikano.f50_sms.utils

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.*
import kotlinx.serialization.*
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap

// 定时任务管理器
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
            scheduler!!.reschedule()
            KanoLog.d("kano_ZTE_LOG_TaskScheduler", "定时任务调度器已启动，共有 ${scheduler?.listAllTasks()?.size} 个任务")
        }
    }

    fun get(): TaskScheduler? = scheduler

    fun stop() {
        scheduler?.stop()
        scheduler = null
        KanoLog.d("kano_ZTE_LOG_TaskScheduler", "scheduler 实例已停止！")
    }
}

@Serializable
data class ScheduledTask(
    val key: Long,
    val id: String,
    var time: String,
    var actionMap: Map<String, String> = emptyMap(),
    var repeatDaily: Boolean = true,
    @Transient var task: () -> Unit = {},
    var lastRunTimestamp: Long? = null,
    var hasTriggered: Boolean = false
)

class TaskScheduler(
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

    private fun getNextTriggerTimeMillis(): Long? {
        val now = Calendar.getInstance()
        return taskMap.values.mapNotNull { task ->
            // 跳过一次性任务已触发的
            if (!task.repeatDaily && task.hasTriggered) return@mapNotNull null

            try {
                val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
                val parsed = sdf.parse(task.time) ?: return@mapNotNull null
                val targetCal = Calendar.getInstance().apply {
                    time = parsed
                    set(Calendar.YEAR, now.get(Calendar.YEAR))
                    set(Calendar.MONTH, now.get(Calendar.MONTH))
                    set(Calendar.DAY_OF_MONTH, now.get(Calendar.DAY_OF_MONTH))
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                if (targetCal.before(now)) {
                    targetCal.add(Calendar.DATE, 1)
                }
                targetCal.timeInMillis
            } catch (e: Exception) {
                null
            }
        }.minOrNull()
    }

    fun start() {
        if (job?.isActive == true) return
        reschedule()
    }


    fun reschedule() {
        job?.cancel()
        val nextTimeMillis = getNextTriggerTimeMillis() ?: return
        KanoLog.d("kano_ZTE_LOG_TaskScheduler", "下一任务时间：${SimpleDateFormat("HH:mm", Locale.getDefault()).format(nextTimeMillis)}")
        val delayMillis = (nextTimeMillis - System.currentTimeMillis()).coerceAtLeast(0)

        job = scope.launch {
            delay(delayMillis)
            triggerIfMatchedTasks()
            reschedule()
        }
    }

    private fun triggerIfMatchedTasks() {
        val now = Date()
        val nowTimeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(now)
        val currentMillis = System.currentTimeMillis()

        var shouldPersist = false

        for ((_, task) in taskMap) {
            if (task.time == nowTimeStr && !task.hasTriggered) {
                task.task()
                KanoLog.d("kano_ZTE_LOG_TaskScheduler", "定时任务 ${task.id} 在 $nowTimeStr 执行了")
                task.lastRunTimestamp = currentMillis
                task.hasTriggered = true
                shouldPersist = true
            }
        }

        if (shouldPersist) persistTasks()
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    fun addTask(
        key: Long,
        id: String,
        time: String,
        repeatDaily: Boolean = true,
        actionMap: Map<String, String>
    ) {
        val formattedTime = when {
            time.matches(Regex("\\d{2}:\\d{2}:\\d{2}")) -> time.substring(0, 5)
            time.matches(Regex("\\d{2}:\\d{2}")) -> time
            else -> {
                KanoLog.w("kano_ZTE_LOG_TaskScheduler", "时间格式不正确: $time，默认使用原值")
                time
            }
        }

        val taskObj = ScheduledTask(
            key = key,
            id = id,
            time = formattedTime,
            actionMap = actionMap,
            repeatDaily = repeatDaily
        )
        taskMap[id] = taskObj
        persistTasks()
        restoreTasks()
        reschedule()
    }

    fun removeTask(id: String) {
        taskMap.remove(id)
        persistTasks()
        reschedule()
    }

    fun getTask(id: String): ScheduledTask? = taskMap[id]

    fun listAllTasks(): List<ScheduledTask> = taskMap.values.toList()

    fun clearAllTasks() {
        taskMap.clear()
        persistTasks()
        reschedule()
    }

    private fun persistTasks() {
        val list = taskMap.values.map { it.copy(task = {}) }
        val jsonStr = json.encodeToString(list)
        prefs.edit().putString("kano_scheduled_tasks", jsonStr).apply()
    }

    @OptIn(DelicateCoroutinesApi::class)
    fun restoreTasks() {
        if (TaskSchedulerManager.sharedPrefs == null) return
        val ADB_IP = TaskSchedulerManager.sharedPrefs!!.getString("gateway_ip", "")?.substringBefore(":").orEmpty()
        val ADMIN_PWD = TaskSchedulerManager.sharedPrefs!!.getString("ADMIN_PWD", "Wa@9w+YWRtaW4=") ?: "Wa@9w+YWRtaW4="

        val jsonStr = prefs.getString("kano_scheduled_tasks", null) ?: return
        try {
            val restoredList = json.decodeFromString<List<ScheduledTask>>(jsonStr)
            restoredList.forEach { saved ->
                saved.task = {
                    scope.launch {
                        if (!scope.isActive) return@launch //避免任务在已停止调度器中执行
                        try {
                            val req = KanoGoformRequest("http://$ADB_IP:8080")
                            val cookie = req.login(ADMIN_PWD)
                            if (cookie != null) {
                                val result = req.postData(cookie, saved.actionMap)
                                req.logout(cookie)
                                if (result?.getString("result") == "success") {
                                    KanoLog.d("kano_ZTE_LOG_TaskScheduler", "zte_web_API执行成功")
                                }
                            }
                        } catch (e: Exception) {
                            KanoLog.e("kano_ZTE_LOG_TaskScheduler", "任务 ${saved.id} 执行失败: ${e.message}")
                        }
                    }
                }
                taskMap[saved.id] = saved
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}