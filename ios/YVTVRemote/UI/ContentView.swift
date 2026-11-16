import SwiftUI

private extension Color {
  static let oledBlack = Color(red: 0.020, green: 0.027, blue: 0.043)
  static let midnightSlate = Color(red: 0.055, green: 0.075, blue: 0.106)
  static let raisedPod = Color(red: 0.086, green: 0.118, blue: 0.169)
  static let electricCyan = Color(red: 0.0, green: 0.898, blue: 1.0)
  static let crimsonPower = Color(red: 1.0, green: 0.302, blue: 0.427)
  static let subtleOutline = Color(red: 0.149, green: 0.200, blue: 0.278)
}

struct ContentView: View {
  @StateObject private var viewModel = RemoteViewModel()

  @State private var showManualIpSheet = false
  @State private var showCreateProfileAlert = false
  @State private var newProfileName = ""
  @State private var discoveredToSave: DiscoveredTv?
  @State private var savedToEdit: SavedTvConnection?

  var body: some View {
    ZStack {
      Color.oledBlack.ignoresSafeArea()

      if viewModel.showConnectionHub {
        ConnectionHubScreen(
          viewModel: viewModel,
          onOpenManualIp: { showManualIpSheet = true },
          onCreateProfile: {
            newProfileName = ""
            showCreateProfileAlert = true
          },
          onSaveDiscovered: { discoveredToSave = $0 },
          onEditSaved: { savedToEdit = $0 }
        )
      } else {
        RemoteControlScreen(viewModel: viewModel)
      }
    }
    .preferredColorScheme(.dark)
    .sheet(isPresented: $viewModel.showSettingsSheet) {
      SettingsSheetView(viewModel: viewModel)
    }
    .sheet(isPresented: $viewModel.showKeyboardSheet) {
      KeyboardSearchSheetView(viewModel: viewModel)
    }
    .sheet(isPresented: $showManualIpSheet) {
      ManualIpSheetView { ip, port, name in
        showManualIpSheet = false
        viewModel.connectManualIp(ipAddress: ip, port: port, customName: name)
      }
    }
    .sheet(item: $discoveredToSave) { tv in
      SaveOrEditTvSheetView(
        title: "Save Discovered TV",
        initialName: tv.displayName,
        initialIp: tv.ipAddress,
        initialPort: tv.port,
        initialProfileId: viewModel.activeProfileId,
        profiles: viewModel.profiles,
        allowEditAddress: false
      ) { name, _, _, profileId in
        viewModel.saveDiscoveredTv(tv, customName: name, profileId: profileId)
      }
    }
    .sheet(item: $savedToEdit) { saved in
      SaveOrEditTvSheetView(
        title: "Edit Saved TV",
        initialName: saved.customName,
        initialIp: saved.ipAddress,
        initialPort: saved.port,
        initialProfileId: saved.profileId,
        profiles: viewModel.profiles,
        allowEditAddress: true
      ) { name, ip, port, profileId in
        viewModel.updateSavedTv(id: saved.id, customName: name, ipAddress: ip, port: port, profileId: profileId)
      }
    }
    .sheet(
      isPresented: Binding(
        get: {
          if case .pairing = viewModel.connectionStatus { return true }
          return false
        },
        set: { active in
          if !active { viewModel.cancelPairing() }
        }
      )
    ) {
      if case .pairing(_, _, _, let customName, let serverName, let errorMsg, let isSubmitting) = viewModel.connectionStatus {
        PairingPinSheetView(
          serverName: serverName,
          initialCustomName: customName,
          errorMessage: errorMsg,
          isSubmitting: isSubmitting,
          onVerify: { pin, name in viewModel.submitPairingPin(pin, customTvName: name) },
          onCancel: { viewModel.cancelPairing() }
        )
      }
    }
    .alert("New Profile", isPresented: $showCreateProfileAlert) {
      TextField("Profile name (e.g. Bedroom)", text: $newProfileName)
      Button("Create") { viewModel.createProfile(name: newProfileName) }
      Button("Cancel", role: .cancel) {}
    }
    .overlay(alignment: .bottom) {
      if let banner = viewModel.statusBannerMessage {
        Text(banner)
          .font(.footnote.weight(.medium))
          .foregroundStyle(.white)
          .padding(.horizontal, 16)
          .padding(.vertical, 10)
          .background(Color.raisedPod, in: Capsule())
          .overlay(Capsule().stroke(Color.electricCyan.opacity(0.45), lineWidth: 1))
          .padding(.bottom, 20)
          .onTapGesture { viewModel.statusBannerMessage = nil }
      }
    }
  }
}

private struct ConnectionHubScreen: View {
  @ObservedObject var viewModel: RemoteViewModel
  let onOpenManualIp: () -> Void
  let onCreateProfile: () -> Void
  let onSaveDiscovered: (DiscoveredTv) -> Void
  let onEditSaved: (SavedTvConnection) -> Void

  var body: some View {
    ScrollView {
      VStack(alignment: .leading, spacing: 18) {
        headerRow
        statusHeroCard
        profilesSection
        savedConnectionsSection
        discoverySection
      }
      .padding(.horizontal, 20)
      .padding(.vertical, 16)
    }
    .onAppear { viewModel.startDiscoveryScan() }
    .onDisappear { viewModel.stopDiscoveryScan() }
  }

