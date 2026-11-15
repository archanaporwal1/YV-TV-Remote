package com.example.remote.protocol

import com.google.protobuf.MessageLite
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

object DelimitedStreamHelper {

  private const val MAX_MESSAGE_SIZE_BYTES = 4 * 1024 * 1024

  fun writeMessage(outputStream: OutputStream, message: MessageLite) {
    val payload = message.toByteArray()
    writeDelimitedBytes(outputStream, payload)
  }

  fun writeDelimitedBytes(outputStream: OutputStream, payload: ByteArray) {
    writeRawVarint32(outputStream, payload.size)
    outputStream.write(payload)
    outputStream.flush()
  }

  fun readDelimitedBytes(inputStream: InputStream): ByteArray? {
    val firstByte = inputStream.read()
    if (firstByte == -1) {
      return null
    }

    val length = readRawVarint32(firstByte, inputStream)
    if (length < 0 || length > MAX_MESSAGE_SIZE_BYTES) {
      throw IOException("Invalid delimited protobuf frame length: $length")
    }

    val buffer = ByteArray(length)
    var totalRead = 0
    while (totalRead < length) {
      val read = inputStream.read(buffer, totalRead, length - totalRead)
      if (read == -1) {
        throw EOFException("Unexpected end of stream while reading $length-byte protobuf frame")
      }
      totalRead += read
    }
    return buffer
  }

  private fun writeRawVarint32(outputStream: OutputStream, initialValue: Int) {
    var value = initialValue
    while (true) {
      if ((value and 0x7F.inv()) == 0) {
        outputStream.write(value)
        return
      } else {
        outputStream.write((value and 0x7F) or 0x80)
        value = value ushr 7
      }
    }
  }

  private fun readRawVarint32(firstByte: Int, inputStream: InputStream): Int {
    if ((firstByte and 0x80) == 0) {
      return firstByte
    }
    var result = firstByte and 0x7F
    var shift = 7
    while (shift < 32) {
      val next = inputStream.read()
      if (next == -1) {
        throw EOFException("Truncated varint32 header")
      }
      result = result or ((next and 0x7F) shl shift)
      if ((next and 0x80) == 0) {
        return result
      }
      shift += 7
    }
    // Consume any remaining continuation bytes up to 64-bit encoding
    for (i in 0 until 5) {
      val next = inputStream.read()
      if (next == -1) {
        throw EOFException("Truncated varint header")
      }
      if ((next and 0x80) == 0) {
        return result
      }
    }
    throw IOException("Malformed varint32 header")
  }
}
