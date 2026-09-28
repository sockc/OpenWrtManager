package com.openwrtmanager.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.viewmodel.compose.viewModel
import com.openwrtmanager.mobile.ui.MainViewModel
import com.openwrtmanager.mobile.ui.screens.RootScreen
import com.openwrtmanager.mobile.ui.theme.OpenWrtManagerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            OpenWrtManagerTheme {
                val vm: MainViewModel = viewModel()
                RootScreen(vm)
            }
        }
    }
}