  private var headerRow: some View {
    HStack(spacing: 12) {
      RoundedRectangle(cornerRadius: 14)
        .fill(Color.midnightSlate)
        .frame(width: 46, height: 46)
        .overlay(
          YvBrandLogoMark()
            .padding(6)
        )
        .overlay(RoundedRectangle(cornerRadius: 14).stroke(Color.subtleOutline, lineWidth: 1))

      VStack(alignment: .leading, spacing: 2) {
        Text("YV-TV Remote")
          .font(.title2.weight(.bold))
          .foregroundStyle(.white)
        Text("Android TV & Google TV Controller")
          .font(.caption)
          .foregroundStyle(.secondary)
      }
      Spacer()

      Button {
        viewModel.showConnectionHub = false
      } label: {
        Image(systemName: "appletvremote.gen4.fill")
          .frame(width: 44, height: 44)
          .foregroundStyle(Color.electricCyan)
          .background(Color.midnightSlate, in: Circle())
          .overlay(Circle().stroke(Color.subtleOutline, lineWidth: 1))
      }

      Button {
        viewModel.showSettingsSheet = true
      } label: {
        Image(systemName: "gearshape.fill")
          .frame(width: 44, height: 44)
          .foregroundStyle(.white)
          .background(Color.midnightSlate, in: Circle())
          .overlay(Circle().stroke(Color.subtleOutline, lineWidth: 1))
      }
    }
  }

  @ViewBuilder
  private var statusHeroCard: some View {
    VStack(alignment: .leading, spacing: 10) {
      switch viewModel.connectionStatus {
      case .connected(let displayName, _, let ip, let port):
        HStack {
          VStack(alignment: .leading, spacing: 4) {
            Text(displayName)
              .font(.headline)
              .foregroundStyle(.white)
            Text("TLS Connected • \(ip):\(port)")
              .font(.caption.monospaced())
              .foregroundStyle(.green)
          }
          Spacer()
          Button("Remote") {
            viewModel.showConnectionHub = false
          }
          .buttonStyle(.borderedProminent)
          .tint(Color.electricCyan)
          .foregroundStyle(.black)
        }

      case .connecting(let targetName, let ip, let port):
        HStack(spacing: 12) {
          ProgressView().tint(Color.electricCyan)
          VStack(alignment: .leading, spacing: 2) {
            Text("Connecting to \(targetName)...")
              .font(.subheadline.weight(.semibold))
              .foregroundStyle(.white)
            Text("\(ip):\(port)")
              .font(.caption.monospaced())
              .foregroundStyle(.secondary)
          }
        }

      case .error(let message, let targetIp, let targetPort, let targetName):
        VStack(alignment: .leading, spacing: 8) {
          Text("Connection Issue")
            .font(.subheadline.weight(.semibold))
            .foregroundStyle(Color.crimsonPower)
          Text(message)
            .font(.caption)
            .foregroundStyle(.secondary)
          if let ip = targetIp {
            Button("Retry \(targetName ?? ip)") {
              viewModel.connectManualIp(ipAddress: ip, port: targetPort, customName: targetName ?? ip)
            }
            .buttonStyle(.bordered)
            .tint(Color.electricCyan)
          }
        }

      default:
        HStack(spacing: 12) {
          Image(systemName: "tv")
            .font(.title3)
            .foregroundStyle(Color.electricCyan)
            .frame(width: 42, height: 42)
            .background(Color.raisedPod, in: Circle())
          VStack(alignment: .leading, spacing: 2) {
            Text("Ready to Connect")
              .font(.subheadline.weight(.semibold))
              .foregroundStyle(.white)
            Text("Select a saved TV or scan your local Wi-Fi.")
              .font(.caption)
              .foregroundStyle(.secondary)
          }
        }
      }
    }
    .frame(maxWidth: .infinity, alignment: .leading)
    .padding(16)
    .background(Color.midnightSlate, in: RoundedRectangle(cornerRadius: 18))
    .overlay(RoundedRectangle(cornerRadius: 18).stroke(Color.subtleOutline, lineWidth: 1))
  }

  private var profilesSection: some View {
    VStack(alignment: .leading, spacing: 8) {
      HStack {
        Text("USER PROFILES")
          .font(.caption.weight(.semibold))
          .foregroundStyle(.secondary)
        Spacer()
        Button("+ New Profile", action: onCreateProfile)
          .font(.caption.weight(.semibold))
          .foregroundStyle(Color.electricCyan)
      }
      ScrollView(.horizontal, showsIndicators: false) {
        HStack(spacing: 8) {
          ForEach(viewModel.profiles) { profile in
            let selected = profile.id == viewModel.activeProfileId
            Button {
              viewModel.selectProfile(profile.id)
            } label: {
              Label(profile.name, systemImage: "person.fill")
                .font(.caption.weight(.semibold))
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
                .foregroundStyle(selected ? Color.electricCyan : .white)
                .background(selected ? Color.electricCyan.opacity(0.16) : Color.midnightSlate, in: Capsule())
                .overlay(Capsule().stroke(selected ? Color.electricCyan : Color.subtleOutline, lineWidth: 1))
            }
          }
        }
      }
    }
  }

