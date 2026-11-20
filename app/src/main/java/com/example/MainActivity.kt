package com.example

import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.remote.ui.RemoteViewModel
import com.example.remote.ui.YvTvRemoteApp
import com.example.ui.theme.YvTvRemoteTheme
import com.example.ui.theme.isThemeDark
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      val remoteViewModel: RemoteViewModel = viewModel()
      val initialThemeSelection =
        remember(remoteViewModel) {
          val prefs = remoteViewModel.uiState.value.uiPreferences
          prefs.themeMode to prefs.accentTheme
        }
      val themeSelectionFlow =
        remember(remoteViewModel) {
          remoteViewModel.uiState
            .map { it.uiPreferences.themeMode to it.uiPreferences.accentTheme }
            .distinctUntilChanged()
        }
      val themeSelection by
        themeSelectionFlow.collectAsStateWithLifecycle(initialValue = initialThemeSelection)
      val (themeMode, accentTheme) = themeSelection
      val useDark = isThemeDark(themeMode)

      LaunchedEffect(useDark) {
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
        themeMode = themeMode,
        accentTheme = accentTheme,
      ) {
        YvTvRemoteApp(viewModel = remoteViewModel)
      }
    }
  }
}
