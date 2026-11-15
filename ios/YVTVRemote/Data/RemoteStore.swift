import Foundation
import SwiftUI

struct UserProfile: Identifiable, Codable, Equatable {
  let id: String
  var name: String
}

struct SavedTvConnection: Identifiable, Codable, Equatable {
  let id: String
  var profileId: String
  var customName: String
  var deviceName: String
  var ipAddress: String
  var port: UInt16
}

struct AppSettings: Codable, Equatable {
  var hapticsEnabled: Bool = true
  var soundEnabled: Bool = true
  var autoReconnectEnabled: Bool = true
}

struct TvAppShortcut: Identifiable {
  let id: String
  let name: String
  let badge: String
  let appLink: String
  let accentColor: Color
}

enum TvAppShortcuts {
  static let all: [TvAppShortcut] = [
    TvAppShortcut(id: "youtube", name: "YouTube", badge: "YT", appLink: "https://www.youtube.com", accentColor: Color(red: 1.0, green: 0.23, blue: 0.19)),
    TvAppShortcut(id: "netflix", name: "Netflix", badge: "NF", appLink: "https://www.netflix.com", accentColor: Color(red: 0.90, green: 0.04, blue: 0.08)),
    TvAppShortcut(id: "prime_video", name: "Prime Video", badge: "PV", appLink: "https://app.primevideo.com", accentColor: Color(red: 0.0, green: 0.66, blue: 0.88)),
    TvAppShortcut(id: "jiocinema", name: "JioCinema", badge: "JC", appLink: "https://www.jiocinema.com", accentColor: Color(red: 0.85, green: 0.0, blue: 0.55)),
    TvAppShortcut(id: "hotstar", name: "Disney+ Hotstar", badge: "HS", appLink: "https://www.hotstar.com", accentColor: Color(red: 0.12, green: 0.50, blue: 0.88)),
    TvAppShortcut(id: "sonyliv", name: "SonyLIV", badge: "SL", appLink: "https://www.sonyliv.com", accentColor: Color(red: 0.96, green: 0.65, blue: 0.14)),
    TvAppShortcut(id: "zee5", name: "ZEE5", badge: "Z5", appLink: "https://www.zee5.com", accentColor: Color(red: 0.64, green: 0.35, blue: 1.0)),
    TvAppShortcut(id: "sunnxt", name: "Sun NXT", badge: "SN", appLink: "https://www.sunnxt.com", accentColor: Color(red: 1.0, green: 0.48, blue: 0.0)),
    TvAppShortcut(id: "jiotv", name: "JioTV", badge: "JT", appLink: "https://jiotv.com", accentColor: Color(red: 0.04, green: 0.40, blue: 0.76)),
  ]
}

final class RemoteStore {
  static let defaultProfileId = "profile_default"

  private let defaults = UserDefaults.standard
  private let profilesKey = "yvtv_profiles"
  private let savedTvsKey = "yvtv_saved_tvs"
  private let settingsKey = "yvtv_settings"
  private let activeProfileKey = "yvtv_active_profile"
  private let lastTvIdKey = "yvtv_last_tv_id"

  func loadProfiles() -> [UserProfile] {
    if let data = defaults.data(forKey: profilesKey),
       let decoded = try? JSONDecoder().decode([UserProfile].self, from: data),
       !decoded.isEmpty {
      return decoded
    }
    let initial = [UserProfile(id: Self.defaultProfileId, name: "Living Room")]
    saveProfiles(initial)
    return initial
  }

  func saveProfiles(_ profiles: [UserProfile]) {
    if let data = try? JSONEncoder().encode(profiles) {
      defaults.set(data, forKey: profilesKey)
    }
  }

  func getActiveProfileId() -> String {
    defaults.string(forKey: activeProfileKey) ?? Self.defaultProfileId
  }

  func setActiveProfileId(_ id: String) {
    defaults.set(id, forKey: activeProfileKey)
  }

  func loadSavedConnections() -> [SavedTvConnection] {
    guard let data = defaults.data(forKey: savedTvsKey),
          let decoded = try? JSONDecoder().decode([SavedTvConnection].self, from: data) else {
      return []
    }
    return decoded
  }

  func saveConnections(_ items: [SavedTvConnection]) {
    if let data = try? JSONEncoder().encode(items) {
      defaults.set(data, forKey: savedTvsKey)
    }
  }

  @discardableResult
  func upsertConnection(
    profileId: String,
    customName: String,
    deviceName: String,
    ipAddress: String,
    port: UInt16
  ) -> SavedTvConnection {
    var list = loadSavedConnections()
    let cleanCustom = customName.trimmingCharacters(in: .whitespacesAndNewlines)
    let resolvedName = cleanCustom.isEmpty ? deviceName : cleanCustom

    if let idx = list.firstIndex(where: { $0.ipAddress == ipAddress && $0.profileId == profileId }) {
      list[idx].customName = resolvedName
      list[idx].deviceName = deviceName
      list[idx].port = port
      saveConnections(list)
      return list[idx]
    } else {
      let item = SavedTvConnection(
        id: UUID().uuidString,
        profileId: profileId,
        customName: resolvedName,
        deviceName: deviceName,
        ipAddress: ipAddress,
        port: port
      )
      list.insert(item, at: 0)
      saveConnections(list)
      return item
    }
  }

  func loadSettings() -> AppSettings {
    guard let data = defaults.data(forKey: settingsKey),
          let decoded = try? JSONDecoder().decode(AppSettings.self, from: data) else {
      return AppSettings()
    }
    return decoded
  }

  func saveSettings(_ settings: AppSettings) {
    if let data = try? JSONEncoder().encode(settings) {
      defaults.set(data, forKey: settingsKey)
    }
  }

  func getLastConnectedTvId() -> String? {
    defaults.string(forKey: lastTvIdKey)
  }

  func setLastConnectedTvId(_ id: String?) {
    defaults.set(id, forKey: lastTvIdKey)
  }
}