  private var savedConnectionsSection: some View {
    VStack(alignment: .leading, spacing: 10) {
      Text("SAVED TV CONNECTIONS")
        .font(.caption.weight(.semibold))
        .foregroundStyle(.secondary)

      let filtered = viewModel.savedConnections.filter { $0.profileId == viewModel.activeProfileId }
      let items = filtered.isEmpty ? viewModel.savedConnections : filtered

      if items.isEmpty {
        Text("No saved TVs yet. Scan Wi-Fi below or enter an IP address.")
          .font(.caption)
          .foregroundStyle(.secondary)
          .padding(16)
          .frame(maxWidth: .infinity, alignment: .leading)
          .background(Color.midnightSlate, in: RoundedRectangle(cornerRadius: 16))
          .overlay(RoundedRectangle(cornerRadius: 16).stroke(Color.subtleOutline, lineWidth: 1))
      } else {
        ForEach(items) { saved in
          HStack(spacing: 12) {
            Image(systemName: "tv.fill")
              .foregroundStyle(Color.electricCyan)
              .frame(width: 40, height: 40)
              .background(Color.raisedPod, in: RoundedRectangle(cornerRadius: 10))
            VStack(alignment: .leading, spacing: 2) {
              Text(saved.customName)
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(.white)
              Text("\(saved.ipAddress):\(saved.port)")
                .font(.caption2.monospaced())
                .foregroundStyle(.secondary)
            }
            Spacer()
            Button { onEditSaved(saved) } label: {
              Image(systemName: "pencil").foregroundStyle(.secondary)
            }
            Button(role: .destructive) { viewModel.deleteSavedTv(saved.id) } label: {
              Image(systemName: "trash").foregroundStyle(Color.crimsonPower)
            }
          }
          .padding(14)
          .background(Color.midnightSlate, in: RoundedRectangle(cornerRadius: 16))
          .overlay(RoundedRectangle(cornerRadius: 16).stroke(Color.subtleOutline, lineWidth: 1))
          .onTapGesture { viewModel.connectToSavedTv(saved) }
        }
      }
    }
  }

  private var discoverySection: some View {
    VStack(alignment: .leading, spacing: 12) {
      HStack {
        Text("DISCOVERED ON WI-FI")
          .font(.caption.weight(.semibold))
          .foregroundStyle(.secondary)
        Spacer()
        if viewModel.isScanning {
          Text("Scanning...")
            .font(.caption.weight(.semibold))
            .foregroundStyle(Color.electricCyan)
        }
      }

      HStack(spacing: 10) {
        Button(action: onOpenManualIp) {
          Label("Manual IP", systemImage: "link")
            .font(.subheadline.weight(.semibold))
            .frame(maxWidth: .infinity)
            .frame(height: 46)
            .foregroundStyle(.white)
            .background(Color.midnightSlate, in: RoundedRectangle(cornerRadius: 14))
            .overlay(RoundedRectangle(cornerRadius: 14).stroke(Color.subtleOutline, lineWidth: 1))
        }

        Button {
          if viewModel.isScanning {
            viewModel.stopDiscoveryScan()
          } else {
            viewModel.startDiscoveryScan()
          }
        } label: {
          Label(
            viewModel.isScanning ? "Stop Scan" : "Scan Wi-Fi",
            systemImage: viewModel.isScanning ? "stop.fill" : "arrow.clockwise"
          )
          .font(.subheadline.weight(.semibold))
          .frame(maxWidth: .infinity)
          .frame(height: 46)
          .foregroundStyle(.black)
          .background(Color.electricCyan, in: RoundedRectangle(cornerRadius: 14))
        }
      }

      if let scanErr = viewModel.scanError {
        Text(scanErr)
          .font(.caption)
          .foregroundStyle(Color.crimsonPower)
          .padding(12)
          .frame(maxWidth: .infinity, alignment: .leading)
          .background(Color.crimsonPower.opacity(0.14), in: RoundedRectangle(cornerRadius: 12))
      }

      if viewModel.discoveredTvs.isEmpty {
        VStack(spacing: 12) {
          RadarScopeView(isScanning: viewModel.isScanning)
            .frame(width: 104, height: 104)
          Text(viewModel.isScanning ? "Scanning local Wi-Fi..." : "Wi-Fi Discovery Idle")
            .font(.subheadline.weight(.semibold))
            .foregroundStyle(.white)
          Text(
            viewModel.isScanning
              ? "Listening for _androidtvremote2._tcp devices"
              : "Tap Scan Wi-Fi to discover Android TVs on your network"
          )
          .font(.caption)
          .foregroundStyle(.secondary)
          .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .padding(22)
        .background(Color.midnightSlate, in: RoundedRectangle(cornerRadius: 18))
        .overlay(
          RoundedRectangle(cornerRadius: 18)
            .stroke(viewModel.isScanning ? Color.electricCyan.opacity(0.35) : Color.subtleOutline, lineWidth: 1)
        )
      } else {
        ForEach(viewModel.discoveredTvs) { tv in
          HStack(spacing: 12) {
            Image(systemName: "tv")
              .foregroundStyle(Color.electricCyan)
              .frame(width: 40, height: 40)
              .background(Color.raisedPod, in: RoundedRectangle(cornerRadius: 10))
            VStack(alignment: .leading, spacing: 2) {
              Text(tv.displayName)
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(.white)
                .lineLimit(1)
              Text("\(tv.ipAddress):\(tv.port)")
                .font(.caption2.monospaced())
                .foregroundStyle(.secondary)
            }
            Spacer()
            Button { onSaveDiscovered(tv) } label: {
              Image(systemName: "bookmark.fill")
                .foregroundStyle(Color.electricCyan)
                .frame(width: 36, height: 36)
            }
            Button("Connect") {
              viewModel.connectToDiscoveredTv(tv)
            }
            .buttonStyle(.borderedProminent)
            .tint(Color.electricCyan)
            .foregroundStyle(.black)
          }
          .padding(14)
          .background(Color.midnightSlate, in: RoundedRectangle(cornerRadius: 16))
          .overlay(RoundedRectangle(cornerRadius: 16).stroke(Color.subtleOutline, lineWidth: 1))
        }
      }
    }
  }
}

private struct RadarScopeView: View {
  let isScanning: Bool
  @State private var sweepAngle: Double = 0
  @State private var pulseProgress: CGFloat = 0

