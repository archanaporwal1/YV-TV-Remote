import Combine
import Foundation
import SwiftUI

enum RemoteInputMode: String {
  case dpad
  case touchpad
}

enum ConnectionStatus: Equatable {
  case disconnected
  case connecting(targetName: String, ipAddress: String, port: UInt16)
  case pairing(
    targetIp: String,
    controlPort: UInt16,
    defaultName: String,
    customName: String,
    serverName: String,
    errorMessage: String?,
    isSubmitting: Bool
  )
  case connected(displayName: String, deviceName: String, ipAddress: String, port: UInt16)
  case error(message: String, targetIp: String?, targetPort: UInt16, targetName: String?)
}

struct PendingConnectionTarget {
  let ipAddress: String
  let controlPort: UInt16
  let deviceName: String
  var customName: String
  let profileId: String
}

@MainActor
final class RemoteViewModel: ObservableObject {
  @Published var connectionStatus: ConnectionStatus = .disconnected
  @Published var discoveredTvs: [DiscoveredTv] = []
  @Published var isScanning = false
  @Published var scanError: String?
  @Published var profiles: [UserProfile] = []
  @Published var activeProfileId: String = RemoteStore.defaultProfileId
  @Published var savedConnections: [SavedTvConnection] = []
  @Published var settings = AppSettings()
  @Published var inputMode: RemoteInputMode = .dpad
  @Published var showConnectionHub = true
  @Published var showSettingsSheet = false
  @Published var showKeyboardSheet = false
  @Published var tvImeState = TvImeState()
  @Published var statusBannerMessage: String?

  private let discoveryManager = TvDiscoveryManager()
  private let pairingClient = PairingClient()
  private let remoteClient = RemoteControlClient()
  private let store = RemoteStore()
  private let feedback = FeedbackManager.shared

  private var activePairingSession: ActivePairingSession?
  private var pendingTarget: PendingConnectionTarget?
  private var postPairingRetryAvailable = false
  private var cancellables = Set<AnyCancellable>()

  init() {
    loadStoredState()
    bindSubsystems()
    attemptAutoReconnectIfEnabled()
  }

  private func loadStoredState() {
    profiles = store.loadProfiles()
    let storedId = store.getActiveProfileId()
    activeProfileId = profiles.contains(where: { $0.id == storedId }) ? storedId : (profiles.first?.id ?? RemoteStore.defaultProfileId)
    savedConnections = store.loadSavedConnections()
    settings = store.loadSettings()
  }

  private func bindSubsystems() {
    discoveryManager.$discoveredTvs.assign(to: &$discoveredTvs)
    discoveryManager.$isScanning.assign(to: &$isScanning)
    discoveryManager.$scanError.assign(to: &$scanError)
    remoteClient.$imeState.assign(to: &$tvImeState)

    remoteClient.$sessionEvent
      .sink { [weak self] event in
        self?.handleSessionEvent(event)
      }
      .store(in: &cancellables)
  }

  private func handleSessionEvent(_ event: RemoteSessionEvent) {
    switch event {
    case .disconnected:
      if case .pairing = connectionStatus { return }
      connectionStatus = .disconnected

    case .connecting:
      connectionStatus = .connecting(
        targetName: pendingTarget?.customName ?? pendingTarget?.deviceName ?? "Android TV",
        ipAddress: pendingTarget?.ipAddress ?? "",
        port: pendingTarget?.controlPort ?? RemoteControlClient.defaultRemotePort
      )

    case .connected:
      postPairingRetryAvailable = false
      guard let target = pendingTarget else { return }
      let saved = store.upsertConnection(
        profileId: target.profileId,
        customName: target.customName,
        deviceName: target.deviceName,
        ipAddress: target.ipAddress,
        port: target.controlPort
      )
      store.setLastConnectedTvId(saved.id)
      savedConnections = store.loadSavedConnections()
      connectionStatus = .connected(
        displayName: saved.customName,
        deviceName: saved.deviceName,
        ipAddress: saved.ipAddress,
        port: saved.port
      )
      showConnectionHub = false
      statusBannerMessage = nil

    case .pairingRequired:
      guard let target = pendingTarget else { return }
      if postPairingRetryAvailable {
        postPairingRetryAvailable = false
        Task {
          try? await Task.sleep(nanoseconds: 500_000_000)
          await remoteClient.connect(host: target.ipAddress, port: target.controlPort)
        }
      } else {
        initiatePairing(target)
      }

    case .error(let message):
      postPairingRetryAvailable = false
      connectionStatus = .error(
        message: message,
        targetIp: pendingTarget?.ipAddress,
        targetPort: pendingTarget?.controlPort ?? RemoteControlClient.defaultRemotePort,
        targetName: pendingTarget?.customName ?? pendingTarget?.deviceName
      )
    }
  }

