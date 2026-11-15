import Foundation
import Network
import Security
import SwiftProtobuf
import UIKit

struct TvImeState: Equatable {
  var imeCounter: Int32 = 0
  var fieldCounter: Int32 = 0
  var currentText: String = ""
  var fieldLabel: String = ""
  var appLabel: String = ""
  var isImeRequested: Bool = false
}

enum RemoteSessionEvent: Equatable {
  case disconnected
  case connecting
  case connected
  case pairingRequired(String)
  case error(String)
}

@MainActor
final class RemoteControlClient: ObservableObject {
  static let defaultRemotePort: UInt16 = 6466

  @Published private(set) var sessionEvent: RemoteSessionEvent = .disconnected
  @Published private(set) var imeState = TvImeState()

  private let certificateManager: CertificateManager
  private let queue = DispatchQueue(label: "com.aistudio.yvtvremote.control")
  private var connection: NWConnection?
  private var stream: DelimitedConnectionStream?
  private var readerTask: Task<Void, Never>?

  init(certificateManager: CertificateManager = .shared) {
    self.certificateManager = certificateManager
  }

  func connect(host: String, port: UInt16 = defaultRemotePort) async {
    disconnect()
    sessionEvent = .connecting
    imeState = TvImeState()

    do {
      let (identity, _) = try certificateManager.loadOrCreateClientIdentity()
      let tlsOptions = NWProtocolTLS.Options()
      if let secIdentity = sec_identity_create(identity) {
        sec_protocol_options_set_local_identity(tlsOptions.securityProtocolOptions, secIdentity)
      }
      sec_protocol_options_set_verify_block(
        tlsOptions.securityProtocolOptions,
        { _, _, complete in
          complete(true)
        },
        queue
      )

      let tcpOptions = NWProtocolTCP.Options()
      tcpOptions.enableKeepalive = true
      tcpOptions.noDelay = true
      tcpOptions.connectionTimeout = 8

      guard let nwPort = NWEndpoint.Port(rawValue: port) else {
        sessionEvent = .error("Invalid remote port \(port).")
        return
      }

      let nwConnection = NWConnection(
        host: NWEndpoint.Host(host),
        port: nwPort,
        using: NWParameters(tls: tlsOptions, tcp: tcpOptions)
      )
      self.connection = nwConnection

      try await waitForReady(nwConnection)

      let activeStream = DelimitedConnectionStream(connection: nwConnection)
      self.stream = activeStream

      readerTask = Task { [weak self] in
        await self?.runReaderLoop(stream: activeStream)
      }
    } catch {
      cleanupConnectionOnly()
      if Self.isTlsOrPairingError(error) {
        sessionEvent = .pairingRequired("TV requires pairing before remote access (\(error.localizedDescription)).")
      } else {
        sessionEvent = .error(error.localizedDescription)
      }
    }
  }

  private func runReaderLoop(stream: DelimitedConnectionStream) async {
    var receivedAnyFrame = false
    do {
      while !Task.isCancelled {
        let frameData = try await stream.readNextFrame()
        receivedAnyFrame = true
        let message = try Remote_RemoteMessage(serializedBytes: frameData)

        if message.hasRemoteConfigure {
          try await sendConfigureAndActive(using: stream)
          sessionEvent = .connected
        } else if message.hasRemoteSetActive {
          if sessionEvent != .connected {
            sessionEvent = .connected
          }
        } else if message.hasRemotePingRequest {
          var pong = Remote_RemotePingResponse()
          pong.val1 = message.remotePingRequest.val1

          var reply = Remote_RemoteMessage()
          reply.remotePingResponse = pong
          try await sendMessage(reply, using: stream)
        } else if message.hasRemoteImeKeyInject {
          let ime = message.remoteImeKeyInject
          let status = ime.textFieldStatus
          imeState.imeCounter = ime.appInfo.counter
          imeState.fieldCounter = status.counterField
          imeState.currentText = status.value
          if !status.label.isEmpty { imeState.fieldLabel = status.label }
          if !ime.appInfo.label.isEmpty { imeState.appLabel = ime.appInfo.label }
        } else if message.hasRemoteImeShowRequest {
          let status = message.remoteImeShowRequest.remoteTextFieldStatus
          imeState.fieldCounter = status.counterField
          imeState.currentText = status.value
          if !status.label.isEmpty { imeState.fieldLabel = status.label }
          imeState.isImeRequested = true
        } else if message.hasRemoteImeBatchEdit {
          imeState.imeCounter = message.remoteImeBatchEdit.imeCounter
          imeState.fieldCounter = message.remoteImeBatchEdit.fieldCounter
        } else if message.hasRemoteStart {
          sessionEvent = .connected
        } else if message.hasRemoteError {
          sessionEvent = .error("Remote session error reported by TV.")
          break
        }
      }
    } catch {
      guard !Task.isCancelled else { return }
      if Self.isTlsOrPairingError(error) || (!receivedAnyFrame && sessionEvent == .connecting) {
        sessionEvent = .pairingRequired("Certificate not trusted by TV. Starting pairing...")
      } else if case DelimitedStreamError.endOfStream = error, sessionEvent == .connected {
        sessionEvent = .disconnected
      } else {
        sessionEvent = .error(error.localizedDescription)
      }
    }
    cleanupConnectionOnly()
  }

