import Foundation
import Security

struct RSAPublicKeyComponents {
  let modulus: Data
  let exponent: Data
}

enum CertificateError: LocalizedError {
  case keyGenerationFailed(String)
  case signingFailed(String)
  case keychainStoreFailed(OSStatus)
  case invalidRsaPublicKey

  var errorDescription: String? {
    switch self {
    case .keyGenerationFailed(let reason): return "RSA key generation failed: \(reason)"
    case .signingFailed(let reason): return "Certificate signing failed: \(reason)"
    case .keychainStoreFailed(let status): return "Keychain error (\(status))"
    case .invalidRsaPublicKey: return "Unable to parse RSA public key"
    }
  }
}

final class CertificateManager {
  static let shared = CertificateManager()

  private let keyTag = "com.aistudio.yvtvremote.client.rsa2048".data(using: .utf8)!
  private let certLabel = "YV-TV Remote Client Identity"
  private let lock = NSLock()

  private init() {}

  func loadOrCreateClientIdentity() throws -> (identity: SecIdentity, publicKey: RSAPublicKeyComponents) {
    lock.lock()
    defer { lock.unlock() }

    if let existingIdentity = queryExistingIdentity(),
       let pubComponents = extractPublicKey(from: existingIdentity) {
      return (existingIdentity, pubComponents)
    }

    // Clear stale items before generating a fresh persistent identity
    deleteExistingItems()

    var error: Unmanaged<CFError>?
    let privateKeyAttrs: [String: Any] = [
      kSecAttrIsPermanent as String: true,
      kSecAttrApplicationTag as String: keyTag,
      kSecAttrLabel as String: certLabel,
      kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
    ]
    let keyAttributes: [String: Any] = [
      kSecAttrKeyType as String: kSecAttrKeyTypeRSA,
      kSecAttrKeySizeInBits as String: 2048,
      kSecPrivateKeyAttrs as String: privateKeyAttrs,
    ]

    guard let privateKey = SecKeyCreateRandomKey(keyAttributes as CFDictionary, &error) else {
      let msg = error?.takeRetainedValue().localizedDescription ?? "Unknown error"
      throw CertificateError.keyGenerationFailed(msg)
    }

    guard let publicKey = SecKeyCopyPublicKey(privateKey),
          let spkiOrPkcs1 = SecKeyCopyExternalRepresentation(publicKey, &error) as Data? else {
      throw CertificateError.invalidRsaPublicKey
    }

    let pubComponents = try parseRsaPublicKeyData(spkiOrPkcs1)
    let certDer = try buildSelfSignedX509Certificate(
      privateKey: privateKey,
      pkcs1PublicKey: spkiOrPkcs1
    )

    guard let secCert = SecCertificateCreateWithData(nil, certDer as CFData) else {
      throw CertificateError.signingFailed("Invalid X.509 DER structure")
    }

    let addCertQuery: [String: Any] = [
      kSecClass as String: kSecClassCertificate,
      kSecValueRef as String: secCert,
      kSecAttrLabel as String: certLabel,
      kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
    ]
    let status = SecItemAdd(addCertQuery as CFDictionary, nil)
    guard status == errSecSuccess || status == errSecDuplicateItem else {
      throw CertificateError.keychainStoreFailed(status)
    }

    guard let identity = queryExistingIdentity() else {
      throw CertificateError.keychainStoreFailed(errSecItemNotFound)
    }
    return (identity, pubComponents)
  }

  func extractPublicKey(from trust: SecTrust) -> RSAPublicKeyComponents? {
    guard let chain = SecTrustCopyCertificateChain(trust) as? [SecCertificate],
          let leaf = chain.first,
          let key = SecCertificateCopyKey(leaf),
          let raw = SecKeyCopyExternalRepresentation(key, nil) as Data? else {
      return nil
    }
    return try? parseRsaPublicKeyData(raw)
  }

  private func extractPublicKey(from identity: SecIdentity) -> RSAPublicKeyComponents? {
    var cert: SecCertificate?
    guard SecIdentityCopyCertificate(identity, &cert) == errSecSuccess,
          let secCert = cert,
          let key = SecCertificateCopyKey(secCert),
          let raw = SecKeyCopyExternalRepresentation(key, nil) as Data? else {
      return nil
    }
    return try? parseRsaPublicKeyData(raw)
  }

