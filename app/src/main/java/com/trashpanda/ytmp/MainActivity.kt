package com.trashpanda.ytmp

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private var isServerRunning = mutableStateOf(false)

    val requestPermissionLauncher =
    registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* ignore result */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(if (isServerRunning.value) "Server is running" else "Server is stopped")

                    Spacer(modifier = Modifier.height(16.dp))

                    Button(onClick = {
                        lifecycleScope.launch {
                            val granted = checkAndRequestNotificationPermission()
                            if (!granted) return@launch
                            startForegroundService(Intent(this@MainActivity, KtorServerService::class.java))
                            isServerRunning.value = true
                        }
                    }, enabled = !isServerRunning.value) {
                        Text("Start Server")
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Button(onClick = {
                        stopService(Intent(this@MainActivity, KtorServerService::class.java))
                        isServerRunning.value = false
                    }, enabled = isServerRunning.value) {
                        Text("Stop Server")
                    }
                }
            }
        }
    }
    private fun checkAndRequestNotificationPermission(): Boolean {
        // Already granted
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        ) return true

        // Ask for permission
        if (ActivityCompat.shouldShowRequestPermissionRationale(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            )
        ) {
            showRationaleAndRetry()
            return false
        }

        // First time or permanently denied
        requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        return false
    }

    private fun showRationaleAndRetry() {
        Log.d("MainActivity", "Showing rationale for notification permission")
        AlertDialog.Builder(this)
            .setTitle("Permission Needed")
            .setMessage("You realy fucking need this shit to run the server!")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Grant") { _, _ ->
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            .show()
    }
}
