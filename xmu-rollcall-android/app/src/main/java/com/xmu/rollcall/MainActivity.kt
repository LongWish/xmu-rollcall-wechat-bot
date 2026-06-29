package com.xmu.rollcall

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.xmu.rollcall.data.AccountStore
import com.xmu.rollcall.ui.DashboardScreen

class MainActivity : ComponentActivity() {

    private lateinit var accountStore: AccountStore

    // Permission request launcher for notifications on Android 13+
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            Toast.makeText(this, "通知权限已启用，后台签到将在状态栏显示提醒", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "未授予通知权限！后台签到提醒可能无法显示", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Initialize Encrypted Account Store
        accountStore = AccountStore.createEncrypted(this)

        // Request notification permission if running on Android 13 (API 33) or above
        checkNotificationPermission()

        setContent {
            DashboardScreen(accountStore = accountStore)
        }
    }

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