  var body: some View {
    ZStack {
      ForEach([0.38, 0.68, 1.0], id: \.self) { scale in
        Circle()
          .stroke(
            isScanning ? Color.electricCyan.opacity(0.18) : Color.subtleOutline,
            lineWidth: 1
          )
          .scaleEffect(scale)
      }

      if isScanning {
        Circle()
          .stroke(Color.electricCyan.opacity(Double(1 - pulseProgress) * 0.45), lineWidth: 1.5)
          .scaleEffect(0.26 + 0.74 * pulseProgress)

        Circle()
          .fill(
            AngularGradient(
              colors: [.clear, .clear, Color.electricCyan.opacity(0.28)],
              center: .center
            )
          )
          .rotationEffect(.degrees(sweepAngle))
      }

      Circle()
        .fill(Color.electricCyan.opacity(0.16))
        .frame(width: 36, height: 36)
        .overlay(Circle().stroke(Color.electricCyan.opacity(0.5), lineWidth: 1))

      Image(systemName: "wifi")
        .foregroundStyle(Color.electricCyan)
    }
    .onAppear { startAnimationsIfNeeded() }
    .onChange(of: isScanning) { _ in startAnimationsIfNeeded() }
  }

  private func startAnimationsIfNeeded() {
    guard isScanning else {
      sweepAngle = 0
      pulseProgress = 0
      return
    }
    withAnimation(.linear(duration: 2.3).repeatForever(autoreverses: false)) {
      sweepAngle = 360
    }
    withAnimation(.easeOut(duration: 2.0).repeatForever(autoreverses: false)) {
      pulseProgress = 1
    }
  }
}

private struct RemoteControlScreen: View {
  @ObservedObject var viewModel: RemoteViewModel

  var body: some View {
    ScrollView {
      VStack(spacing: 16) {
        topBar
        modeAndKeyboardBar

        if viewModel.inputMode == .dpad {
          ClassicDPadView { code, cue in viewModel.sendKey(code, cue: cue) }
        } else {
          TouchpadSurfaceView { code, cue in viewModel.sendKey(code, cue: cue) }
        }

        navigationRow
        volumeChannelRow
        mediaBar
        shortcutsGrid
      }
      .padding(.horizontal, 18)
      .padding(.vertical, 12)
    }
  }

  private var topBar: some View {
    HStack(spacing: 10) {
      Button {
        viewModel.showConnectionHub = true
      } label: {
        HStack(spacing: 10) {
          Circle()
            .fill(statusDotColor)
            .frame(width: 10, height: 10)
          VStack(alignment: .leading, spacing: 2) {
            Text(statusTitle)
              .font(.subheadline.weight(.semibold))
              .foregroundStyle(.white)
              .lineLimit(1)
            Text(statusSubtitle)
              .font(.caption2)
              .foregroundStyle(.secondary)
              .lineLimit(1)
          }
          Spacer()
          Image(systemName: "arrow.left.arrow.right")
            .foregroundStyle(Color.electricCyan)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 10)
        .background(Color.midnightSlate, in: RoundedRectangle(cornerRadius: 16))
        .overlay(RoundedRectangle(cornerRadius: 16).stroke(Color.subtleOutline, lineWidth: 1))
      }

      Button {
        viewModel.showSettingsSheet = true
      } label: {
        Image(systemName: "gearshape.fill")
          .frame(width: 46, height: 46)
          .foregroundStyle(.white)
          .background(Color.midnightSlate, in: Circle())
      }

      Button {
        viewModel.sendKey(TvKeyCode.power, cue: .powerOrApp)
      } label: {
        Image(systemName: "power")
          .frame(width: 46, height: 46)
          .foregroundStyle(Color.crimsonPower)
          .background(Color.crimsonPower.opacity(0.18), in: Circle())
          .overlay(Circle().stroke(Color.crimsonPower.opacity(0.6), lineWidth: 1))
      }
    }
  }

  private var statusTitle: String {
    switch viewModel.connectionStatus {
    case .connected(let name, _, _, _): return name
    case .connecting(let name, _, _): return "Connecting to \(name)..."
    case .pairing(_, _, _, _, let serverName, _, _): return "Pairing \(serverName)"
    case .error: return "Connection Lost"
    case .disconnected: return "No TV Connected"
    }
  }

  private var statusSubtitle: String {
    switch viewModel.connectionStatus {
    case .connected(_, _, let ip, let port): return "\(ip):\(port)"
    case .error(let msg, _, _, _): return msg
    default: return "Tap to select or pair a TV"
    }
  }

