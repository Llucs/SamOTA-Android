package com.llucs.samota.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlin.math.roundToInt
import androidx.compose.foundation.text.KeyboardOptions

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadScreen(vm: DownloadViewModel) {
    val state = vm.state
    val scroll = rememberScrollState()
    val ctx = LocalContext.current

    val needsNotifications = Build.VERSION.SDK_INT >= 33
    val hasNotificationsPermission = !needsNotifications || ContextCompat.checkSelfPermission(
        ctx,
        Manifest.permission.POST_NOTIFICATIONS
    ) == PackageManager.PERMISSION_GRANTED

    val notifPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) vm.download() else vm.setUserMessage("Permita notificações para baixar em segundo plano")
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("SamOTA") }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize()
                .verticalScroll(scroll),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Dados do aparelho")
                    OutlinedTextField(
                        value = state.model,
                        onValueChange = vm::setModel,
                        label = { Text("Modelo (SM-)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = state.firmware,
                        onValueChange = vm::setFirmware,
                        label = { Text("Firmware (PDA/CSC/PHONE)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = state.csc,
                        onValueChange = vm::setCsc,
                        label = { Text("CSC") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = state.imei,
                        onValueChange = vm::setImei,
                        label = { Text("IMEI (15+ dígitos)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Divider()

                    Text("Download")
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Conexões: ${state.connections}")
                        Slider(
                            value = state.connections.toFloat(),
                            onValueChange = { vm.setConnections(it.roundToInt().coerceIn(1, 32)) },
                            valueRange = 1f..32f,
                            steps = 30,
                            modifier = Modifier.weight(1f).padding(start = 12.dp)
                        )
                    }

                    val speedLabel = if (state.maxSpeedMiB <= 0.0) "Sem limite" else "${state.maxSpeedMiB.roundToInt()} MiB/s"
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Velocidade: $speedLabel")
                    }
                    Slider(
                        value = state.maxSpeedMiB.toFloat().coerceIn(0f, 200f),
                        onValueChange = { v ->
                            val vv = v.roundToInt().toDouble()
                            vm.setMaxSpeed(if (vv <= 0.0) 0.0 else vv)
                        },
                        valueRange = 0f..200f,
                        steps = 199,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Decriptar (.enc2/.enc4)")
                        Switch(
                            checked = state.decrypt,
                            onCheckedChange = vm::setDecrypt
                        )
                    }

                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = vm::check, enabled = !state.busy) { Text("Verificar") }
                        Button(
                            onClick = {
                                if (hasNotificationsPermission) vm.download()
                                else notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            },
                            enabled = !state.busy
                        ) { Text("Baixar") }
                        Button(onClick = vm::cancel, enabled = state.busy) { Text("Cancelar") }
                    }

                    if (!hasNotificationsPermission) {
                        Text("Para baixar em segundo plano no Android 13+, permita notificações.")
                    }
                }
            }

            if (state.totalBytes > 0L) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val progress = (state.downloadedBytes.toDouble() / state.totalBytes.toDouble()).coerceIn(0.0, 1.0)
                        Text("Progresso")
                        LinearProgressIndicator(progress = progress.toFloat(), modifier = Modifier.fillMaxWidth())
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("${DownloadViewModel.formatBytes(state.downloadedBytes)} / ${DownloadViewModel.formatBytes(state.totalBytes)}")
                            Text(DownloadViewModel.formatSpeed(state.bytesPerSecond))
                        }
                        Text("Etapa: ${state.stage}")
                    }
                }
            }

            if (!state.message.isNullOrBlank() || !state.lastOutput.isNullOrBlank()) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.message?.let { Text(it) }
                        state.lastOutput?.let {
                            Text(it, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
        }
    }
}