  private func queryExistingIdentity() -> SecIdentity? {
    let query: [String: Any] = [
      kSecClass as String: kSecClassIdentity,
      kSecAttrLabel as String: certLabel,
      kSecReturnRef as String: true,
      kSecMatchLimit as String: kSecMatchLimitOne,
    ]
    var item: CFTypeRef?
    let status = SecItemCopyMatching(query as CFDictionary, &item)
    guard status == errSecSuccess,
          let ref = item,
          CFGetTypeID(ref) == SecIdentityGetTypeID() else { return nil }
    return (ref as! SecIdentity)
  }

  private func deleteExistingItems() {
    let keyQuery: [String: Any] = [
      kSecClass as String: kSecClassKey,
      kSecAttrApplicationTag as String: keyTag,
    ]
    SecItemDelete(keyQuery as CFDictionary)

    let certQuery: [String: Any] = [
      kSecClass as String: kSecClassCertificate,
      kSecAttrLabel as String: certLabel,
    ]
    SecItemDelete(certQuery as CFDictionary)
  }

  /// Parses SecKeyCopyExternalRepresentation PKCS#1 RSAPublicKey ASN.1 DER
  /// and strips any leading 0x00 sign byte.
  func parseRsaPublicKeyData(_ data: Data) throws -> RSAPublicKeyComponents {
    var reader = DerReader(data: data)
    let seq = try reader.readSequence()
    var seqReader = DerReader(data: seq)
    let modRaw = try seqReader.readIntegerBytes()
    let expRaw = try seqReader.readIntegerBytes()
    return RSAPublicKeyComponents(
      modulus: stripLeadingZeroByte(modRaw),
      exponent: stripLeadingZeroByte(expRaw)
    )
  }

  func stripLeadingZeroByte(_ bytes: Data) -> Data {
    if bytes.count > 1, bytes.first == 0x00 {
      return Data(bytes.dropFirst())
    }
    return bytes
  }

  private func buildSelfSignedX509Certificate(
    privateKey: SecKey,
    pkcs1PublicKey: Data
  ) throws -> Data {
    // Version: v3 (explicit tag [0] -> INTEGER 2)
    let version = DerWriter.explicitTag(0, content: DerWriter.integer(Data([0x02])))

    // Random positive 8-byte serial
    var serialBytes = (0..<8).map { _ in UInt8.random(in: 0...255) }
    serialBytes[0] = (serialBytes[0] & 0x7F) | 0x01
    let serial = DerWriter.integer(Data(serialBytes))

    // sha256WithRSAEncryption OID 1.2.840.113549.1.1.11 + NULL
    let sha256WithRsaAlgId = DerWriter.sequence(
      DerWriter.oid([1, 2, 840, 113549, 1, 1, 11]) + DerWriter.null()
    )

    // Subject & Issuer: CN=YV-TV Remote, O=AndroidTV, C=US
    let name = DerWriter.name([
      ([2, 5, 4, 3], "YV-TV Remote"),
      ([2, 5, 4, 10], "AndroidTV"),
      ([2, 5, 4, 6], "US"),
    ])

    // Validity: -1 day to +20 years
    let now = Date()
    let notBefore = now.addingTimeInterval(-86_400)
    let notAfter = now.addingTimeInterval(20 * 365 * 86_400)
    let validity = DerWriter.sequence(
      DerWriter.utcTime(notBefore) + DerWriter.generalizedTime(notAfter)
    )

    // SubjectPublicKeyInfo: rsaEncryption OID 1.2.840.113549.1.1.1 + NULL + BIT STRING
    let rsaAlgId = DerWriter.sequence(
      DerWriter.oid([1, 2, 840, 113549, 1, 1, 1]) + DerWriter.null()
    )
    let spki = DerWriter.sequence(rsaAlgId + DerWriter.bitString(pkcs1PublicKey))

    let tbsCertificate = DerWriter.sequence(
      version + serial + sha256WithRsaAlgId + name + validity + name + spki
    )

    var signError: Unmanaged<CFError>?
    guard let signature = SecKeyCreateSignature(
      privateKey,
      .rsaSignatureMessagePKCS1v15SHA256,
      tbsCertificate as CFData,
      &signError
    ) as Data? else {
      let msg = signError?.takeRetainedValue().localizedDescription ?? "Unknown signing error"
      throw CertificateError.signingFailed(msg)
    }

    return DerWriter.sequence(
      tbsCertificate + sha256WithRsaAlgId + DerWriter.bitString(signature)
    )
  }
}

private struct DerReader {
  let data: Data
  var offset: Int = 0