  private var statusDotColor: Color {
    switch viewModel.connectionStatus {
    case .connected: return .green
    case .connecting, .pairing: return .orange
    case .error: return Color.crimsonPower
    case .disconnected: return .gray
    }
  }

  private var modeAndKeyboardBar: some View {
    HStack {
      HStack(spacing: 4) {
        modePill("D-Pad", icon: nil, mode: .dpad)
        modePill("Touchpad", icon: "hand.draw.fill", mode: .touchpad)
      }
      .padding(4)
      .background(Color.midnightSlate, in: Capsule())
      .overlay(Capsule().stroke(Color.subtleOutline, lineWidth: 1))

      Spacer()

      HStack(spacing: 8) {
        Button {
          viewModel.sendKey(TvKeyCode.search, cue: .confirmChime)
        } label: {
          Image(systemName: "magnifyingglass")
            .frame(width: 46, height: 46)
            .foregroundStyle(.white)
            .background(Color.midnightSlate, in: Circle())
            .overlay(Circle().stroke(Color.subtleOutline, lineWidth: 1))
        }

        Button {
          viewModel.showKeyboardSheet = true
        } label: {
          Image(systemName: "keyboard")
            .frame(width: 46, height: 46)
            .foregroundStyle(viewModel.tvImeState.isImeRequested ? Color.electricCyan : .white)
            .background(Color.midnightSlate, in: Circle())
            .overlay(
              Circle().stroke(
                viewModel.tvImeState.isImeRequested ? Color.electricCyan : Color.subtleOutline,
                lineWidth: 1
              )
            )
        }
      }
    }
  }

  private func modePill(_ title: String, icon: String?, mode: RemoteInputMode) -> some View {
    let selected = viewModel.inputMode == mode
    let tint: Color = selected ? .electricCyan : .secondary
    return Button {
      viewModel.inputMode = mode
    } label: {
      HStack(spacing: 5) {
        if let icon {
          Image(systemName: icon)
            .font(.caption.weight(.semibold))
        } else {
          DPadModeGlyphView(tint: tint)
            .frame(width: 15, height: 15)
        }
        Text(title)
          .font(.caption.weight(.semibold))
      }
      .padding(.horizontal, 12)
      .padding(.vertical, 8)
      .foregroundStyle(tint)
      .background(selected ? Color.electricCyan.opacity(0.18) : .clear, in: Capsule())
    }
  }

  private var navigationRow: some View {
    HStack(spacing: 10) {
      navButton("Back", icon: "arrow.uturn.backward") { viewModel.sendKey(TvKeyCode.back, cue: .backHome) }
      navButton("Home", icon: "house.fill") { viewModel.sendKey(TvKeyCode.home, cue: .backHome) }
      navButton("Menu", icon: "line.3.horizontal") { viewModel.sendKey(TvKeyCode.menu, cue: .navTick) }
      Button {
        viewModel.sendKey(TvKeyCode.tvInput, cue: .navTick)
      } label: {
        VStack(spacing: 4) {
          TvInputGlyphView(tint: .white)
            .frame(width: 18, height: 18)
          Text("Input").font(.caption2.weight(.medium))
        }
        .foregroundStyle(.white)
        .frame(maxWidth: .infinity)
        .frame(height: 58)
        .background(Color.raisedPod, in: RoundedRectangle(cornerRadius: 16))
        .overlay(RoundedRectangle(cornerRadius: 16).stroke(Color.subtleOutline, lineWidth: 1))
      }
    }
  }

  private func navButton(_ label: String, icon: String, action: @escaping () -> Void) -> some View {
    Button(action: action) {
      VStack(spacing: 4) {
        Image(systemName: icon).font(.callout)
        Text(label).font(.caption2.weight(.medium))
      }
      .foregroundStyle(.white)
      .frame(maxWidth: .infinity)
      .frame(height: 58)
      .background(Color.raisedPod, in: RoundedRectangle(cornerRadius: 16))
      .overlay(RoundedRectangle(cornerRadius: 16).stroke(Color.subtleOutline, lineWidth: 1))
    }
  }

