package com.xmu.rollcall.ui

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.xmu.rollcall.data.Account
import com.xmu.rollcall.data.AccountStore
import com.xmu.rollcall.net.XmuLoginClient
import com.xmu.rollcall.service.AnswerOutcome
import com.xmu.rollcall.service.RollcallRecord
import com.xmu.rollcall.service.RollcallService
import com.xmu.rollcall.service.WatchService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Premium Dark Theme Palette
val DeepSlateBg = Color(0xFF0F172A)
val CardBg = Color(0x1AFFFFFF)
val CardBorder = Color(0x1FADF3FF) // Subtle light border
val ElectricBlue = Color(0xFF3B82F6)
val SoftCyan = Color(0xFF06B6D4)
val SuccessGreen = Color(0xFF10B981)
val ExpiredGray = Color(0xFF64748B)
val ErrorRed = Color(0xFFEF4444)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(accountStore: AccountStore) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // State flows from Background Service
    val isServiceRunning by WatchService.isRunning.collectAsState()
    val watchLogs by WatchService.logs.collectAsState()
    val isAutoSubmitEnabled by WatchService.autoSubmit.collectAsState()
    val watchIntervalMinutes by WatchService.pollIntervalMinutes.collectAsState()

    // Local UI State
    var accountsList by remember { mutableStateOf(accountStore.getAccounts()) }
    var activeAccount by remember { mutableStateOf(accountStore.getActiveAccount()) }
    
    var isLoading by remember { mutableStateOf(false) }
    var loadingMessage by remember { mutableStateOf("") }
    
    var activeRollcalls by remember { mutableStateOf<List<RollcallRecord>>(emptyList()) }
    var answerOutcomes by remember { mutableStateOf<List<AnswerOutcome>>(emptyList()) }
    var checkTimeStr by remember { mutableStateOf("") }

    // Dialog state
    var showAddAccountDialog by remember { mutableStateOf(false) }
    var showSwitchAccountDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        WatchService.loadPersistedSettings(context)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(DeepSlateBg, Color(0xFF1E293B))
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            // Header Title
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "XMU 自动签到助手",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        text = "TronClass 签到与雷达定位工具",
                        fontSize = 12.sp,
                        color = Color(0xFF94A3B8)
                    )
                }

                // Service Status Indicator
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(if (isServiceRunning) SuccessGreen.copy(alpha = 0.2f) else ExpiredGray.copy(alpha = 0.2f))
                        .border(
                            1.dp,
                            if (isServiceRunning) SuccessGreen else ExpiredGray,
                            RoundedCornerShape(20.dp)
                        )
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(if (isServiceRunning) SuccessGreen else ExpiredGray)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isServiceRunning) "后台守护中" else "未开启后台",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isServiceRunning) SuccessGreen else Color.White
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Main scrollable container
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Card 1: Active Account Settings
                item {
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Default.AccountCircle,
                                        contentDescription = "User",
                                        tint = ElectricBlue,
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "账号设置",
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White,
                                        fontSize = 16.sp
                                    )
                                }

                                Row {
                                    TextButton(onClick = { showSwitchAccountDialog = true }) {
                                        Text(text = "切换", color = SoftCyan)
                                    }
                                    Spacer(modifier = Modifier.width(4.dp))
                                    TextButton(onClick = { showAddAccountDialog = true }) {
                                        Text(text = "添加", color = ElectricBlue)
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            if (activeAccount != null) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text(
                                            text = activeAccount!!.name,
                                            fontSize = 18.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = Color.White
                                        )
                                        Text(
                                            text = "学号: ${activeAccount!!.username}",
                                            fontSize = 13.sp,
                                            color = Color(0xFF64748B)
                                        )
                                    }

                                    // Clear Cookie Cache
                                    Button(
                                        onClick = {
                                            scope.launch {
                                                accountStore.updateCookies(activeAccount!!.username, emptyMap())
                                                activeAccount = accountStore.getActiveAccount()
                                                Toast.makeText(context, "登录缓存已清理，下次将重新登录", Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0x33EF4444)),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.height(32.dp)
                                    ) {
                                        Text("清理缓存", color = ErrorRed, fontSize = 12.sp)
                                    }
                                }
                            } else {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 12.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "尚未配置账号，请点击“添加”输入学号密码",
                                        color = Color(0xFF64748B),
                                        fontSize = 13.sp,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }
                    }
                }

                // Card 2: Manual Check and Sign-In Operations
                item {
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "即时操作",
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                fontSize = 16.sp
                            )
                            Spacer(modifier = Modifier.height(12.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                // Check Rollcalls button
                                Button(
                                    onClick = {
                                        if (activeAccount == null) {
                                            Toast.makeText(context, "请先添加账号", Toast.LENGTH_SHORT).show()
                                            return@Button
                                        }
                                        isLoading = true
                                        loadingMessage = "正在查询签到..."
                                        scope.launch {
                                            try {
                                                val client = XmuLoginClient()
                                                if (activeAccount!!.serializedCookies.isNotEmpty()) {
                                                    client.restoreCookies(activeAccount!!.serializedCookies)
                                                }
                                                val service = RollcallService(client, activeAccount!!.username)
                                                
                                                val batchResult = withContext(Dispatchers.IO) {
                                                    try {
                                                        service.inspectActiveRollcalls()
                                                    } catch (e: Exception) {
                                                        // retry login once
                                                        if (client.loginTronClass(activeAccount!!.username, activeAccount!!.password)) {
                                                            accountStore.updateCookies(activeAccount!!.username, client.getSerializedCookies())
                                                            val retryService = RollcallService(client, activeAccount!!.username)
                                                            retryService.inspectActiveRollcalls()
                                                        } else {
                                                            throw e
                                                        }
                                                    }
                                                }
                                                activeRollcalls = batchResult.rollcalls
                                                answerOutcomes = batchResult.outcomes
                                                checkTimeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                                                Toast.makeText(context, "查询成功，发现 ${activeRollcalls.size} 个签到", Toast.LENGTH_SHORT).show()
                                            } catch (e: Exception) {
                                                Toast.makeText(context, "查询失败: ${e.message}", Toast.LENGTH_LONG).show()
                                            } finally {
                                                isLoading = false
                                            }
                                        }
                                    },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Icon(Icons.Default.Search, contentDescription = "Query")
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("查询签到", fontWeight = FontWeight.SemiBold)
                                }

                                // Auto Answer button
                                Button(
                                    onClick = {
                                        if (activeAccount == null) {
                                            Toast.makeText(context, "请先添加账号", Toast.LENGTH_SHORT).show()
                                            return@Button
                                        }
                                        isLoading = true
                                        loadingMessage = "正在执行签到提交..."
                                        scope.launch {
                                            try {
                                                val client = XmuLoginClient()
                                                if (activeAccount!!.serializedCookies.isNotEmpty()) {
                                                    client.restoreCookies(activeAccount!!.serializedCookies)
                                                }
                                                val service = RollcallService(client, activeAccount!!.username)
                                                
                                                val batchResult = withContext(Dispatchers.IO) {
                                                    try {
                                                        service.answerActiveRollcalls()
                                                    } catch (e: Exception) {
                                                        if (client.loginTronClass(activeAccount!!.username, activeAccount!!.password)) {
                                                            accountStore.updateCookies(activeAccount!!.username, client.getSerializedCookies())
                                                            val retryService = RollcallService(client, activeAccount!!.username)
                                                            retryService.answerActiveRollcalls()
                                                        } else {
                                                            throw e
                                                        }
                                                    }
                                                }
                                                activeRollcalls = batchResult.rollcalls
                                                answerOutcomes = batchResult.outcomes
                                                checkTimeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                                                
                                                val successCount = answerOutcomes.count {
                                                    it.action == "answered" || it.action == "already_answered"
                                                }
                                                Toast.makeText(context, "签到处理完毕！已完成 $successCount 个", Toast.LENGTH_LONG).show()
                                            } catch (e: Exception) {
                                                Toast.makeText(context, "执行失败: ${e.message}", Toast.LENGTH_LONG).show()
                                            } finally {
                                                isLoading = false
                                            }
                                        }
                                    },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(containerColor = SoftCyan),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Icon(Icons.Default.CheckCircle, contentDescription = "Sign in")
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("一键签到", fontWeight = FontWeight.SemiBold)
                                }
                            }

                            // Show queried rollcalls if any
                            if (activeRollcalls.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(16.dp))
                                Divider(color = Color(0x1AFFFFFF))
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("签到列表 (${checkTimeStr} 查询):", fontSize = 13.sp, color = Color(0xFF94A3B8))
                                }
                                Spacer(modifier = Modifier.height(8.dp))

                                activeRollcalls.forEachIndexed { idx, rollcall ->
                                    val outcome = answerOutcomes.getOrNull(idx)
                                    RollcallItemView(rollcall = rollcall, outcome = outcome)
                                    Spacer(modifier = Modifier.height(8.dp))
                                }
                            }
                        }
                    }
                }

                // Card 3: Background Polling Guard Settings
                item {
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "后台自动扫描守候",
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                fontSize = 16.sp
                            )
                            Spacer(modifier = Modifier.height(12.dp))

                            // Start/Stop Toggle Button
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("开启守护线程轮询", color = Color.White, fontSize = 14.sp)
                                Switch(
                                    checked = isServiceRunning,
                                    onCheckedChange = { start ->
                                        if (activeAccount == null && start) {
                                            Toast.makeText(context, "请先添加账号", Toast.LENGTH_SHORT).show()
                                            return@Switch
                                        }
                                        val intent = Intent(context, WatchService::class.java).apply {
                                            action = if (start) WatchService.ACTION_START else WatchService.ACTION_STOP
                                        }
                                        context.startForegroundService(intent)
                                    },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = SuccessGreen,
                                        checkedTrackColor = SuccessGreen.copy(alpha = 0.5f)
                                    )
                                )
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            // Auto Submit Toggle
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("检测到签到时自动提交", color = Color.White, fontSize = 14.sp)
                                Switch(
                                    checked = isAutoSubmitEnabled,
                                    onCheckedChange = { WatchService.persistAutoSubmit(context, it) },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = ElectricBlue,
                                        checkedTrackColor = ElectricBlue.copy(alpha = 0.5f)
                                    )
                                )
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            // Polling Interval slider
                            Text(
                                text = "轮询扫描间隔: $watchIntervalMinutes 分钟",
                                color = Color(0xFF94A3B8),
                                fontSize = 13.sp
                            )
                            Slider(
                                value = watchIntervalMinutes.toFloat(),
                                onValueChange = { WatchService.persistPollInterval(context, it.toInt()) },
                                valueRange = 1f..15f,
                                steps = 14,
                                colors = SliderDefaults.colors(
                                    thumbColor = ElectricBlue,
                                    activeTrackColor = ElectricBlue
                                )
                            )
                            Text(
                                text = "* 守护进程会启用 WakeLock 及通知常驻；强省电 ROM 仍建议设为后台无限制。",
                                fontSize = 10.sp,
                                color = Color(0xFF64748B)
                            )
                        }
                    }
                }

                // Card 4: Guardian Logs Console
                item {
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "守护日志控制台",
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    fontSize = 16.sp
                                )

                                TextButton(
                                    onClick = { WatchService.logs.value = emptyList() },
                                    contentPadding = PaddingValues(0.dp)
                                ) {
                                    Text("清空日志", color = Color(0xFF94A3B8), fontSize = 12.sp)
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            // Console Log Area
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(140.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFF020617))
                                    .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(8.dp))
                                    .padding(8.dp)
                            ) {
                                if (watchLogs.isEmpty()) {
                                    Text(
                                        text = "暂无守护日志。开启后台扫描后，日志将在此实时滚动更新...",
                                        fontSize = 12.sp,
                                        color = Color(0xFF475569),
                                        fontFamily = FontFamily.Monospace
                                    )
                                } else {
                                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                                        items(watchLogs) { log ->
                                            Text(
                                                text = log,
                                                fontSize = 11.sp,
                                                color = when {
                                                    log.contains("成功") -> SuccessGreen
                                                    log.contains("失败") || log.contains("异常") || log.contains("出错") -> ErrorRed
                                                    log.contains("扫描") -> SoftCyan
                                                    else -> Color(0xFFE2E8F0)
                                                },
                                                fontFamily = FontFamily.Monospace,
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Loading Mask Overlay
        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.6f))
                    .clickable(enabled = false) {},
                contentAlignment = Alignment.Center
            ) {
                GlassCard(
                    modifier = Modifier
                        .width(220.dp)
                        .wrapContentHeight()
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator(color = ElectricBlue)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = loadingMessage,
                            color = Color.White,
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }

    // Add Account Dialog
    if (showAddAccountDialog) {
        AddAccountDialog(
            onDismiss = { showAddAccountDialog = false },
            onAccountAdded = { user, pwd, remark ->
                showAddAccountDialog = false
                isLoading = true
                loadingMessage = "正在验证统一身份认证..."
                
                scope.launch {
                    try {
                        val client = XmuLoginClient()
                        val success = withContext(Dispatchers.IO) {
                            client.loginTronClass(user, pwd)
                        }
                        if (success) {
                            val service = RollcallService(client, user)
                            val profile = withContext(Dispatchers.IO) { service.fetchProfile() }
                            val fullName = profile?.get("name") as? String ?: remark.ifEmpty { "账号" }

                            val account = Account(
                                username = user,
                                password = pwd,
                                name = fullName,
                                serializedCookies = client.getSerializedCookies()
                            )
                            accountStore.addOrUpdateAccount(account)
                            accountsList = accountStore.getAccounts()
                            activeAccount = accountStore.getActiveAccount()
                            Toast.makeText(context, "账号添加成功！名字: $fullName", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "登录验证失败，请确认学号及密码", Toast.LENGTH_LONG).show()
                        }
                    } catch (e: Exception) {
                        Toast.makeText(context, "登录失败: ${e.message}", Toast.LENGTH_LONG).show()
                    } finally {
                        isLoading = false
                    }
                }
            }
        )
    }

    // Switch Account Dialog
    if (showSwitchAccountDialog) {
        SwitchAccountDialog(
            accounts = accountsList,
            currentActive = activeAccount,
            onDismiss = { showSwitchAccountDialog = false },
            onSelect = { acc ->
                accountStore.setActiveUsername(acc.username)
                activeAccount = accountStore.getActiveAccount()
                showSwitchAccountDialog = false
                Toast.makeText(context, "已切换活跃账号至: ${acc.name}", Toast.LENGTH_SHORT).show()
            },
            onDelete = { acc ->
                accountStore.deleteAccount(acc.username)
                accountsList = accountStore.getAccounts()
                activeAccount = accountStore.getActiveAccount()
                Toast.makeText(context, "账号 [${acc.name}] 已删除", Toast.LENGTH_SHORT).show()
            }
        )
    }
}

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(CardBg)
            .border(1.dp, CardBorder, RoundedCornerShape(16.dp))
    ) {
        content()
    }
}

