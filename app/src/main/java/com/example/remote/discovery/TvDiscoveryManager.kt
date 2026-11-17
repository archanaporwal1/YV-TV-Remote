package com.example.remote.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

data class DiscoveredTv(
  val serviceName: String,
  val displayName: String,
  val ipAddress: String,
  val port: Int,
)

class TvDiscoveryManager(context: Context) {

  private val appContext = context.applicationContext
  private val nsdManager = appContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
  private val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

  private val _discoveredTvs = MutableStateFlow<List<DiscoveredTv>>(emptyList())
  val discoveredTvs: StateFlow<List<DiscoveredTv>> = _discoveredTvs.asStateFlow()

  private val _isScanning = MutableStateFlow(false)
  val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

  private val _scanError = MutableStateFlow<String?>(null)
  val scanError: StateFlow<String?> = _scanError.asStateFlow()

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private var resolveQueueChannel: Channel<NsdServiceInfo>? = null
  private var resolveWorkerJob: Job? = null
  private var discoveryListener: NsdManager.DiscoveryListener? = null
  private var multicastLock: WifiManager.MulticastLock? = null

  @Synchronized
  fun startScan() {
    if (_isScanning.value) return
    val manager = nsdManager
    if (manager == null) {
      _scanError.value = "Network discovery service unavailable on this device. Use Manual IP."
      return
    }
    _scanError.value = null
    _discoveredTvs.value = emptyList()

    acquireMulticastLock()

    val channel = Channel<NsdServiceInfo>(Channel.UNLIMITED)
    resolveQueueChannel = channel
    val queuedServices = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    resolveWorkerJob =
      scope.launch {
        for (serviceInfo in channel) {
          val resolved = resolveSequentially(serviceInfo)
          if (resolved != null) {
            _discoveredTvs.update { current ->
              val filtered =
                current.filterNot {
                  it.ipAddress == resolved.ipAddress || it.serviceName == resolved.serviceName
                }
              (filtered + resolved).sortedBy { it.displayName.lowercase() }
            }
          }
        }
      }

    val listener =
      object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(regType: String) {
          _isScanning.value = true
        }

        override fun onServiceFound(service: NsdServiceInfo) {
          runCatching {
            val type = service.serviceType.orEmpty()
            val name = service.serviceName.orEmpty()
            if (type.contains(SERVICE_TYPE_MATCH) &&
              name.isNotBlank() &&
              queuedServices.add(name)
            ) {
              resolveQueueChannel?.trySend(service)
            }
          }
        }

        override fun onServiceLost(service: NsdServiceInfo) {
          runCatching {
            val name = service.serviceName.orEmpty()
            if (name.isNotBlank()) {
              queuedServices.remove(name)
              _discoveredTvs.update { current ->
                current.filterNot { it.serviceName == name }
              }
            }
          }
        }

        override fun onDiscoveryStopped(serviceType: String) {
          _isScanning.value = false
        }

        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
          _isScanning.value = false
          _scanError.value = "Discovery failed to start (code $errorCode). Check Wi-Fi connection."
          stopScanInternal()
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
          _isScanning.value = false
        }
      }

    discoveryListener = listener
    try {
      manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
      _isScanning.value = true
    } catch (t: Throwable) {
      _isScanning.value = false
      _scanError.value = t.message ?: "Unable to start mDNS discovery."
      stopScanInternal()
    }
  }

  @Synchronized
  fun stopScan() {
    stopScanInternal()
  }

  private fun stopScanInternal() {
    discoveryListener?.let { listener ->
      try {
        nsdManager?.stopServiceDiscovery(listener)
      } catch (_: Throwable) {
      }
    }
    discoveryListener = null
    runCatching { resolveQueueChannel?.close() }
    resolveQueueChannel = null
    resolveWorkerJob?.cancel()
    resolveWorkerJob = null
    releaseMulticastLock()
    _isScanning.value = false
  }

  @Suppress("DEPRECATION")
  private suspend fun resolveSequentially(serviceInfo: NsdServiceInfo): DiscoveredTv? {
    val manager = nsdManager ?: return null
    repeat(2) { attempt ->
      val result =
        withTimeoutOrNull(RESOLVE_TIMEOUT_MS) {
          suspendCancellableCoroutine<Pair<Boolean, DiscoveredTv?>> { continuation ->
            val completed = AtomicBoolean(false)
            val resolveListener =
              object : NsdManager.ResolveListener {
                override fun onResolveFailed(failedService: NsdServiceInfo, errorCode: Int) {
                  if (completed.compareAndSet(false, true) && continuation.isActive) {
                    val shouldRetry = errorCode == NsdManager.FAILURE_ALREADY_ACTIVE
                    continuation.resume(shouldRetry to null)
                  }
                }

                override fun onServiceResolved(resolvedService: NsdServiceInfo) {
                  if (!completed.compareAndSet(false, true) || !continuation.isActive) return
                  val tv =
                    runCatching {
                      val ipv4Address = extractIpv4Address(resolvedService)
                      if (ipv4Address.isNullOrBlank()) {
                        null
                      } else {
                        val svcName = resolvedService.serviceName.orEmpty()
                        val friendlyName =
                          extractFriendlyName(resolvedService)
                            ?: svcName.ifBlank { "Android TV" }
                        DiscoveredTv(
                          serviceName = svcName,
                          displayName = friendlyName,
                          ipAddress = ipv4Address,
                          port = resolvedService.port.takeIf { it > 0 } ?: DEFAULT_REMOTE_PORT,
                        )
                      }
                    }.getOrNull()
                  continuation.resume(false to tv)
                }
              }

            try {
              manager.resolveService(serviceInfo, resolveListener)
            } catch (_: Throwable) {
              if (completed.compareAndSet(false, true) && continuation.isActive) {
                continuation.resume(false to null)
              }
            }
          }
        }
      if (result?.second != null) return result.second
      if (result?.first == true && attempt == 0) {
        kotlinx.coroutines.delay(250L)
      } else {
        return null
      }
    }
    return null
  }

  @Suppress("DEPRECATION")
  private fun extractIpv4Address(serviceInfo: NsdServiceInfo): String? {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      val ipv4 = runCatching { serviceInfo.hostAddresses.firstOrNull { it is Inet4Address } }.getOrNull()
      if (ipv4?.hostAddress != null) return ipv4.hostAddress
    }
    val host = runCatching { serviceInfo.host }.getOrNull()
    return if (host is Inet4Address) host.hostAddress else host?.hostAddress?.takeIf { !it.contains(':') }
  }

  private fun extractFriendlyName(serviceInfo: NsdServiceInfo): String? {
    val attributes = runCatching { serviceInfo.attributes }.getOrNull() ?: return null
    val rawName = attributes["fn"] ?: attributes["n"] ?: return null
    return rawName.toString(Charsets.UTF_8).trim().takeIf { it.isNotEmpty() }
  }

  private fun acquireMulticastLock() {
    if (multicastLock?.isHeld == true) return
    try {
      val lock = wifiManager?.createMulticastLock("yvtv_remote_mdns_lock")?.apply {
        setReferenceCounted(false)
        acquire()
      }
      multicastLock = lock
    } catch (_: Throwable) {
    }
  }

  private fun releaseMulticastLock() {
    try {
      if (multicastLock?.isHeld == true) {
        multicastLock?.release()
      }
    } catch (_: Throwable) {
    } finally {
      multicastLock = null
    }
  }

  companion object {
    private const val SERVICE_TYPE = "_androidtvremote2._tcp."
    private const val SERVICE_TYPE_MATCH = "_androidtvremote2._tcp"
    private const val RESOLVE_TIMEOUT_MS = 4_500L
    private const val DEFAULT_REMOTE_PORT = 6466
  }
}
