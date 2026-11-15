import Foundation
import Network

struct DiscoveredTv: Identifiable, Equatable {
  var id: String { "\(serviceName)_\(ipAddress)_\(port)" }
  let serviceName: String
  let displayName: String
  let ipAddress: String
  let port: UInt16
}

@MainActor
final class TvDiscoveryManager: ObservableObject {
  @Published private(set) var discoveredTvs: [DiscoveredTv] = []
  @Published private(set) var isScanning = false
  @Published private(set) var scanError: String?

  private var browser: NWBrowser?
  private var activeResolvers: [NWEndpoint: NWConnection] = [:]
  private let queue = DispatchQueue(label: "com.aistudio.yvtvremote.discovery")

  func startScan() {
    guard !isScanning else { return }
    scanError = nil
    discoveredTvs = []

    let params = NWParameters.tcp
    params.includePeerToPeer = true
    let descriptor = NWBrowser.Descriptor.bonjourWithTXTRecord(type: "_androidtvremote2._tcp", domain: nil)
    let nwBrowser = NWBrowser(for: descriptor, using: params)
    self.browser = nwBrowser

    nwBrowser.stateUpdateHandler = { [weak self] state in
      Task { @MainActor [weak self] in
        guard let self else { return }
        switch state {
        case .ready:
          self.isScanning = true
        case .failed(let error):
          self.isScanning = false
          self.scanError = "Wi-Fi discovery failed: \(error.localizedDescription)"
          self.stopScan()
        case .cancelled:
          self.isScanning = false
        default:
          break
        }
      }
    }

    nwBrowser.browseResultsChangedHandler = { [weak self] results, _ in
      Task { @MainActor [weak self] in
        self?.handleBrowseResults(results)
      }
    }

    isScanning = true
    nwBrowser.start(queue: queue)
  }

  func stopScan() {
    browser?.cancel()
    browser = nil
    activeResolvers.values.forEach { $0.cancel() }
    activeResolvers.removeAll()
    isScanning = false
  }

  private func handleBrowseResults(_ results: Set<NWBrowser.Result>) {
    let activeEndpoints = Set(results.map { $0.endpoint })
    for (endpoint, conn) in activeResolvers where !activeEndpoints.contains(endpoint) {
      conn.cancel()
      activeResolvers.removeValue(forKey: endpoint)
    }

    for result in results {
      guard case .service(let serviceName, _, _, _) = result.endpoint else { continue }
      let friendlyName = extractFriendlyName(from: result.metadata) ?? serviceName
      resolveService(endpoint: result.endpoint, serviceName: serviceName, friendlyName: friendlyName)
    }
  }

  private func extractFriendlyName(from metadata: NWBrowser.Result.Metadata?) -> String? {
    guard case .bonjour(let txtRecord) = metadata else { return nil }
    if let fn = txtRecord.dictionary["fn"], !fn.isEmpty { return fn }
    if let n = txtRecord.dictionary["n"], !n.isEmpty { return n }
    return nil
  }

  private func resolveService(endpoint: NWEndpoint, serviceName: String, friendlyName: String) {
    guard activeResolvers[endpoint] == nil else { return }
    let resolver = NWConnection(to: endpoint, using: .tcp)
    activeResolvers[endpoint] = resolver

    resolver.stateUpdateHandler = { [weak self, weak resolver] state in
      guard let resolver else { return }
      switch state {
      case .ready:
        if let remoteEndpoint = resolver.currentPath?.remoteEndpoint,
           case .hostPort(let host, let port) = remoteEndpoint,
           let ipv4 = Self.cleanIPv4(host) {
          let tv = DiscoveredTv(
            serviceName: serviceName,
            displayName: friendlyName,
            ipAddress: ipv4,
            port: port.rawValue
          )
          Task { @MainActor [weak self] in
            self?.upsertDiscovered(tv)
          }
        }
        resolver.cancel()
      case .failed, .cancelled:
        Task { @MainActor [weak self] in
          self?.activeResolvers.removeValue(forKey: endpoint)
        }
      default:
        break
      }
    }
    resolver.start(queue: queue)
  }

  private func upsertDiscovered(_ tv: DiscoveredTv) {
    discoveredTvs.removeAll { $0.ipAddress == tv.ipAddress || $0.serviceName == tv.serviceName }
    discoveredTvs.append(tv)
    discoveredTvs.sort { $0.displayName.localizedCaseInsensitiveCompare($1.displayName) == .orderedAscending }
  }

  nonisolated private static func cleanIPv4(_ host: NWEndpoint.Host) -> String? {
    let raw: String
    switch host {
    case .ipv4(let addr):
      raw = "\(addr)"
    case .name(let hostname, _):
      raw = hostname
    default:
      return nil
    }
    let clean = raw.split(separator: "%").first.map(String.init) ?? raw
    return clean.contains(":") ? nil : clean
  }
}
