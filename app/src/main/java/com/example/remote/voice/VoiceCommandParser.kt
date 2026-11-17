package com.example.remote.voice

import com.example.proto.remote.RemoteKeyCode
import com.example.remote.data.TvAppShortcut
import com.example.remote.data.TvAppShortcuts
import com.example.remote.feedback.FeedbackCue

sealed class ParsedVoiceCommand {
  data class SendKey(
    val keyCode: RemoteKeyCode,
    val label: String,
    val cue: FeedbackCue = FeedbackCue.NAV_TICK,
  ) : ParsedVoiceCommand()

  data class OpenOttApp(
    val shortcut: TvAppShortcut,
    val label: String,
  ) : ParsedVoiceCommand()

  data class SearchOrType(
    val query: String,
    val label: String,
  ) : ParsedVoiceCommand()
}

object VoiceCommandParser {

  private val ottVerbs = listOf("open ", "launch ", "start ", "run ", "switch to ", "go to ")
  private val searchPrefixes = listOf("search for ", "search ", "find ", "look up ", "play ")

  fun parse(
    transcript: String,
    availableShortcuts: List<TvAppShortcut> = TvAppShortcuts.all + TvAppShortcuts.popularAddableCatalog,
  ): ParsedVoiceCommand? {
    val raw = transcript.trim()
    if (raw.isEmpty()) return null

    val normalized =
      raw
        .lowercase()
        .replace(Regex("[^a-z0-9+\\s]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
        .removePrefix("please ")
        .removePrefix("can you ")
        .removePrefix("hey tv ")
        .removePrefix("tv ")
        .trim()

    if (normalized.isEmpty()) return null

    // 1. Direct TV key & playback commands
    matchDirectKeyCommand(normalized)?.let { return it }

    // 2. OTT app launch commands ("open netflix", "launch youtube", or exact app name)
    for (verb in ottVerbs) {
      if (normalized.startsWith(verb)) {
        val targetName = normalized.removePrefix(verb).trim()
        findMatchingShortcut(targetName, availableShortcuts)?.let { shortcut ->
          return ParsedVoiceCommand.OpenOttApp(
            shortcut = shortcut,
            label = "Opening ${shortcut.name}",
          )
        }
      }
    }

    // Also allow saying just the app name directly (e.g. "Netflix", "YouTube", "Disney Hotstar")
    findMatchingShortcut(normalized, availableShortcuts, exactOrAliasOnly = true)?.let { shortcut ->
      return ParsedVoiceCommand.OpenOttApp(
        shortcut = shortcut,
        label = "Opening ${shortcut.name}",
      )
    }

    // 3. Explicit search / query command
    for (prefix in searchPrefixes) {
      if (normalized.startsWith(prefix)) {
        val cleaned = raw.trim().drop(prefix.length).trim()
        if (cleaned.isNotEmpty()) {
          return ParsedVoiceCommand.SearchOrType(
            query = cleaned,
            label = "Searching TV for \"$cleaned\"",
          )
        }
      }
    }

    // 4. Fallback: send natural speech as TV search query
    return ParsedVoiceCommand.SearchOrType(
      query = raw,
      label = "Searching TV for \"$raw\"",
    )
  }

  private fun matchDirectKeyCommand(normalized: String): ParsedVoiceCommand.SendKey? {
    return when (normalized) {
      "volume up", "louder", "turn up volume", "turn up the volume", "increase volume", "raise volume" ->
        ParsedVoiceCommand.SendKey(RemoteKeyCode.KEYCODE_VOLUME_UP, "Volume Up", FeedbackCue.NAV_TICK)

      "volume down", "quieter", "softer", "turn down volume", "turn down the volume", "decrease volume", "lower volume" ->
        ParsedVoiceCommand.SendKey(RemoteKeyCode.KEYCODE_VOLUME_DOWN, "Volume Down", FeedbackCue.NAV_TICK)

      "mute", "unmute", "mute tv", "mute volume", "silence" ->
        ParsedVoiceCommand.SendKey(RemoteKeyCode.KEYCODE_VOLUME_MUTE, "Toggled Mute", FeedbackCue.BACK_HOME)

      "channel up", "next channel" ->
        ParsedVoiceCommand.SendKey(RemoteKeyCode.KEYCODE_CHANNEL_UP, "Channel Up", FeedbackCue.NAV_TICK)

      "channel down", "previous channel", "last channel" ->
        ParsedVoiceCommand.SendKey(RemoteKeyCode.KEYCODE_CHANNEL_DOWN, "Channel Down", FeedbackCue.NAV_TICK)

      "play", "pause", "play pause", "resume", "resume playback", "pause playback", "pause video", "play video" ->
        ParsedVoiceCommand.SendKey(RemoteKeyCode.KEYCODE_MEDIA_PLAY_PAUSE, "Play / Pause", FeedbackCue.CONFIRM_CHIME)

      "stop", "stop playback", "stop video" ->
        ParsedVoiceCommand.SendKey(RemoteKeyCode.KEYCODE_MEDIA_STOP, "Stopped Playback", FeedbackCue.BACK_HOME)

      "next", "next track", "next episode", "next video", "skip" ->
        ParsedVoiceCommand.SendKey(RemoteKeyCode.KEYCODE_MEDIA_NEXT, "Next", FeedbackCue.NAV_TICK)

      "previous", "previous track", "previous episode", "previous video" ->
        ParsedVoiceCommand.SendKey(RemoteKeyCode.KEYCODE_MEDIA_PREVIOUS, "Previous", FeedbackCue.NAV_TICK)

      "rewind", "skip back", "seek back", "go backward" ->
        ParsedVoiceCommand.SendKey(RemoteKeyCode.KEYCODE_MEDIA_REWIND, "Rewind", FeedbackCue.NAV_TICK)

      "fast forward", "forward", "skip forward", "seek forward" ->
        ParsedVoiceCommand.SendKey(RemoteKeyCode.KEYCODE_MEDIA_FAST_FORWARD, "Fast Forward", FeedbackCue.NAV_TICK)

      "home", "go home", "home screen", "go to home" ->
        ParsedVoiceCommand.SendKey(RemoteKeyCode.KEYCODE_HOME, "Home", FeedbackCue.BACK_HOME)

      "back", "go back", "return" ->
        ParsedVoiceCommand.SendKey(RemoteKeyCode.KEYCODE_BACK, "Back", FeedbackCue.BACK_HOME)

      "menu", "open menu", "options" ->
        ParsedVoiceCommand.SendKey(RemoteKeyCode.KEYCODE_MENU, "Menu", FeedbackCue.NAV_TICK)

      "input", "hdmi", "source", "switch input", "change input" ->
        ParsedVoiceCommand.SendKey(RemoteKeyCode.KEYCODE_TV_INPUT, "Input Source", FeedbackCue.NAV_TICK)

      "power", "power off", "turn off", "turn off tv", "turn off the tv", "turn on", "turn on tv", "sleep" ->
        ParsedVoiceCommand.SendKey(RemoteKeyCode.KEYCODE_POWER, "Power Toggle", FeedbackCue.POWER_OR_APP)

      "settings", "open settings", "tv settings" ->
        ParsedVoiceCommand.SendKey(RemoteKeyCode.KEYCODE_SETTINGS, "TV Settings", FeedbackCue.NAV_TICK)

      "captions", "subtitles", "toggle captions", "toggle subtitles" ->
        ParsedVoiceCommand.SendKey(RemoteKeyCode.KEYCODE_CAPTIONS, "Captions", FeedbackCue.NAV_TICK)

      "guide", "tv guide", "channel guide" ->
        ParsedVoiceCommand.SendKey(RemoteKeyCode.KEYCODE_GUIDE, "TV Guide", FeedbackCue.NAV_TICK)

      "ok", "okay", "select", "enter", "confirm", "click" ->
        ParsedVoiceCommand.SendKey(RemoteKeyCode.KEYCODE_DPAD_CENTER, "Select OK", FeedbackCue.CONFIRM_CHIME)

      "up", "move up", "scroll up", "go up" ->
        ParsedVoiceCommand.SendKey(RemoteKeyCode.KEYCODE_DPAD_UP, "D-Pad Up", FeedbackCue.NAV_TICK)

      "down", "move down", "scroll down", "go down" ->
        ParsedVoiceCommand.SendKey(RemoteKeyCode.KEYCODE_DPAD_DOWN, "D-Pad Down", FeedbackCue.NAV_TICK)

      "left", "move left", "go left" ->
        ParsedVoiceCommand.SendKey(RemoteKeyCode.KEYCODE_DPAD_LEFT, "D-Pad Left", FeedbackCue.NAV_TICK)

      "right", "move right", "go right" ->
        ParsedVoiceCommand.SendKey(RemoteKeyCode.KEYCODE_DPAD_RIGHT, "D-Pad Right", FeedbackCue.NAV_TICK)

      "assistant", "google assistant", "voice assistant" ->
        ParsedVoiceCommand.SendKey(RemoteKeyCode.KEYCODE_VOICE_ASSIST, "TV Assistant", FeedbackCue.POWER_OR_APP)

      else -> null
    }
  }

  private fun findMatchingShortcut(
    spokenTarget: String,
    shortcuts: List<TvAppShortcut>,
    exactOrAliasOnly: Boolean = false,
  ): TvAppShortcut? {
    val clean = spokenTarget.replace(Regex("[^a-z0-9+\\s]"), " ").replace(Regex("\\s+"), " ").trim()
    if (clean.isEmpty()) return null
    val compactSpoken = clean.replace(" ", "")

    val aliases =
      mapOf(
        "amazon prime" to "prime_video",
        "prime" to "prime_video",
        "prime video" to "prime_video",
        "hotstar" to "hotstar",
        "disney hotstar" to "hotstar",
        "disney plus" to "hotstar",
        "disney+ hotstar" to "hotstar",
        "disney" to "hotstar",
        "jio cinema" to "jiocinema",
        "jio tv" to "jiotv",
        "sony liv" to "sonyliv",
        "zee 5" to "zee5",
        "zee five" to "zee5",
        "sun next" to "sunnxt",
        "apple tv" to "apple_tv",
        "apple tv plus" to "apple_tv",
        "discovery plus" to "discovery_plus",
        "fan code" to "fancode",
        "hbo max" to "max",
      )

    aliases[clean]?.let { aliasId ->
      shortcuts.firstOrNull { it.id == aliasId }?.let { return it }
    }

    shortcuts.firstOrNull { shortcut ->
      val normalizedName = shortcut.name.lowercase().replace(Regex("[^a-z0-9+\\s]"), " ").replace(Regex("\\s+"), " ").trim()
      val compactName = normalizedName.replace(" ", "")
      normalizedName == clean ||
        compactName == compactSpoken ||
        shortcut.id.replace("_", "") == compactSpoken
    }?.let { return it }

    if (exactOrAliasOnly) return null

    return shortcuts.firstOrNull { shortcut ->
      val normalizedName = shortcut.name.lowercase().replace(Regex("[^a-z0-9+\\s]"), " ").replace(Regex("\\s+"), " ").trim()
      normalizedName.contains(clean) || clean.contains(normalizedName)
    }
  }
}