  mutating func readSequence() throws -> Data {
    guard offset < data.count, data[offset] == 0x30 else {
      throw CertificateError.invalidRsaPublicKey
    }
    offset += 1
    let length = try readLength()
    guard offset + length <= data.count else { throw CertificateError.invalidRsaPublicKey }
    let slice = data[offset..<(offset + length)]
    offset += length
    return Data(slice)
  }

  mutating func readIntegerBytes() throws -> Data {
    guard offset < data.count, data[offset] == 0x02 else {
      throw CertificateError.invalidRsaPublicKey
    }
    offset += 1
    let length = try readLength()
    guard offset + length <= data.count else { throw CertificateError.invalidRsaPublicKey }
    let slice = data[offset..<(offset + length)]
    offset += length
    return Data(slice)
  }

  private mutating func readLength() throws -> Int {
    guard offset < data.count else { throw CertificateError.invalidRsaPublicKey }
    let first = data[offset]
    offset += 1
    if (first & 0x80) == 0 {
      return Int(first)
    }
    let byteCount = Int(first & 0x7F)
    guard byteCount > 0, byteCount <= 4, offset + byteCount <= data.count else {
      throw CertificateError.invalidRsaPublicKey
    }
    var value = 0
    for _ in 0..<byteCount {
      value = (value << 8) | Int(data[offset])
      offset += 1
    }
    return value
  }
}

private enum DerWriter {
  static func tlv(tag: UInt8, content: Data) -> Data {
    var out = Data([tag])
    out.append(encodeLength(content.count))
    out.append(content)
    return out
  }

  static func sequence(_ content: Data) -> Data { tlv(tag: 0x30, content: content) }
  static func set(_ content: Data) -> Data { tlv(tag: 0x31, content: content) }
  static func null() -> Data { Data([0x05, 0x00]) }

  static func explicitTag(_ tagNumber: UInt8, content: Data) -> Data {
    tlv(tag: 0xA0 | (tagNumber & 0x1F), content: content)
  }

  static func integer(_ raw: Data) -> Data {
    var value = raw
    if let first = value.first, (first & 0x80) != 0 {
      value.insert(0x00, at: 0)
    }
    return tlv(tag: 0x02, content: value)
  }

  static func bitString(_ bytes: Data) -> Data {
    var payload = Data([0x00])
    payload.append(bytes)
    return tlv(tag: 0x03, content: payload)
  }

  static func utf8String(_ string: String) -> Data {
    tlv(tag: 0x0C, content: Data(string.utf8))
  }

  static func oid(_ arcs: [UInt]) -> Data {
    guard arcs.count >= 2 else { return Data() }
    var encoded = Data([UInt8(arcs[0] * 40 + arcs[1])])
    for arc in arcs.dropFirst(2) {
      encoded.append(encodeBase128(arc))
    }
    return tlv(tag: 0x06, content: encoded)
  }

  static func name(_ rdns: [([UInt], String)]) -> Data {
    var seqContent = Data()
    for (attrOid, value) in rdns {
      let atv = sequence(oid(attrOid) + utf8String(value))
      seqContent.append(set(atv))
    }
    return sequence(seqContent)
  }

  static func utcTime(_ date: Date) -> Data {
    let formatter = DateFormatter()
    formatter.locale = Locale(identifier: "en_US_POSIX")
    formatter.timeZone = TimeZone(secondsFromGMT: 0)
    formatter.dateFormat = "yyMMddHHmmss'Z'"
    return tlv(tag: 0x17, content: Data(formatter.string(from: date).utf8))
  }

  static func generalizedTime(_ date: Date) -> Data {
    let formatter = DateFormatter()
    formatter.locale = Locale(identifier: "en_US_POSIX")
    formatter.timeZone = TimeZone(secondsFromGMT: 0)
    formatter.dateFormat = "yyyyMMddHHmmss'Z'"
    return tlv(tag: 0x18, content: Data(formatter.string(from: date).utf8))
  }

  private static func encodeBase128(_ value: UInt) -> Data {
    var v = value
    var stack: [UInt8] = [UInt8(v & 0x7F)]
    v >>= 7
    while v > 0 {
      stack.append(UInt8((v & 0x7F) | 0x80))
      v >>= 7
    }
    return Data(stack.reversed())
  }

  private static func encodeLength(_ length: Int) -> Data {
    if length < 128 {
      return Data([UInt8(length)])
    }
    var bytes: [UInt8] = []
    var remaining = length
    while remaining > 0 {
      bytes.insert(UInt8(remaining & 0xFF), at: 0)
      remaining >>= 8
    }
    return Data([UInt8(0x80 | bytes.count)] + bytes)
  }
}
