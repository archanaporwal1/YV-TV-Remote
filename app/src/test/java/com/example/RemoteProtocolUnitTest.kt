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
}
