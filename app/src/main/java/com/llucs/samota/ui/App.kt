package com.llucs.samota.ui

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import com.llucs.samota.ui.screens.DownloadScreen
import com.llucs.samota.ui.screens.DownloadViewModel

@Composable
fun App() {
    val vm: DownloadViewModel = viewModel()
    DownloadScreen(vm = vm)
}