  private var volumeChannelRow: some View {
    HStack(spacing: 10) {
      HStack(spacing: 0) {
        Button { viewModel.sendKey(TvKeyCode.volumeDown, cue: .volumeStep) } label: {
          Image(systemName: "minus").frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        Text("VOL").font(.caption2.weight(.bold)).foregroundStyle(.secondary)
        Button { viewModel.sendKey(TvKeyCode.volumeUp, cue: .volumeStep) } label: {
          Image(systemName: "plus").frame(maxWidth: .infinity, maxHeight: .infinity)
        }
      }
      .foregroundStyle(.white)
      .frame(maxWidth: .infinity)
      .frame(height: 56)
      .background(Color.raisedPod, in: RoundedRectangle(cornerRadius: 18))
      .overlay(RoundedRectangle(cornerRadius: 18).stroke(Color.subtleOutline, lineWidth: 1))

      Button { viewModel.sendKey(TvKeyCode.volumeMute, cue: .volumeStep) } label: {
        Image(systemName: "speaker.slash.fill")
          .foregroundStyle(.white)
          .frame(width: 56, height: 56)
          .background(Color.raisedPod, in: RoundedRectangle(cornerRadius: 18))
          .overlay(RoundedRectangle(cornerRadius: 18).stroke(Color.subtleOutline, lineWidth: 1))
      }

      HStack(spacing: 0) {
        Button { viewModel.sendKey(TvKeyCode.channelDown, cue: .navTick) } label: {
          Image(systemName: "chevron.down").frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        Text("CH").font(.caption2.weight(.bold)).foregroundStyle(.secondary)
        Button { viewModel.sendKey(TvKeyCode.channelUp, cue: .navTick) } label: {
          Image(systemName: "chevron.up").frame(maxWidth: .infinity, maxHeight: .infinity)
        }
      }
      .foregroundStyle(.white)
      .frame(maxWidth: .infinity)
      .frame(height: 56)
      .background(Color.raisedPod, in: RoundedRectangle(cornerRadius: 18))
      .overlay(RoundedRectangle(cornerRadius: 18).stroke(Color.subtleOutline, lineWidth: 1))
    }
  }

  private var mediaBar: some View {
    HStack(spacing: 8) {
      mediaButton("backward.end.fill") { viewModel.sendKey(TvKeyCode.mediaPrevious) }
      mediaButton("backward.fill") { viewModel.sendKey(TvKeyCode.mediaRewind) }
      Button { viewModel.sendKey(TvKeyCode.mediaPlayPause, cue: .confirmChime) } label: {
        Image(systemName: "playpause.fill")
          .foregroundStyle(Color.electricCyan)
          .frame(maxWidth: .infinity)
          .frame(height: 50)
          .background(Color.electricCyan.opacity(0.18), in: RoundedRectangle(cornerRadius: 16))
          .overlay(RoundedRectangle(cornerRadius: 16).stroke(Color.electricCyan.opacity(0.5), lineWidth: 1))
      }
      mediaButton("forward.fill") { viewModel.sendKey(TvKeyCode.mediaFastForward) }
      mediaButton("forward.end.fill") { viewModel.sendKey(TvKeyCode.mediaNext) }
    }
  }

  private func mediaButton(_ systemName: String, action: @escaping () -> Void) -> some View {
    Button(action: action) {
      Image(systemName: systemName)
        .foregroundStyle(.white)
        .frame(maxWidth: .infinity)
        .frame(height: 50)
        .background(Color.raisedPod, in: RoundedRectangle(cornerRadius: 16))
        .overlay(RoundedRectangle(cornerRadius: 16).stroke(Color.subtleOutline, lineWidth: 1))
    }
  }

  private var shortcutsGrid: some View {
    VStack(alignment: .leading, spacing: 10) {
      Text("QUICK-LAUNCH STREAMING")
        .font(.caption.weight(.semibold))
        .foregroundStyle(.secondary)

      LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 10), count: 3), spacing: 10) {
        ForEach(TvAppShortcuts.all) { shortcut in
          Button {
            viewModel.launchShortcut(shortcut)
          } label: {
            VStack(spacing: 6) {
              OttAppIconTileView(shortcut: shortcut)
                .frame(width: 42, height: 42)
              Text(shortcut.name)
                .font(.caption2.weight(.semibold))
                .foregroundStyle(.white)
                .lineLimit(1)
            }
            .frame(maxWidth: .infinity)
            .frame(height: 86)
            .background(
              LinearGradient(
                colors: [shortcut.accentColor.opacity(0.14), Color.midnightSlate],
                startPoint: .top,
                endPoint: .bottom
              ),
              in: RoundedRectangle(cornerRadius: 18)
            )
            .overlay(RoundedRectangle(cornerRadius: 18).stroke(shortcut.accentColor.opacity(0.35), lineWidth: 1))
          }
        }
      }
    }
  }
}

private struct ClassicDPadView: View {
  let onKey: (Int, FeedbackCue) -> Void

  var body: some View {
    ZStack {
      Circle()
        .fill(Color.midnightSlate)
        .frame(width: 236, height: 236)
        .overlay(Circle().stroke(Color.subtleOutline, lineWidth: 1.5))

      Button { onKey(TvKeyCode.dpadUp, .navTick) } label: {
        Image(systemName: "chevron.up").font(.title2.weight(.semibold)).frame(width: 80, height: 58)
      }
      .foregroundStyle(.white)
      .offset(y: -82)

      Button { onKey(TvKeyCode.dpadDown, .navTick) } label: {
        Image(systemName: "chevron.down").font(.title2.weight(.semibold)).frame(width: 80, height: 58)
      }
      .foregroundStyle(.white)
      .offset(y: 82)

      Button { onKey(TvKeyCode.dpadLeft, .navTick) } label: {
        Image(systemName: "chevron.left").font(.title2.weight(.semibold)).frame(width: 58, height: 80)
      }
      .foregroundStyle(.white)
      .offset(x: -82)

      Button { onKey(TvKeyCode.dpadRight, .navTick) } label: {
        Image(systemName: "chevron.right").font(.title2.weight(.semibold)).frame(width: 58, height: 80)
      }
      .foregroundStyle(.white)
      .offset(x: 82)

      Button { onKey(TvKeyCode.dpadCenter, .confirmChime) } label: {
        Text("OK")
          .font(.headline.weight(.bold))
          .foregroundStyle(Color.electricCyan)
          .frame(width: 86, height: 86)
          .background(Color.electricCyan.opacity(0.18), in: Circle())
          .overlay(Circle().stroke(Color.electricCyan, lineWidth: 1.5))
      }
    }
    .frame(height: 256)
  }
}