@Composable
fun RollcallItemView(rollcall: RollcallRecord, outcome: AnswerOutcome?) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0x0AFFFFFF))
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = rollcall.course_title,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = rollcall.typeLabel,
                        fontSize = 11.sp,
                        color = SoftCyan
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "ID: ${rollcall.rollcall_id}",
                        fontSize = 10.sp,
                        color = Color(0xFF64748B)
                    )
                }
            }

            // Outcome badge
            val (statusText, statusColor) = when {
                rollcall.is_expired -> "已过期" to ExpiredGray
                rollcall.isAnswered -> "已完成" to SuccessGreen
                outcome?.action == "answered" -> "成功" to SuccessGreen
                outcome?.action == "already_answered" -> "已完成" to SuccessGreen
                outcome?.action == "detected" -> "待签" to SoftCyan
                outcome != null && !outcome.success -> "失败" to ErrorRed
                else -> "待签" to SoftCyan
            }

            Column(horizontalAlignment = Alignment.End) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(statusColor.copy(alpha = 0.15f))
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = statusText,
                        color = statusColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                if (outcome != null) {
                    Text(
                        text = when {
                            outcome.numberCode != null -> "码:${outcome.numberCode}"
                            outcome.latitude != null && outcome.longitude != null -> String.format(Locale.US, "%.3f,%.3f", outcome.latitude, outcome.longitude)
                            else -> outcome.message.take(12)
                        },
                        color = Color(0xFF94A3B8),
                        fontSize = 9.sp,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddAccountDialog(
    onDismiss: () -> Unit,
    onAccountAdded: (user: String, pwd: String, remark: String) -> Unit
) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var remark by remember { mutableStateOf("") }

    Dialog(onDismissRequest = onDismiss) {
        GlassCard(modifier = Modifier.width(300.dp)) {
            Column(
                modifier = Modifier
                    .background(Color(0xFF1E293B))
                    .padding(20.dp)
            ) {
                Text(
                    text = "添加 XMU 统一认证账号",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
                Spacer(modifier = Modifier.height(16.dp))

                TextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("学号") },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color(0x33000000),
                        unfocusedContainerColor = Color(0x33000000),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))

                TextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("密码") },
                    visualTransformation = PasswordVisualTransformation(),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color(0x33000000),
                        unfocusedContainerColor = Color(0x33000000),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))

                TextField(
                    value = remark,
                    onValueChange = { remark = it },
                    label = { Text("备注名 (可选)") },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color(0x33000000),
                        unfocusedContainerColor = Color(0x33000000),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(20.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("取消", color = Color(0xFF94A3B8))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (username.isNotEmpty() && password.isNotEmpty()) {
                                onAccountAdded(username, password, remark)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue)
                    ) {
                        Text("验证并保存")
                    }
                }
            }
        }
    }
}

