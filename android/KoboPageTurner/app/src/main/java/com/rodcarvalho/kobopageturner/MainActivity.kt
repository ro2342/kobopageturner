package com.rodcarvalho.kobopageturner

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val events = mutableStateListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ble = BlePeripheralService(applicationContext) { line ->
            events.add(0, line)
            while (events.size > 60) {
                events.removeAt(events.size - 1)
            }
        }
        setContent {
            KoboPageTurnerApp(ble, events)
        }
    }
}

@Composable
fun KoboPageTurnerApp(ble: BlePeripheralService, events: List<String>) {
    var statusColor by remember { mutableStateOf(Color.Gray) }
    var statusDetail by remember { mutableStateOf("Checking Bluetooth…") }
    var showInfo by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun startBle() {
        if (!ble.isSupported()) {
            statusColor = Color.Red
            statusDetail = "This phone's Bluetooth doesn't support peripheral/GATT-server mode — this app can't work here."
            return
        }
        statusColor = Color(0xFFFFA500)
        statusDetail = "Starting…"
        ble.start { success, error ->
            if (success) {
                statusColor = Color(0xFFFFA500)
                statusDetail = "Advertising as a keyboard. Open your Kobo's Bluetooth pairing screen and connect to this phone."
            } else {
                statusColor = Color.Red
                statusDetail = "Failed to start Bluetooth advertising: " + (error ?: "unknown error")
            }
        }
    }

    ble.onConnectionStateChanged = { hasSubscriber ->
        if (hasSubscriber) {
            statusColor = Color(0xFF2E7D32)
            statusDetail = "Connected — button presses will turn pages."
        } else {
            statusColor = Color(0xFFFFA500)
            statusDetail = "Advertising as a keyboard. Open your Kobo's Bluetooth pairing screen and connect to this phone."
        }
    }

    val permissions = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf()
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.isEmpty() || results.values.all { it }) {
            startBle()
        } else {
            statusColor = Color.Red
            statusDetail = "Bluetooth permission denied — grant it in Settings to use this app."
        }
    }

    LaunchedEffect(Unit) {
        if (permissions.isEmpty()) {
            startBle()
        } else {
            permissionLauncher.launch(permissions)
        }
    }

    MaterialTheme {
        val density = LocalDensity.current
        val swipeThresholdPx = with(density) { 60.dp.toPx() }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .pointerInput(Unit) {
                    var totalDrag = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { totalDrag = 0f },
                        onDragEnd = {
                            if (totalDrag > swipeThresholdPx) {
                                scope.launch { ble.sendKey(HidKeyCode.LEFT_ARROW) }
                            } else if (totalDrag < -swipeThresholdPx) {
                                scope.launch { ble.sendKey(HidKeyCode.RIGHT_ARROW) }
                            }
                        },
                    ) { change, dragAmount ->
                        totalDrag += dragAmount
                        change.consume()
                    }
                }
        ) {
            Row(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable { scope.launch { ble.sendKey(HidKeyCode.LEFT_ARROW) } }
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable { scope.launch { ble.sendKey(HidKeyCode.RIGHT_ARROW) } }
                )
            }

            Box(
                Modifier
                    .align(Alignment.Center)
                    .fillMaxHeight()
                    .width(1.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant)
            )

            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 20.dp)
                    .size(12.dp)
                    .background(statusColor, CircleShape)
            )

            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(20.dp))
                    .padding(4.dp)
            ) {
                IconButton(onClick = { showInfo = true }) { Text("ℹ") }
                IconButton(onClick = {
                    ble.stop()
                    startBle()
                }) { Text("⚙") }
            }

            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 24.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text("Tap or swipe to turn pages", fontSize = 14.sp)
            }
        }

        if (showInfo) {
            AlertDialog(
                onDismissRequest = { showInfo = false },
                confirmButton = {
                    TextButton(onClick = { showInfo = false }) { Text("Close") }
                },
                title = { Text("Kobo Page Turner") },
                text = {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(statusDetail)
                        Text(
                            "\nRecent Bluetooth activity (newest first) — what a connecting device actually did:",
                            fontSize = 12.sp,
                        )
                        if (events.isEmpty()) {
                            Text("(nothing yet)", fontSize = 12.sp)
                        } else {
                            LazyColumn(modifier = Modifier.heightIn(max = 240.dp)) {
                                items(events) { line ->
                                    Text(line, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                },
            )
        }
    }
}