private struct TouchpadSurfaceView: View {
  let onKey: (Int, FeedbackCue) -> Void
  @State private var gestureLabel = "Swipe to navigate • Tap for OK"

  var body: some View {
    VStack(spacing: 10) {
      Image(systemName: "hand.tap.fill")
        .font(.title)
        .foregroundStyle(Color.electricCyan)
      Text(gestureLabel)
        .font(.subheadline)
        .foregroundStyle(.secondary)
    }
    .frame(maxWidth: .infinity)
    .frame(height: 256)
    .background(Color.midnightSlate, in: RoundedRectangle(cornerRadius: 26))
    .overlay(RoundedRectangle(cornerRadius: 26).stroke(Color.electricCyan.opacity(0.45), lineWidth: 1.5))
    .contentShape(Rectangle())
    .onTapGesture {
      gestureLabel = "OK Selected"
      onKey(TvKeyCode.dpadCenter, .confirmChime)
    }
    .gesture(
      DragGesture(minimumDistance: 36)
        .onEnded { value in
          let dx = value.translation.width
          let dy = value.translation.height
          if abs(dx) > abs(dy) {
            if dx > 0 {
              gestureLabel = "Swipe →"
              onKey(TvKeyCode.dpadRight, .navTick)
            } else {
              gestureLabel = "Swipe ←"
              onKey(TvKeyCode.dpadLeft, .navTick)
            }
          } else {
            if dy > 0 {
              gestureLabel = "Swipe ↓"
              onKey(TvKeyCode.dpadDown, .navTick)
            } else {
              gestureLabel = "Swipe ↑"
              onKey(TvKeyCode.dpadUp, .navTick)
            }
          }
        }
    )
  }
}

private struct PairingPinSheetView: View {
  let serverName: String
  let initialCustomName: String
  let errorMessage: String?
  let isSubmitting: Bool
  let onVerify: (String, String) -> Void
  let onCancel: () -> Void

  @State private var pin = ""
  @State private var customName = ""

  var body: some View {
    NavigationStack {
      Form {
        Section {
          Text("Enter the 6-character hexadecimal code displayed on \(serverName).")
            .font(.subheadline)
            .foregroundStyle(.secondary)
          TextField("e.g. A4F92C", text: $pin)
            .textInputAutocapitalization(.characters)
            .autocorrectionDisabled()
            .font(.title3.monospaced())
            .onChange(of: pin) { raw in
              let filtered = raw.uppercased().filter { $0.isHexDigit }
              pin = String(filtered.prefix(6))
            }
          TextField("Save TV As", text: $customName)
        }
        if let errorMessage {
          Section {
            Text(errorMessage).foregroundStyle(Color.crimsonPower)
          }
        }
      }
      .navigationTitle("Pair TV")
      .toolbar {
        ToolbarItem(placement: .cancellationAction) {
          Button("Cancel", action: onCancel)
        }
        ToolbarItem(placement: .confirmationAction) {
          Button("Verify & Pair") {
            onVerify(pin, customName)
          }
          .disabled(pin.count != 6 || isSubmitting)
        }
      }
      .onAppear { customName = initialCustomName }
    }
    .preferredColorScheme(.dark)
  }
}

private struct ManualIpSheetView: View {
  let onConnect: (String, UInt16, String) -> Void
  @Environment(\.dismiss) private var dismiss

  @State private var ip = ""
  @State private var portText = "6466"
  @State private var nickname = ""

  var body: some View {
    NavigationStack {
      Form {
        Section("Android TV Address") {
          TextField("IPv4 Address (e.g. 192.168.1.42)", text: $ip)
            .keyboardType(.decimalPad)
          TextField("Port", text: $portText)
            .keyboardType(.numberPad)
          TextField("TV Nickname", text: $nickname)
        }
      }
      .navigationTitle("Connect by IP")
      .toolbar {
        ToolbarItem(placement: .cancellationAction) {
          Button("Cancel") { dismiss() }
        }
        ToolbarItem(placement: .confirmationAction) {
          Button("Connect") {
            let port = UInt16(portText) ?? RemoteControlClient.defaultRemotePort
            onConnect(ip, port, nickname)
          }
          .disabled(ip.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
        }
      }
    }
    .preferredColorScheme(.dark)
  }
}

private struct SaveOrEditTvSheetView: View {
  let title: String
  let initialName: String
  let initialIp: String
  let initialPort: UInt16
  let initialProfileId: String
  let profiles: [UserProfile]
  let allowEditAddress: Bool
  let onSave: (String, String, UInt16, String) -> Void

  @Environment(\.dismiss) private var dismiss
  @State private var name = ""
  @State private var ip = ""
  @State private var portText = ""
  @State private var selectedProfileId = ""

