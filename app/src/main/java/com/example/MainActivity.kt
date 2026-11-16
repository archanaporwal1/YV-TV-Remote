package com.example

import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.remote.ui.RemoteViewModel
import com.example.remote.ui.YvTvRemoteApp
import com.example.ui.theme.YvTvRemoteTheme
import com.example.ui.theme.isThemeDark

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      val remoteViewModel: RemoteViewModel = viewModel()
      val uiState by remoteViewModel.uiState.collectAsStateWithLifecycle()
      val useDark = isThemeDark(uiState.uiPreferences.themeMode)

      SideEffect {
        val barStyle =
          if (useDark) {
            SystemBarStyle.dark(AndroidColor.TRANSPARENT)
          } else {
            SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
          }
        enableEdgeToEdge(
          statusBarStyle = barStyle,
          navigationBarStyle = barStyle,
        )
      }

      YvTvRemoteTheme(
        themeMode = uiState.uiPreferences.themeMode,
        accentTheme = uiState.uiPreferences.accentTheme,
      ) {
        YvTvRemoteApp(viewModel = remoteViewModel)
      }
    }
  }
}