  private func attemptAutoReconnectIfEnabled() {
    guard settings.autoReconnectEnabled,
          let lastId = store.getLastConnectedTvId(),
          let lastTv = savedConnections.first(where: { $0.id == lastId }) else {
      return
    }
    connectToSavedTv(lastTv)
  }

  func startDiscoveryScan() {
    discoveryManager.startScan()
  }

  func stopDiscoveryScan() {
    discoveryManager.stopScan()
  }

  func connectToDiscoveredTv(_ tv: DiscoveredTv, customName: String? = nil) {
    let existing = savedConnections.first { $0.ipAddress == tv.ipAddress && $0.profileId == activeProfileId }
    let trimmed = customName?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
    let chosenName = trimmed.isEmpty ? (existing?.customName ?? tv.displayName) : trimmed
    let target = PendingConnectionTarget(
      ipAddress: tv.ipAddress,
      controlPort: tv.port,
      deviceName: tv.displayName,
      customName: chosenName,
      profileId: activeProfileId
    )
    connectWithAutoPair(target)
  }

  func connectManualIp(ipAddress: String, port: UInt16, customName: String) {
    let cleanIp = ipAddress.trimmingCharacters(in: .whitespacesAndNewlines)
    guard !cleanIp.isEmpty else { return }
    let cleanName = customName.trimmingCharacters(in: .whitespacesAndNewlines)
    let chosenName = cleanName.isEmpty ? cleanIp : cleanName
    let target = PendingConnectionTarget(
      ipAddress: cleanIp,
      controlPort: port == 0 ? RemoteControlClient.defaultRemotePort : port,
      deviceName: chosenName,
      customName: chosenName,
      profileId: activeProfileId
    )
    connectWithAutoPair(target)
  }

  func connectToSavedTv(_ savedTv: SavedTvConnection) {
    if savedTv.profileId != activeProfileId && profiles.contains(where: { $0.id == savedTv.profileId }) {
      selectProfile(savedTv.profileId)
    }
    let target = PendingConnectionTarget(
      ipAddress: savedTv.ipAddress,
      controlPort: savedTv.port,
      deviceName: savedTv.deviceName,
      customName: savedTv.customName,
      profileId: savedTv.profileId
    )
    connectWithAutoPair(target)
  }

  private func connectWithAutoPair(_ target: PendingConnectionTarget) {
    cancelPairingInternal()
    postPairingRetryAvailable = false
    pendingTarget = target
    discoveryManager.stopScan()
    Task {
      await remoteClient.connect(host: target.ipAddress, port: target.controlPort)
    }
  }

  private func initiatePairing(_ target: PendingConnectionTarget) {
    cancelPairingInternal()
    connectionStatus = .connecting(
      targetName: target.customName,
      ipAddress: target.ipAddress,
      port: PairingClient.defaultPairingPort
    )

    Task {
      let result = await pairingClient.startPairing(host: target.ipAddress, port: PairingClient.defaultPairingPort)
      switch result {
      case .awaitingPin(let session, let serverName):
        activePairingSession = session
        connectionStatus = .pairing(
          targetIp: target.ipAddress,
          controlPort: target.controlPort,
          defaultName: target.deviceName,
          customName: target.customName,
          serverName: serverName,
          errorMessage: nil,
          isSubmitting: false
        )
      case .failed(let reason):
        connectionStatus = .error(
          message: reason,
          targetIp: target.ipAddress,
          targetPort: target.controlPort,
          targetName: target.customName
        )
      }
    }
  }

  func submitPairingPin(_ pin: String, customTvName: String) {
    guard let session = activePairingSession,
          case .pairing(let targetIp, let controlPort, let defaultName, let prevName, let serverName, _, _) = connectionStatus else {
      return
    }
    let cleanName = customTvName.trimmingCharacters(in: .whitespacesAndNewlines)
    let finalName = cleanName.isEmpty ? prevName : cleanName
    pendingTarget?.customName = finalName

    connectionStatus = .pairing(
      targetIp: targetIp,
      controlPort: controlPort,
      defaultName: defaultName,
      customName: finalName,
      serverName: serverName,
      errorMessage: nil,
      isSubmitting: true
    )

    Task {
      let result = await session.submitPin(pin)
      switch result {
      case .success:
        activePairingSession = nil
        let target = pendingTarget ?? PendingConnectionTarget(
          ipAddress: targetIp,
          controlPort: controlPort,
          deviceName: defaultName,
          customName: finalName,
          profileId: activeProfileId
        )
        let saved = store.upsertConnection(
          profileId: target.profileId,
          customName: finalName,
          deviceName: target.deviceName,
          ipAddress: target.ipAddress,
          port: target.controlPort
        )
        store.setLastConnectedTvId(saved.id)
        savedConnections = store.loadSavedConnections()
        postPairingRetryAvailable = true
        try? await Task.sleep(nanoseconds: 300_000_000)
        await remoteClient.connect(host: target.ipAddress, port: target.controlPort)

      case .invalidPin(let reason):
        connectionStatus = .pairing(
          targetIp: targetIp,
          controlPort: controlPort,
          defaultName: defaultName,
          customName: finalName,
          serverName: serverName,
          errorMessage: reason,
          isSubmitting: false
        )

      case .rejectedByTv(let reason), .connectionLost(let reason):
        activePairingSession = nil
        connectionStatus = .error(
          message: reason,
          targetIp: targetIp,
          targetPort: controlPort,
          targetName: finalName
        )
      }
    }
  }

