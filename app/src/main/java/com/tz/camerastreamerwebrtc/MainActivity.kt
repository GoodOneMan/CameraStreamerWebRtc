package com.tz.camerastreamerwebrtc

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import org.webrtc.SurfaceViewRenderer

class MainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* проверка перед стартом — см. кнопку Start */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Запрашиваем камеру, микрофон и (на Android 13+) уведомления
        val permissions = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(permissions.toTypedArray())

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFFEFEFEF)
                ) {
                    StreamScreen()
                }
            }
        }
    }
}

@Composable
fun StreamScreen(vm: StreamViewModel = viewModel()) {
    val context = LocalContext.current
    var serverUrl by remember { mutableStateOf("ws://192.168.1.172:5000/ws") }
    var cameraFacing by remember { mutableStateOf(CameraFacing.BACK) }
    val status by vm.status.collectAsState()
    val isStreaming by vm.isStreaming.collectAsState()

    Box(modifier = Modifier.fillMaxSize()) {
        // === Видео-превью ===
        AndroidView(
            factory = { ctx ->
                SurfaceViewRenderer(ctx).also { renderer ->
                    renderer.setZOrderMediaOverlay(true)
                    renderer.setZOrderOnTop(false)
                    vm.attachLocalRenderer(renderer)
                }
            },
            onRelease = { renderer ->
                vm.detachLocalRenderer(renderer)
                try { renderer.release() } catch (_: Exception) {}
            },
            modifier = Modifier.fillMaxSize()
        )

        // === UI поверх видео ===
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Surface(
                color = Color(0xE6FFFFFF),
                shape = MaterialTheme.shapes.medium,
                tonalElevation = 4.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "WebRTC Camera Streamer",
                        style = MaterialTheme.typography.headlineSmall,
                        color = Color.Black
                    )
                    Spacer(Modifier.height(12.dp))

                    Text(
                        "Signaling URL (ws://<windows-ip>:5000/ws):",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.DarkGray
                    )
                    BasicTextField(
                        value = serverUrl,
                        onValueChange = { serverUrl = it },
                        textStyle = androidx.compose.ui.text.TextStyle(color = Color.Black),
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color.White)
                            .border(1.dp, Color.Gray)
                            .padding(8.dp)
                    )
                    Spacer(Modifier.height(12.dp))

                    // Выбор камеры
                    Text(
                        "Camera:",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.DarkGray
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier
                                .selectable(
                                    selected = cameraFacing == CameraFacing.BACK,
                                    enabled = !isStreaming,
                                    onClick = { cameraFacing = CameraFacing.BACK }
                                )
                                .padding(end = 16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = cameraFacing == CameraFacing.BACK,
                                onClick = { cameraFacing = CameraFacing.BACK },
                                enabled = !isStreaming
                            )
                            Text("Back", color = Color.Black)
                        }
                        Row(
                            modifier = Modifier
                                .selectable(
                                    selected = cameraFacing == CameraFacing.FRONT,
                                    enabled = !isStreaming,
                                    onClick = { cameraFacing = CameraFacing.FRONT }
                                )
                                .padding(end = 16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = cameraFacing == CameraFacing.FRONT,
                                onClick = { cameraFacing = CameraFacing.FRONT },
                                enabled = !isStreaming
                            )
                            Text("Front", color = Color.Black)
                        }
                    }
                    Spacer(Modifier.height(12.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                val hasCamera = ContextCompat.checkSelfPermission(
                                    context, Manifest.permission.CAMERA
                                ) == PackageManager.PERMISSION_GRANTED
                                val hasMic = ContextCompat.checkSelfPermission(
                                    context, Manifest.permission.RECORD_AUDIO
                                ) == PackageManager.PERMISSION_GRANTED
                                if (hasCamera && hasMic) vm.start(serverUrl, cameraFacing)
                                else vm.reportPermissionError()
                            },
                            enabled = !isStreaming,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF1976D2),
                                contentColor = Color.White
                            )
                        ) { Text("Start") }
                        Button(
                            onClick = { vm.stop() },
                            enabled = isStreaming,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFD32F2F),
                                contentColor = Color.White
                            )
                        ) { Text("Stop") }
                    }
                    Spacer(Modifier.height(8.dp))

                    Text(
                        "Status: $status",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.Black
                    )
                }
            }
        }
    }
}