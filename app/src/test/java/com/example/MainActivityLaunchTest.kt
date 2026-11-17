package com.example

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import com.example.remote.update.AppReleaseInfo
import com.example.remote.update.AppUpdateManager
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MainActivityLaunchTest {

  @get:Rule
  val composeTestRule = createAndroidComposeRule<MainActivity>()

  @Test
  fun launchMainActivityAndNavigateAllPrimaryScreensWithoutCrash() {
    composeTestRule.waitForIdle()

    // Hub screen renders cleanly
    composeTestRule.onNodeWithTag("open_remote_surface_button").assertIsDisplayed()
    composeTestRule.onNodeWithTag("hub_settings_button").assertIsDisplayed()

    // Scroll to Manual IP button in hub LazyColumn, open dialog, and dismiss cleanly
    composeTestRule.onNodeWithTag("hub_list")
      .performScrollToNode(hasTestTag("manual_ip_button"))
    composeTestRule.onNodeWithTag("manual_ip_button").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("manual_ip_input").assertIsDisplayed()
    composeTestRule.onNodeWithTag("manual_cancel_button").performClick()
    composeTestRule.waitForIdle()

    // Scroll back to header and open Settings Modal Sheet, scroll to App Updates card, and exercise controls
    composeTestRule.onNodeWithTag("hub_list")
      .performScrollToNode(hasTestTag("hub_settings_button"))
    composeTestRule.onNodeWithTag("hub_settings_button").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("settings_list")
      .performScrollToNode(hasTestTag("settings_check_updates_button"))
    composeTestRule.onNodeWithTag("settings_autoupdate_switch").assertIsDisplayed()
    composeTestRule.onNodeWithTag("settings_check_updates_button").performClick()
    composeTestRule.waitForIdle()

    // Close Settings Modal Sheet cleanly
    composeTestRule.onNodeWithTag("settings_list")
      .performScrollToNode(hasTestTag("settings_close_button"))
    composeTestRule.onNodeWithTag("settings_close_button").performClick()
    composeTestRule.waitForIdle()

    // Switch to Remote Control surface
    composeTestRule.onNodeWithTag("open_remote_surface_button").performClick()
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag("btn_power").assertIsDisplayed()
    composeTestRule.onNodeWithTag("dpad_center").assertIsDisplayed()

    // Scroll to Quick-Launch Streaming OTT icons
    composeTestRule.onNodeWithTag("shortcut_youtube").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithTag("shortcut_jiotv").performScrollTo().assertIsDisplayed()

    // Open Add OTT App dialog from remote grid -> 1-tap add Spotify
    composeTestRule.onNodeWithTag("remote_add_ott_button").performScrollTo().performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("quick_add_ott_spotify").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("shortcut_spotify").performScrollTo().assertIsDisplayed()

    // Add a custom OTT app via form
    composeTestRule.onNodeWithTag("remote_add_ott_button").performScrollTo().performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("ott_name_input").performTextInput("Tubi TV")
    composeTestRule.onNodeWithTag("ott_link_input").performTextInput("tubitv.com")
    composeTestRule.onNodeWithTag("ott_save_button").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithText("Tubi TV").performScrollTo().assertIsDisplayed()

    // Switch to Touchpad and back to D-Pad
    composeTestRule.onNodeWithTag("mode_touchpad_button").performScrollTo().performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("touchpad_surface").assertIsDisplayed()

    composeTestRule.onNodeWithTag("mode_dpad_button").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("dpad_center").assertIsDisplayed()

    // Verify Microphone Voice Command bar & Voice Command Sheet
    composeTestRule.onNodeWithTag("btn_voice_mic").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithTag("btn_tv_voice_assist").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithTag("btn_voice_mic").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("voice_command_sheet").assertIsDisplayed()
    composeTestRule.onNodeWithTag("voice_command_sheet")
      .performScrollToNode(hasTestTag("voice_quick_prompts_row"))
    composeTestRule.onNodeWithTag("voice_chip_volume_up").performScrollTo().performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("voice_command_sheet")
      .performScrollToNode(hasTestTag("voice_transcript_card"))
    composeTestRule.onNodeWithTag("voice_executed_label").assertIsDisplayed()
    composeTestRule.onNodeWithTag("voice_command_sheet")
      .performScrollToNode(hasTestTag("voice_manual_command_input"))
    composeTestRule.onNodeWithTag("voice_manual_command_input").performTextInput("Please search for Inception")
    composeTestRule.onNodeWithTag("voice_manual_send_button").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("voice_command_sheet")
      .performScrollToNode(hasTestTag("voice_sheet_close_button"))
    composeTestRule.onNodeWithTag("voice_sheet_close_button").performClick()
    composeTestRule.waitForIdle()

    // Open Keyboard sheet, test Voice handoff from keyboard, and close cleanly
    composeTestRule.onNodeWithTag("btn_open_keyboard").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("keyboard_search_input").assertIsDisplayed()
    composeTestRule.onNodeWithTag("keyboard_voice_btn").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("voice_command_sheet").assertIsDisplayed()
    composeTestRule.onNodeWithTag("voice_sheet_close_button").performClick()
    composeTestRule.waitForIdle()

    // Open Customize UI sheet from Remote top bar, test Light/Dark theme, Minimalist preset & reset
    composeTestRule.onNodeWithTag("remote_customize_ui_button").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("customize_ui_list")
      .performScrollToNode(hasTestTag("pref_theme_mode_light"))
    composeTestRule.onNodeWithTag("pref_theme_mode_light").performClick()
    composeTestRule.onNodeWithTag("customize_ui_list")
      .performScrollToNode(hasTestTag("preset_minimal"))
    composeTestRule.onNodeWithTag("preset_minimal").performClick()
    composeTestRule.onNodeWithTag("customize_ui_list")
      .performScrollToNode(hasTestTag("accent_theme_emerald"))
    composeTestRule.onNodeWithTag("accent_theme_emerald").performClick()
    composeTestRule.onNodeWithTag("customize_ui_list")
      .performScrollToNode(hasTestTag("customize_ui_close_button"))
    composeTestRule.onNodeWithTag("customize_ui_close_button").performClick()
    composeTestRule.waitForIdle()

    // Minimalist hides OTT shortcuts & search bar while keeping D-Pad
    composeTestRule.onNodeWithTag("dpad_center").assertIsDisplayed()
    composeTestRule.onNodeWithTag("shortcut_youtube").assertDoesNotExist()

    // Re-open Customize UI, reset defaults cleanly, and delete Spotify custom OTT shortcut
    composeTestRule.onNodeWithTag("remote_customize_ui_button").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("customize_ui_list")
      .performScrollToNode(hasTestTag("pref_reset_ui_button"))
    composeTestRule.onNodeWithTag("pref_reset_ui_button").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("customize_ui_list")
      .performScrollToNode(hasTestTag("delete_custom_ott_spotify"))
    composeTestRule.onNodeWithTag("delete_custom_ott_spotify").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("delete_custom_ott_spotify").assertDoesNotExist()
    composeTestRule.onNodeWithTag("customize_ui_list")
      .performScrollToNode(hasTestTag("customize_ui_close_button"))
    composeTestRule.onNodeWithTag("customize_ui_close_button").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("shortcut_spotify").assertDoesNotExist()

    // Verify notification builder runs cleanly with monochrome smallIcon
    composeTestRule.activityRule.scenario.onActivity { activity ->
      AppUpdateManager.showUpdateNotification(
        activity,
        AppReleaseInfo(
          versionCode = 99,
          versionName = "9.9.9",
          tag = "v9.9.9",
          title = "Test Release",
          notes = "Release notes",
          apkUrl = "https://example.com/test.apk",
          releaseUrl = "https://example.com/release",
        ),
      )
    }
  }
}
