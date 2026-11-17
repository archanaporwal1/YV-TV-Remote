package com.example

import com.example.proto.pairing.PairingEncoding
import com.example.proto.pairing.PairingMessage
import com.example.proto.pairing.PairingOption
import com.example.proto.pairing.RoleType
import com.example.proto.remote.RemoteDirection
import com.example.proto.remote.RemoteKeyCode
import com.example.proto.remote.RemoteKeyInject
import com.example.proto.remote.RemoteMessage
import com.example.remote.protocol.CertificateManager
import com.example.remote.protocol.DelimitedStreamHelper
import com.example.remote.protocol.PairingClient
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.MessageDigest

class RemoteProtocolUnitTest {

  @Test
  fun varintDelimitedProtobufRoundTrip() {
    val pairingMsg =
      PairingMessage.newBuilder()
        .setProtocolVersion(2)
        .setStatus(PairingMessage.Status.STATUS_OK)
        .setPairingOption(
          PairingOption.newBuilder()
            .addInputEncodings(
              PairingEncoding.newBuilder()
                .setType(PairingEncoding.EncodingType.ENCODING_TYPE_HEXADECIMAL)
                .setSymbolLength(6)
                .build()
            )
            .setPreferredRole(RoleType.ROLE_TYPE_INPUT)
            .build()
        )
        .build()

    val keyMsg =
      RemoteMessage.newBuilder()
        .setRemoteKeyInject(
          RemoteKeyInject.newBuilder()
            .setKeyCode(RemoteKeyCode.KEYCODE_DPAD_CENTER)
            .setDirection(RemoteDirection.SHORT)
            .build()
        )
        .build()

    val out = ByteArrayOutputStream()
    DelimitedStreamHelper.writeMessage(out, pairingMsg)
    DelimitedStreamHelper.writeMessage(out, keyMsg)

    val input = ByteArrayInputStream(out.toByteArray())
    val frame1 = DelimitedStreamHelper.readDelimitedBytes(input)
    val frame2 = DelimitedStreamHelper.readDelimitedBytes(input)
    val eof = DelimitedStreamHelper.readDelimitedBytes(input)

    val parsed1 = PairingMessage.parseFrom(frame1)
    val parsed2 = RemoteMessage.parseFrom(frame2)

    assertEquals(2, parsed1.protocolVersion)
    assertEquals(PairingMessage.Status.STATUS_OK, parsed1.status)
    assertEquals(6, parsed1.pairingOption.getInputEncodings(0).symbolLength)
    assertEquals(RemoteKeyCode.KEYCODE_DPAD_CENTER, parsed2.remoteKeyInject.keyCode)
    assertEquals(RemoteDirection.SHORT, parsed2.remoteKeyInject.direction)
    assertNull(eof)
  }

  @Test
  fun stripLeadingZeroByteRemovesSignByteOnly() {
    val withSignByte = BigInteger(1, byteArrayOf(0x8A.toByte(), 0x1F.toByte(), 0x01.toByte()))
    val stripped = CertificateManager.stripLeadingZeroByte(withSignByte)
    assertArrayEquals(
      byteArrayOf(0x8A.toByte(), 0x1F.toByte(), 0x01.toByte()),
      stripped,
    )

    val withoutSignByte = BigInteger(1, byteArrayOf(0x01, 0x00, 0x01))
    assertArrayEquals(
      byteArrayOf(0x01, 0x00, 0x01),
      CertificateManager.stripLeadingZeroByte(withoutSignByte),
    )
  }

  @Test
  fun hexToBytesAndPinDigestVerification() {
    val nonce = PairingClient.hexToBytes("A4F9")
    assertArrayEquals(byteArrayOf(0xA4.toByte(), 0xF9.toByte()), nonce)

    val digest = MessageDigest.getInstance("SHA-256")
    digest.update(byteArrayOf(0x01, 0x02))
    digest.update(nonce)
    val hash = digest.digest()
    assertEquals(32, hash.size)
  }

  @Test
  fun keyboardImeAndCharacterMapping() {
    assertEquals(RemoteKeyCode.KEYCODE_A, com.example.remote.protocol.RemoteControlClient.charToRemoteKeyCode('A'))
    assertEquals(RemoteKeyCode.KEYCODE_Z, com.example.remote.protocol.RemoteControlClient.charToRemoteKeyCode('z'))
    assertEquals(RemoteKeyCode.KEYCODE_SPACE, com.example.remote.protocol.RemoteControlClient.charToRemoteKeyCode(' '))
    assertEquals(RemoteKeyCode.KEYCODE_0, com.example.remote.protocol.RemoteControlClient.charToRemoteKeyCode('0'))

    val imeMessage =
      RemoteMessage.newBuilder()
        .setRemoteImeBatchEdit(
          com.example.proto.remote.RemoteImeBatchEdit.newBuilder()
            .setImeCounter(3)
            .setFieldCounter(1)
            .addEditInfo(
              com.example.proto.remote.RemoteEditInfo.newBuilder()
                .setInsert(1)
                .setTextFieldStatus(
                  com.example.proto.remote.RemoteImeObject.newBuilder()
                    .setStart(0)
                    .setEnd(0)
                    .setValue("inception")
                    .build()
                )
                .build()
            )
            .build()
        )
        .build()

    val out = ByteArrayOutputStream()
    DelimitedStreamHelper.writeMessage(out, imeMessage)
    val decoded = RemoteMessage.parseFrom(DelimitedStreamHelper.readDelimitedBytes(ByteArrayInputStream(out.toByteArray())))
    assertEquals("inception", decoded.remoteImeBatchEdit.getEditInfo(0).textFieldStatus.value)
    assertEquals(3, decoded.remoteImeBatchEdit.imeCounter)
  }