@Composable
fun SwitchAccountDialog(
    accounts: List<Account>,
    currentActive: Account?,
    onDismiss: () -> Unit,
    onSelect: (Account) -> Unit,
    onDelete: (Account) -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        GlassCard(modifier = Modifier.width(300.dp)) {
            Column(
                modifier = Modifier
                    .background(Color(0xFF1E293B))
                    .padding(20.dp)
            ) {
                Text(
                    text = "选择活跃账号",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
                Spacer(modifier = Modifier.height(16.dp))

                if (accounts.isEmpty()) {
                    Text("暂无保存的账号", color = Color(0xFF64748B), fontSize = 13.sp)
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 200.dp)) {
                        items(accounts) { account ->
                            val isActive = account.username == currentActive?.username
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isActive) Color(0x1A3B82F6) else Color.Transparent)
                                    .clickable { onSelect(account) }
                                    .padding(8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = account.name,
                                        color = Color.White,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 14.sp
                                    )
                                    Text(
                                        text = account.username,
                                        color = Color(0xFF94A3B8),
                                        fontSize = 12.sp
                                    )
                                }

                                IconButton(onClick = { onDelete(account) }) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = "Delete",
                                        tint = ErrorRed,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("关闭", color = Color.White)
                    }
                }
            }
        }
    }
}
