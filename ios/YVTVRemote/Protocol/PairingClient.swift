import CryptoKit
import Foundation
import Network
import Security
import SwiftProtobuf
import UIKit

enum PairingStartResult {
  case awaitingPin(session: ActivePairingSession, serverName: String)
  case failed(String)
}

enum PinSubmitResult {
  case success
  case invalidPin(String)
  case rejectedByTv(String)
  case connectionLost(String)
}

final class ActivePairingSession {
  private let connection: NWConnection
  private let stream: DelimitedConnectionStream
  private let clientPublicKey: RSAPublicKeyComponents
  private let serverPublicKey: RSAPublicKeyComponents

  init(
    connection: NWConnection,
    stream: DelimitedConnectionStream,
    clientPublicKey: RSAPublicKeyComponents,
    serverPublicKey: RSAPublicKeyComponents
  ) {
    self.connection = connection
    self.stream = stream
    self.clientPublicKey = clientPublicKey
    self.serverPublicKey = serverPublicKey
  }

  func submitPin(_ rawPin: String) async -> PinSubmitResult {
    let pin = rawPin.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
    guard pin.count == 6, pin.allSatisfy({ $0.isHexDigit }) else {
      return .invalidPin("Enter the exact 6-character hexadecimal code shown on your TV.")
    }

    guard let expectedFirstByte = UInt8(pin.prefix(2), radix: 16),
          let nonceBytes = PairingClient.hexToData(String(pin.dropFirst(2))) else {
      return .invalidPin("Invalid hexadecimal PIN format.")
    }

    var sha = SHA256()
    sha.update(data: clientPublicKey.modulus)
    sha.update(data: clientPublicKey.exponent)
    sha.update(data: serverPublicKey.modulus)
    sha.update(data: serverPublicKey.exponent)
    sha.update(data: nonceBytes)
    let digest = Data(sha.finalize())

    guard digest.first == expectedFirstByte else {
      return .invalidPin("PIN verification mismatch. Please double-check the 6 characters on your TV.")
    }

    do {
      var secret = Pairing_PairingSecret()
      secret.secret = digest

      var msg = Pairing_PairingMessage()
      msg.protocolVersion = 2
      msg.status = Pairing_PairingMessage.Status(rawValue: 200) ?? .ok
      msg.pairingSecret = secret

      try await stream.sendFrame(try msg.serializedData())

      let replyData = try await stream.readNextFrame()
      let reply = try Pairing_PairingMessage(serializedBytes: replyData)
      close()

      if reply.status.rawValue == 402 {
        return .rejectedByTv("TV rejected the pairing PIN (STATUS_BAD_SECRET). Please start pairing again.")
      }
      guard reply.status.rawValue == 200, reply.hasPairingSecretAck else {
        return .rejectedByTv("Unexpected pairing secret response from TV (\(reply.status.rawValue)).")
      }
      return .success
    } catch {
      close()
      return .connectionLost(error.localizedDescription)
    }
  }

  func close() {
    connection.cancel()
  }
}

final class PairingClient {
  static let defaultPairingPort: UInt16 = 6467
  private let certificateManager: CertificateManager
  private let queue = DispatchQueue(label: "com.aistudio.yvtvremote.pairing")

  init(certificateManager: CertificateManager = .shared) {
    self.certificateManager = certificateManager
  }

