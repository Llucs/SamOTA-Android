package com.llucs.samota

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.llucs.samota.ui.App
import com.llucs.samota.ui.theme.SamOTATheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SamOTATheme {
                App()
            }
        }
    }
}
