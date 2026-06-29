package com.xmu.rollcall.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.xmu.rollcall.MainActivity
import com.xmu.rollcall.data.AccountStore
import com.xmu.rollcall.net.XmuLoginClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.text.SimpleDateFormat
import java.util.*

class WatchService : Service() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private var pollJob: Job? = null

    private lateinit var accountStore: AccountStore
    private var wakeLock: PowerManager.WakeLock? = null

    // Track processed rollcall IDs to avoid duplicate alerts/sign-ins
    private val processedRollcalls = mutableSetOf<Int>()

    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "xmu_rollcall_watch_channel"
        private const val ALERT_CHANNEL_ID = "xmu_rollcall_alert_channel"

        val isRunning = MutableStateFlow(false)
        val logs = MutableStateFlow<List<String>>(emptyList())
        val autoSubmit = MutableStateFlow(true)
        val pollIntervalMinutes = MutableStateFlow(5) // Default 5 mins

        fun addLog(message: String) {
            val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            val timeStr = sdf.format(Date())
            val formattedLog = "$timeStr: $message"
            val currentLogs = logs.value.toMutableList()
            currentLogs.add(0, formattedLog)
            if (currentLogs.size > 150) {
                currentLogs.removeAt(currentLogs.size - 1)
            }
            logs.value = currentLogs
        }
    }

    override fun onCreate() {
        super.onCreate()
        accountStore = AccountStore.createEncrypted(this)
        createNotificationChannels()
        
        // Acquire wake lock to keep CPU running when screen is off
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "XmuRollcall::WatchWakeLock").apply {
            acquire(10*60*1000L /*10 minutes*/)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startWatching()
            ACTION_STOP -> stopWatching()
        }
        return START_STICKY
    }

    private fun startWatching() {
        if (isRunning.value) return
        isRunning.value = true
        addLog("后台守护已开启，开始自动扫描...")

        // Promote to foreground service
        startForeground(NOTIFICATION_ID, createForegroundNotification("准备进行第一次扫描..."))

        // Start background polling
        pollJob = serviceScope.launch {
            while (isActive) {
                try {
                    performScan()
                } catch (e: CancellationException) {
                    break
                } catch (e: Exception) {
                    addLog("扫描出错: ${e.message}")
                    e.printStackTrace()
                }
                
                // Sleep for interval
                val intervalMs = pollIntervalMinutes.value * 60 * 1000L
                delay(intervalMs)
            }
        }
    }

    private fun stopWatching() {
        if (!isRunning.value) return
        isRunning.value = false
        addLog("后台守护已关闭。")
        pollJob?.cancel()
        pollJob = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private suspend fun performScan() {
        val activeAccount = accountStore.getActiveAccount()
        if (activeAccount == null) {
            addLog("未设置活跃账号，扫描跳过。")
            updateNotificationText("未配置活跃账号。")
            return
        }

        addLog("正在扫描 [${activeAccount.name}] 的签到...")
        updateNotificationText("正在扫描 [${activeAccount.name}]...")

        val loginClient = XmuLoginClient()
        if (activeAccount.serializedCookies.isNotEmpty()) {
            loginClient.restoreCookies(activeAccount.serializedCookies)
        }

        val rollcallService = RollcallService(loginClient, activeAccount.username)

        var rollcalls: List<RollcallRecord>
        try {
            rollcalls = withContext(Dispatchers.IO) { rollcallService.fetchRollcalls() }
        } catch (e: Exception) {
            // Probably cookie expired, try to log in again
            addLog("会话过期或请求失败，正在尝试重新登录...")
            try {
                val loginSuccess = withContext(Dispatchers.IO) {
                    loginClient.loginTronClass(activeAccount.username, activeAccount.password)
                }
                if (loginSuccess) {
                    accountStore.updateCookies(activeAccount.username, loginClient.getSerializedCookies())
                    addLog("重新登录成功！保存新会话。")
                    // Fetch again
                    rollcalls = withContext(Dispatchers.IO) { rollcallService.fetchRollcalls() }
                } else {
                    addLog("重新登录未获通过。")
                    sendAlertNotification("登录失败", "自动登录 [${activeAccount.name}] 失败，请检查账号密码。")
                    return
                }
            } catch (loginEx: Exception) {
                addLog("重新登录出错: ${loginEx.message}")
                sendAlertNotification("登录错误", "尝试登录 [${activeAccount.name}] 时出错: ${loginEx.message}")
                return
            }
        }

        val activeRollcalls = rollcalls.filter { !it.is_expired && it.rollcall_status != "on_call_fine" }
        addLog("发现 ${activeRollcalls.size} 个活动签到 (总共 ${rollcalls.size} 个)。")
        updateNotificationText("扫描完成，活动签到: ${activeRollcalls.size} 个")

        for (rollcall in activeRollcalls) {
            if (processedRollcalls.contains(rollcall.rollcall_id)) {
                // Already processed in this run or previous runs
                continue
            }

            // Mark as processed
            processedRollcalls.add(rollcall.rollcall_id)

            if (autoSubmit.value) {
                addLog("正在自动提交 [${rollcall.course_title}] 签到...")
                try {
                    val outcome = withContext(Dispatchers.IO) {
                        // Creating a fresh service invocation to ensure it has updated cookies
                        val freshService = RollcallService(loginClient, activeAccount.username)
                        if (rollcall.is_radar) {
                            freshService.answerActiveRollcalls().outcomes.firstOrNull { it.rollcall.rollcall_id == rollcall.rollcall_id }
                        } else {
                            freshService.answerActiveRollcalls().outcomes.firstOrNull { it.rollcall.rollcall_id == rollcall.rollcall_id }
                        }
                    }
                    if (outcome != null && outcome.success) {
                        val detail = when {
                            outcome.numberCode != null -> "签到码: ${outcome.numberCode}"
                            outcome.latitude != null && outcome.longitude != null -> String.format(Locale.US, "坐标: %.4f, %.4f", outcome.latitude, outcome.longitude)
                            else -> "提交成功"
                        }
                        addLog("自动签到成功: [${rollcall.course_title}] ($detail)")
                        sendAlertNotification("自动签到成功", "[${rollcall.course_title}] 签到成功！$detail")
                    } else {
                        val errMsg = outcome?.message ?: "原因未知"
                        addLog("自动签到失败: [${rollcall.course_title}] ($errMsg)")
                        sendAlertNotification("自动签到失败", "[${rollcall.course_title}] 签到失败: $errMsg")
                    }
                } catch (ansEx: Exception) {
                    addLog("提交签到异常: [${rollcall.course_title}] (${ansEx.message})")
                    sendAlertNotification("自动签到异常", "[${rollcall.course_title}] 提交出错: ${ansEx.message}")
                }
            } else {
                addLog("检测到签到: [${rollcall.course_title}] (${rollcall.typeLabel})，请点击手动签到。")
                sendAlertNotification("检测到新签到", "[${rollcall.course_title}] 请点击进入 App 签到。")
            }
        }
    }

    private fun createForegroundNotification(contentText: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("XMU 自动签到守护中")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_menu_myplaces)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    private fun updateNotificationText(text: String) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, createForegroundNotification(text))
    }

    private fun sendAlertNotification(title: String, text: String) {
        val pendingIntent = PendingIntent.getActivity(
            this, System.currentTimeMillis().toInt(),
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(Notification.DEFAULT_ALL)
            .setContentIntent(pendingIntent)
            .build()

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(System.currentTimeMillis().toInt(), notification)
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES,O) {
            val watchChannel = NotificationChannel(
                CHANNEL_ID,
                "后台守护通知 (前台服务)",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持后台服务常驻运行的常驻通知"
            }

            val alertChannel = NotificationChannel(
                ALERT_CHANNEL_ID,
                "签到状态提醒",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "签到成功、失败或检测到新签到时的警报提醒"
                enableLights(true)
                enableVibration(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }

            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(watchChannel)
            notificationManager.createNotificationChannel(alertChannel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        isRunning.value = false
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}