  func startPairing(host: String, port: UInt16 = defaultPairingPort) async -> PairingStartResult {
    var connection: NWConnection?
    do {
      let (identity, clientPublicKey) = try certificateManager.loadOrCreateClientIdentity()
      var capturedServerKey: RSAPublicKeyComponents?
      let keyLock = NSLock()

      let tlsOptions = NWProtocolTLS.Options()
      if let secIdentity = sec_identity_create(identity) {
        sec_protocol_options_set_local_identity(tlsOptions.securityProtocolOptions, secIdentity)
      }
      sec_protocol_options_set_verify_block(
        tlsOptions.securityProtocolOptions,
        { _, trust, complete in
          let secTrust = sec_trust_copy_ref(trust).takeRetainedValue()
          if let extracted = self.certificateManager.extractPublicKey(from: secTrust) {
            keyLock.lock()
            capturedServerKey = extracted
            keyLock.unlock()
          }
          complete(true)
        },
        queue
      )

      let tcpOptions = NWProtocolTCP.Options()
      tcpOptions.connectionTimeout = 8
      let params = NWParameters(tls: tlsOptions, tcp: tcpOptions)

      guard let nwPort = NWEndpoint.Port(rawValue: port) else {
        return .failed("Invalid pairing port \(port).")
      }

      let nwConnection = NWConnection(host: NWEndpoint.Host(host), port: nwPort, using: params)
      connection = nwConnection
      try await waitForReady(nwConnection)

      keyLock.lock()
      let serverKey = capturedServerKey
      keyLock.unlock()

      guard let serverPublicKey = serverKey else {
        nwConnection.cancel()
        return .failed("Unable to extract TV RSA public key from TLS certificate.")
      }

      let stream = DelimitedConnectionStream(connection: nwConnection)
      let deviceName = await MainActor.run { UIDevice.current.name }

      // Step 1: PairingRequest -> PairingRequestAck
      var req = Pairing_PairingRequest()
      req.serviceName = "com.example.yvtvremote"
      req.clientName = deviceName.isEmpty ? "YV-TV Remote iOS" : deviceName

      var reqMsg = Pairing_PairingMessage()
      reqMsg.protocolVersion = 2
      reqMsg.status = Pairing_PairingMessage.Status(rawValue: 200) ?? .ok
      reqMsg.pairingRequest = req
      try await stream.sendFrame(try reqMsg.serializedData())

      let reqAck = try Pairing_PairingMessage(serializedBytes: try await stream.readNextFrame())
      guard reqAck.status.rawValue == 200, reqAck.hasPairingRequestAck else {
        nwConnection.cancel()
        return .failed("TV rejected PairingRequest (\(reqAck.status.rawValue)).")
      }
      let serverName = reqAck.pairingRequestAck.serverName.isEmpty ? host : reqAck.pairingRequestAck.serverName

      // Step 2: PairingOption (HEXADECIMAL length 6) -> TV PairingOption
      var hexEncoding = Pairing_PairingEncoding()
      hexEncoding.type = Pairing_PairingEncoding.EncodingType(rawValue: 3) ?? .hexadecimal
      hexEncoding.symbolLength = 6

      var opt = Pairing_PairingOption()
      opt.inputEncodings = [hexEncoding]
      opt.preferredRole = Pairing_RoleType(rawValue: 1) ?? .input

      var optMsg = Pairing_PairingMessage()
      optMsg.protocolVersion = 2
      optMsg.status = Pairing_PairingMessage.Status(rawValue: 200) ?? .ok
      optMsg.pairingOption = opt
      try await stream.sendFrame(try optMsg.serializedData())

      let tvOpt = try Pairing_PairingMessage(serializedBytes: try await stream.readNextFrame())
      guard tvOpt.status.rawValue == 200, tvOpt.hasPairingOption else {
        nwConnection.cancel()
        return .failed("TV rejected PairingOption (\(tvOpt.status.rawValue)).")
      }

      // Step 3: PairingConfiguration -> PairingConfigurationAck
      var config = Pairing_PairingConfiguration()
      config.encoding = hexEncoding
      config.clientRole = Pairing_RoleType(rawValue: 1) ?? .input

      var cfgMsg = Pairing_PairingMessage()
      cfgMsg.protocolVersion = 2
      cfgMsg.status = Pairing_PairingMessage.Status(rawValue: 200) ?? .ok
      cfgMsg.pairingConfiguration = config
      try await stream.sendFrame(try cfgMsg.serializedData())

      let cfgAck = try Pairing_PairingMessage(serializedBytes: try await stream.readNextFrame())
      guard cfgAck.status.rawValue == 200, cfgAck.hasPairingConfigurationAck else {
        nwConnection.cancel()
        return .failed("TV rejected PairingConfiguration (\(cfgAck.status.rawValue)).")
      }

      let session = ActivePairingSession(
        connection: nwConnection,
        stream: stream,
        clientPublicKey: clientPublicKey,
        serverPublicKey: serverPublicKey
      )
      return .awaitingPin(session: session, serverName: serverName)
    } catch {
      connection?.cancel()
      return .failed(error.localizedDescription)
    }
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
          if RemoteControlClient.isTlsOrPairingError(error) {
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

  static func hexToData(_ hex: String) -> Data? {
    guard hex.count % 2 == 0 else { return nil }
    var data = Data(capacity: hex.count / 2)
    var index = hex.startIndex
    while index < hex.endIndex {
      let next = hex.index(index, offsetBy: 2)
      guard let byte = UInt8(hex[index..<next], radix: 16) else { return nil }
      data.append(byte)
      index = next
    }
    return data
  }
}