  private func sendConfigureAndActive(using stream: DelimitedConnectionStream) async throws {
    var deviceInfo = Remote_RemoteDeviceInfo()
    deviceInfo.model = UIDevice.current.model
    deviceInfo.vendor = "Apple"
    deviceInfo.unknown1 = 1
    deviceInfo.unknown2 = "1"
    deviceInfo.packageName = "com.example.yvtvremote"
    deviceInfo.appVersion = "1.0"

    var configure = Remote_RemoteConfigure()
    configure.code1 = 622
    configure.deviceInfo = deviceInfo

    var configMsg = Remote_RemoteMessage()
    configMsg.remoteConfigure = configure
    try await sendMessage(configMsg, using: stream)

    var setActive = Remote_RemoteSetActive()
    setActive.active = 622

    var activeMsg = Remote_RemoteMessage()
    activeMsg.remoteSetActive = setActive
    try await sendMessage(activeMsg, using: stream)
  }

  @discardableResult
  func sendKey(_ keyCode: Int, direction: Int = 3) async -> Bool {
    guard let stream else { return false }
    var inject = Remote_RemoteKeyInject()
    inject.keyCode = Remote_RemoteKeyCode(rawValue: keyCode) ?? Remote_RemoteKeyCode()
    inject.direction = Remote_RemoteDirection(rawValue: direction) ?? Remote_RemoteDirection()

    var msg = Remote_RemoteMessage()
    msg.remoteKeyInject = inject
    return (try? await sendMessage(msg, using: stream)) != nil
  }

  @discardableResult
  func sendImeText(_ newText: String, previousLength: Int) async -> Bool {
    guard let stream else { return false }
    var imeObj = Remote_RemoteImeObject()
    imeObj.start = 0
    imeObj.end = Int32(max(0, previousLength))
    imeObj.value = newText

    var editInfo = Remote_RemoteEditInfo()
    editInfo.insert = 1
    editInfo.textFieldStatus = imeObj

    var batch = Remote_RemoteImeBatchEdit()
    batch.imeCounter = imeState.imeCounter
    batch.fieldCounter = imeState.fieldCounter
    batch.editInfo = [editInfo]

    var msg = Remote_RemoteMessage()
    msg.remoteImeBatchEdit = batch
    do {
      try await sendMessage(msg, using: stream)
      imeState.currentText = newText
      return true
    } catch {
      return false
    }
  }

  @discardableResult
  func sendTextAsKeyEvents(_ text: String) async -> Bool {
    for ch in text {
      guard let code = Self.charToKeyCode(ch) else { continue }
      let ok = await sendKey(code)
      if !ok { return false }
      try? await Task.sleep(nanoseconds: 18_000_000)
    }
    return true
  }

  @discardableResult
  func launchApp(_ appLink: String) async -> Bool {
    guard let stream else { return false }
    var launch = Remote_RemoteAppLinkLaunchRequest()
    launch.appLink = appLink

    var msg = Remote_RemoteMessage()
    msg.remoteAppLinkLaunchRequest = launch
    return (try? await sendMessage(msg, using: stream)) != nil
  }

  func clearImeRequested() {
    imeState.isImeRequested = false
  }

  func disconnect() {
    readerTask?.cancel()
    readerTask = nil
    cleanupConnectionOnly()
    imeState = TvImeState()
    sessionEvent = .disconnected
  }

  private func cleanupConnectionOnly() {
    stream = nil
    connection?.cancel()
    connection = nil
  }

  private func sendMessage(_ message: Remote_RemoteMessage, using stream: DelimitedConnectionStream) async throws {
    let data = try message.serializedData()
    try await stream.sendFrame(data)
  }

  private func waitForReady(_ connection: NWConnection) async throws {
    try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
      let stateLock = NSLock()
      var resumed = false

      func finish(_ result: Result<Void, Error>) {
        stateLock.lock()
        guard !resumed else {
          stateLock.unlock()
          return
        }
        resumed = true
        stateLock.unlock()
        continuation.resume(with: result)
      }

      connection.stateUpdateHandler = { state in
        switch state {
        case .ready:
          finish(.success(()))
        case .waiting(let error):
          if Self.isTlsOrPairingError(error) {
            finish(.failure(error))
          }
        case .failed(let error):
          finish(.failure(error))
        case .cancelled:
          finish(.failure(DelimitedStreamError.endOfStream))
        default:
          break
        }
      }
      connection.start(queue: queue)
    }
  }

  nonisolated static func isTlsOrPairingError(_ error: Error) -> Bool {
    if case NWError.tls = error { return true }
    let desc = error.localizedDescription.uppercased()
    return desc.contains("CERTIFICATE") ||
      desc.contains("TLS") ||
      desc.contains("SSL") ||
      desc.contains("HANDSHAKE") ||
      desc.contains("ALERT")
  }

  static func charToKeyCode(_ ch: Character) -> Int? {
    let lower = Character(ch.lowercased())
    if let ascii = lower.asciiValue {
      if ascii >= 97 && ascii <= 122 {
        return 29 + Int(ascii - 97) // KEYCODE_A..Z
      }
      if ascii >= 48 && ascii <= 57 {
        return 7 + Int(ascii - 48) // KEYCODE_0..9
      }
    }
    switch ch {
    case " ": return 62
    case ",": return 55
    case ".": return 56
    case "-": return 69
    case "=": return 70
    case "'": return 75
    case "/": return 76
    case "@": return 77
    case "\n": return 66
    default: return nil
    }
  }
}

enum TvKeyCode {
  static let home = 3
  static let back = 4
  static let dpadUp = 19
  static let dpadDown = 20
  static let dpadLeft = 21
  static let dpadRight = 22
  static let dpadCenter = 23
  static let volumeUp = 24
  static let volumeDown = 25
  static let power = 26
  static let space = 62
  static let enter = 66
  static let del = 67
  static let menu = 82
  static let search = 84
  static let mediaPlayPause = 85
  static let mediaNext = 87
  static let mediaPrevious = 88
  static let mediaRewind = 89
  static let mediaFastForward = 90
  static let volumeMute = 164
  static let channelUp = 166
  static let channelDown = 167
  static let tvInput = 178
  static let voiceAssist = 231
}
