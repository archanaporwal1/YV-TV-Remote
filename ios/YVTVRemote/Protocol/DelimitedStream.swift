import Foundation
import Network

enum DelimitedStreamError: LocalizedError {
  case endOfStream
  case invalidFrameLength(Int)

  var errorDescription: String? {
    switch self {
    case .endOfStream: return "TV closed the connection."
    case .invalidFrameLength(let length): return "Invalid protobuf frame length: \(length)"
    }
  }
}

final class DelimitedConnectionStream {
  private let connection: NWConnection
  private var buffer = Data()
  private let maxFrameBytes = 4 * 1024 * 1024

  init(connection: NWConnection) {
    self.connection = connection
  }

  func sendFrame(_ payload: Data) async throws {
    var framed = encodeVarint32(payload.count)
    framed.append(payload)

    try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
      connection.send(content: framed, completion: .contentProcessed { error in
        if let error {
          continuation.resume(throwing: error)
        } else {
          continuation.resume()
        }
      })
    }
  }

  func readNextFrame() async throws -> Data {
    while true {
      if let frame = try extractNextFrameFromBuffer() {
        return frame
      }
      let chunk = try await receiveChunk()
      if chunk.isEmpty {
        throw DelimitedStreamError.endOfStream
      }
      buffer.append(chunk)
    }
  }

  private func extractNextFrameFromBuffer() throws -> Data? {
    var index = 0
    var length = 0
    var shift = 0
    var headerComplete = false

    while index < buffer.count {
      let byte = buffer[buffer.startIndex + index]
      index += 1
      length |= Int(byte & 0x7F) << shift
      if (byte & 0x80) == 0 {
        headerComplete = true
        break
      }
      shift += 7
      if shift >= 35 {
        throw DelimitedStreamError.invalidFrameLength(-1)
      }
    }

    guard headerComplete else { return nil }
    guard length >= 0, length <= maxFrameBytes else {
      throw DelimitedStreamError.invalidFrameLength(length)
    }
    guard buffer.count >= index + length else { return nil }

    let start = buffer.startIndex + index
    let payload = Data(buffer[start..<(start + length)])
    buffer.removeFirst(index + length)
    return payload
  }

  private func receiveChunk() async throws -> Data {
    try await withCheckedThrowingContinuation { continuation in
      connection.receive(minimumIncompleteLength: 1, maximumLength: 65_536) { data, _, isComplete, error in
        if let error {
          continuation.resume(throwing: error)
        } else if let data, !data.isEmpty {
          continuation.resume(returning: data)
        } else if isComplete {
          continuation.resume(throwing: DelimitedStreamError.endOfStream)
        } else {
          continuation.resume(returning: Data())
        }
      }
    }
  }

  private func encodeVarint32(_ initialValue: Int) -> Data {
    var value = UInt32(bitPattern: Int32(initialValue))
    var out = Data()
    while true {
      if (value & ~0x7F) == 0 {
        out.append(UInt8(value))
        return out
      } else {
        out.append(UInt8((value & 0x7F) | 0x80))
        value >>= 7
      }
    }
  }
}