  var body: some View {
    NavigationStack {
      Form {
        Section("TV Details") {
          TextField("Custom TV Name", text: $name)
          if allowEditAddress {
            TextField("IPv4 Address", text: $ip)
            TextField("Port", text: $portText).keyboardType(.numberPad)
          }
        }
        Section("Profile") {
          Picker("Assign to Profile", selection: $selectedProfileId) {
            ForEach(profiles) { prof in
              Text(prof.name).tag(prof.id)
            }
          }
        }
      }
      .navigationTitle(title)
      .toolbar {
        ToolbarItem(placement: .cancellationAction) {
          Button("Cancel") { dismiss() }
        }
        ToolbarItem(placement: .confirmationAction) {
          Button("Save") {
            onSave(name, ip, UInt16(portText) ?? initialPort, selectedProfileId)
            dismiss()
          }
        }
      }
      .onAppear {
        name = initialName
        ip = initialIp
        portText = String(initialPort)
        selectedProfileId = initialProfileId
      }
    }
    .preferredColorScheme(.dark)
  }
}

private struct KeyboardSearchSheetView: View {
  @ObservedObject var viewModel: RemoteViewModel
  @Environment(\.dismiss) private var dismiss

  @State private var query = ""
  @State private var useDirectKeys = false

  var body: some View {
    NavigationStack {
      VStack(spacing: 14) {
        Picker("Input Mode", selection: $useDirectKeys) {
          Text("TV IME Sync").tag(false)
          Text("Direct Keystrokes").tag(true)
        }
        .pickerStyle(.segmented)

        TextField("Search or type on TV...", text: $query)
          .textFieldStyle(.roundedBorder)
          .submitLabel(.search)
          .onChange(of: query) { updated in
            if !useDirectKeys {
              viewModel.sendKeyboardImeText(updated, previousText: viewModel.tvImeState.currentText)
            }
          }
          .onSubmit {
            viewModel.submitKeyboardSearch(query, useDirectKeys: useDirectKeys)
          }

        HStack(spacing: 10) {
          Button("TV Search") { viewModel.sendKey(TvKeyCode.search, cue: .confirmChime) }
            .buttonStyle(.bordered)
            .frame(maxWidth: .infinity)
          Button(useDirectKeys ? "Type & Go" : "Search") {
            viewModel.submitKeyboardSearch(query, useDirectKeys: useDirectKeys)
          }
          .buttonStyle(.borderedProminent)
          .tint(Color.electricCyan)
          .foregroundStyle(.black)
          .frame(maxWidth: .infinity)
        }

        HStack(spacing: 8) {
          quickBtn("Voice", icon: "mic.fill") { viewModel.sendKey(TvKeyCode.voiceAssist, cue: .powerOrApp) }
          quickBtn("Space", icon: "space") { viewModel.sendKey(TvKeyCode.space) }
          quickBtn("Delete", icon: "delete.left.fill") { viewModel.sendKey(TvKeyCode.del, cue: .backHome) }
          quickBtn("Enter", icon: "return") { viewModel.sendKey(TvKeyCode.enter, cue: .confirmChime) }
        }
        Spacer()
      }
      .padding(20)
      .navigationTitle("TV Keyboard")
      .toolbar {
        ToolbarItem(placement: .cancellationAction) {
          Button("Done") { dismiss() }
        }
      }
      .onAppear { query = viewModel.tvImeState.currentText }
    }
    .preferredColorScheme(.dark)
  }

  private func quickBtn(_ title: String, icon: String, action: @escaping () -> Void) -> some View {
    Button(action: action) {
      VStack(spacing: 4) {
        Image(systemName: icon)
        Text(title).font(.caption2)
      }
      .frame(maxWidth: .infinity)
      .frame(height: 50)
      .background(Color.raisedPod, in: RoundedRectangle(cornerRadius: 12))
    }
  }
}

private struct SettingsSheetView: View {
  @ObservedObject var viewModel: RemoteViewModel
  @Environment(\.dismiss) private var dismiss

  var body: some View {
    NavigationStack {
      Form {
        Section("Feedback") {
          Toggle(
            "Haptic Feedback",
            isOn: Binding(
              get: { viewModel.settings.hapticsEnabled },
              set: {
                var copy = viewModel.settings
                copy.hapticsEnabled = $0
                viewModel.saveSettings(copy)
              }
            )
          )
          Toggle(
            "Sound Effects",
            isOn: Binding(
              get: { viewModel.settings.soundEnabled },
              set: {
                var copy = viewModel.settings
                copy.soundEnabled = $0
                viewModel.saveSettings(copy)
              }
            )
          )
        }

        Section("Connection") {
          Toggle(
            "Auto-Reconnect on Launch",
            isOn: Binding(
              get: { viewModel.settings.autoReconnectEnabled },
              set: {
                var copy = viewModel.settings
                copy.autoReconnectEnabled = $0
                viewModel.saveSettings(copy)
              }
            )
          )
        }

        Section("Profiles") {
          ForEach(viewModel.profiles) { prof in
            HStack {
              Text(prof.name)
              Spacer()
              if prof.id == viewModel.activeProfileId {
                Image(systemName: "checkmark.circle.fill").foregroundStyle(Color.electricCyan)
              }
            }
            .contentShape(Rectangle())
            .onTapGesture { viewModel.selectProfile(prof.id) }
            .swipeActions {
              if viewModel.profiles.count > 1 {
                Button("Delete", role: .destructive) { viewModel.deleteProfile(prof.id) }
              }
            }
          }
        }
      }
      .navigationTitle("Settings")
      .toolbar {
        ToolbarItem(placement: .confirmationAction) {
          Button("Done") { dismiss() }
        }
      }
    }
    .preferredColorScheme(.dark)
  }
}