  @Test
  fun certificateUnknownSslExceptionTriggersPairingDetection() {
    val boringSslReadAlert =
      javax.net.ssl.SSLException(
        "Read error: ssl=0xb400007df44ea418: Failure in SSL library, usually a protocol error error:10000416:SSL routines:OPENSSL_internal:SSLV3_ALERT_CERTIFICATE_UNKNOWN"
      )
    org.junit.Assert.assertTrue(
      com.example.remote.protocol.RemoteControlClient.isCertificateOrHandshakeError(boringSslReadAlert)
    )
    org.junit.Assert.assertTrue(
      com.example.remote.protocol.RemoteControlClient.isCertificateOrHandshakeError(
        javax.net.ssl.SSLHandshakeException("Handshake failed")
      )
    )
    org.junit.Assert.assertFalse(
      com.example.remote.protocol.RemoteControlClient.isCertificateOrHandshakeError(
        javax.net.ssl.SSLException("Connection timed out")
      )
    )
  }

  @Test
  fun updateVersionComparison() {
    val mgr = com.example.remote.update.AppUpdateManager
    org.junit.Assert.assertTrue(mgr.isNewerVersion("1.0.5", 5, "1.0", 1))
    org.junit.Assert.assertTrue(mgr.isNewerVersion("v1.1.0", 1, "1.0.99", 99))
    org.junit.Assert.assertTrue(mgr.isNewerVersion("1.0.5", 6, "1.0.5", 5))
    org.junit.Assert.assertFalse(mgr.isNewerVersion("1.0.5", 5, "1.0.5", 5))
    org.junit.Assert.assertFalse(mgr.isNewerVersion("1.0.4", 4, "1.0.5", 5))
  }

  @Test
  fun voiceCommandParserMapsKeysOttAppsAndSearches() {
    val parser = com.example.remote.voice.VoiceCommandParser
    val volUp = parser.parse("Please volume up")
    org.junit.Assert.assertTrue(volUp is com.example.remote.voice.ParsedVoiceCommand.SendKey)
    assertEquals(
      RemoteKeyCode.KEYCODE_VOLUME_UP,
      (volUp as com.example.remote.voice.ParsedVoiceCommand.SendKey).keyCode,
    )

    val pause = parser.parse("pause")
    assertEquals(
      RemoteKeyCode.KEYCODE_MEDIA_PLAY_PAUSE,
      (pause as com.example.remote.voice.ParsedVoiceCommand.SendKey).keyCode,
    )

    val openNetflix = parser.parse("Open Netflix")
    org.junit.Assert.assertTrue(openNetflix is com.example.remote.voice.ParsedVoiceCommand.OpenOttApp)
    assertEquals(
      "netflix",
      (openNetflix as com.example.remote.voice.ParsedVoiceCommand.OpenOttApp).shortcut.id,
    )

    val openPrime = parser.parse("launch Amazon Prime")
    org.junit.Assert.assertTrue(openPrime is com.example.remote.voice.ParsedVoiceCommand.OpenOttApp)
    assertEquals(
      "prime_video",
      (openPrime as com.example.remote.voice.ParsedVoiceCommand.OpenOttApp).shortcut.id,
    )

    val explicitSearch = parser.parse("search for Stranger Things")
    org.junit.Assert.assertTrue(explicitSearch is com.example.remote.voice.ParsedVoiceCommand.SearchOrType)
    assertEquals(
      "Stranger Things",
      (explicitSearch as com.example.remote.voice.ParsedVoiceCommand.SearchOrType).query,
    )

    val politeSearch = parser.parse("Please search for Inception")
    org.junit.Assert.assertTrue(politeSearch is com.example.remote.voice.ParsedVoiceCommand.SearchOrType)
    assertEquals(
      "Inception",
      (politeSearch as com.example.remote.voice.ParsedVoiceCommand.SearchOrType).query,
    )

    val naturalSearch = parser.parse("Lo-fi focus beats")
    org.junit.Assert.assertTrue(naturalSearch is com.example.remote.voice.ParsedVoiceCommand.SearchOrType)
    assertEquals(
      "Lo-fi focus beats",
      (naturalSearch as com.example.remote.voice.ParsedVoiceCommand.SearchOrType).query,
    )

    assertNull(parser.parse("   "))
  }

  @Test
  fun delimitedStreamHelperRejectsMalformedOversizedLengthWithoutCrash() {
    // Varint representing a huge length (> 16 MiB)
    val badFrame = ByteArrayInputStream(byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x07))
    val result = runCatching { DelimitedStreamHelper.readDelimitedBytes(badFrame) }
    org.junit.Assert.assertTrue(result.isFailure || result.getOrNull() == null)
  }
}