  func cancelPairing() {
    cancelPairingInternal()
    connectionStatus = .disconnected
  }

  private func cancelPairingInternal() {
    activePairingSession?.close()
    activePairingSession = nil
  }

  func sendKey(_ keyCode: Int, cue: FeedbackCue = .navTick) {
    feedback.trigger(cue, settings: settings)
    Task {
      let ok = await remoteClient.sendKey(keyCode)
      if !ok {
        if case .connected = connectionStatus {
          statusBannerMessage = "Failed to send command to TV."
        } else {
          statusBannerMessage = "Not connected to a TV. Connect or pair a device first."
        }
      }
    }
  }

  func sendKeyboardImeText(_ newText: String, previousText: String) {
    Task {
      await remoteClient.sendImeText(newText, previousLength: previousText.count)
    }
  }

  func submitKeyboardSearch(_ text: String, useDirectKeys: Bool) {
    feedback.trigger(.confirmChime, settings: settings)
    Task {
      guard case .connected = connectionStatus else {
        statusBannerMessage = "Not connected to a TV. Connect or pair a device first."
        return
      }
      let sent: Bool
      if useDirectKeys {
        sent = await remoteClient.sendTextAsKeyEvents(text)
      } else {
        sent = await remoteClient.sendImeText(text, previousLength: tvImeState.currentText.count)
      }
      if sent {
        await remoteClient.sendKey(TvKeyCode.enter)
      } else {
        statusBannerMessage = "Failed to send text to TV."
      }
    }
  }

  func launchShortcut(_ shortcut: TvAppShortcut) {
    feedback.trigger(.powerOrApp, settings: settings)
    Task {
      let sent = await remoteClient.launchApp(shortcut.appLink)
      statusBannerMessage = sent
        ? "Launching \(shortcut.name) on TV..."
        : "Connect to your TV to launch \(shortcut.name)."
    }
  }

  func selectProfile(_ id: String) {
    activeProfileId = id
    store.setActiveProfileId(id)
  }

  func createProfile(name: String) {
    let clean = name.trimmingCharacters(in: .whitespacesAndNewlines)
    guard !clean.isEmpty else { return }
    let prof = UserProfile(id: UUID().uuidString, name: clean)
    profiles.append(prof)
    store.saveProfiles(profiles)
    selectProfile(prof.id)
  }

  func deleteProfile(_ id: String) {
    guard profiles.count > 1 else { return }
    profiles.removeAll { $0.id == id }
    let fallback = profiles.first?.id ?? RemoteStore.defaultProfileId
    savedConnections = savedConnections.map { item in
      var copy = item
      if copy.profileId == id { copy.profileId = fallback }
      return copy
    }
    store.saveProfiles(profiles)
    store.saveConnections(savedConnections)
    if activeProfileId == id { selectProfile(fallback) }
  }

  func saveDiscoveredTv(_ tv: DiscoveredTv, customName: String, profileId: String) {
    let saved = store.upsertConnection(
      profileId: profileId,
      customName: customName,
      deviceName: tv.displayName,
      ipAddress: tv.ipAddress,
      port: tv.port
    )
    savedConnections = store.loadSavedConnections()
    statusBannerMessage = "Saved \"\(saved.customName)\""
  }

  func updateSavedTv(id: String, customName: String, ipAddress: String, port: UInt16, profileId: String) {
    savedConnections = savedConnections.map { item in
      guard item.id == id else { return item }
      var copy = item
      let cleanName = customName.trimmingCharacters(in: .whitespacesAndNewlines)
      let cleanIp = ipAddress.trimmingCharacters(in: .whitespacesAndNewlines)
      if !cleanName.isEmpty { copy.customName = cleanName }
      if !cleanIp.isEmpty { copy.ipAddress = cleanIp }
      if port > 0 { copy.port = port }
      copy.profileId = profileId
      return copy
    }
    store.saveConnections(savedConnections)
  }

  func deleteSavedTv(_ id: String) {
    savedConnections.removeAll { $0.id == id }
    store.saveConnections(savedConnections)
    if store.getLastConnectedTvId() == id {
      store.setLastConnectedTvId(nil)
    }
  }

  func saveSettings(_ updated: AppSettings) {
    settings = updated
    store.saveSettings(updated)
  }
}
