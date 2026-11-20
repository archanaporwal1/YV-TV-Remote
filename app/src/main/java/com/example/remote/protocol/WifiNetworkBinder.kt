package com.example.remote.protocol

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.InetAddress
import java.net.Socket

/**
 * Binds private LAN TCP sockets directly to the active Wi-Fi [Network] when available so Android's
 * multi-interface routing never sends local TV traffic over cellular or stale default routes.
 */
object WifiNetworkBinder {

  fun isPrivateLanHost(host: String): Boolean {
    val clean = host.trim().lowercase()
    if (clean.isEmpty() || clean == "localhost" || clean == "127.0.0.1" || clean == "::1") {
      return false
    }
    val parts = clean.split('.')
    if (parts.size != 4) return false
    val octets = parts.map { it.toIntOrNull() ?: return false }
    if (octets.any { it !in 0..255 }) return false
    val first = octets[0]
    val second = octets[1]
    return when {
      first == 10 -> true
      first == 192 && second == 168 -> true
      first == 172 && second in 16..31 -> true
      first == 169 && second == 254 -> true
      else -> false
    }
  }

  fun findActiveWifiNetwork(context: Context?): Network? {
    if (context == null) return null
    return runCatching {
      val cm =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE)
          as? ConnectivityManager ?: return null
      @Suppress("DEPRECATION")
      val networks = cm.allNetworks
      var bestWifi: Network? = null
      for (network in networks) {
        val caps = cm.getNetworkCapabilities(network) ?: continue
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
          if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) {
            return network
          }
          if (bestWifi == null) {
            bestWifi = network
          }
        }
      }
      bestWifi
    }.getOrNull()
  }

  fun createPlainSocket(context: Context?, host: String): Socket {
    if (isPrivateLanHost(host)) {
      val wifiNetwork = findActiveWifiNetwork(context)
      if (wifiNetwork != null) {
        val boundSocket = runCatching { wifiNetwork.socketFactory.createSocket() }.getOrNull()
        if (boundSocket != null) {
          return boundSocket
        }
      }
    }
    return Socket()
  }

  fun isLanIpv4Address(address: InetAddress?): Boolean {
    val v4 = address as? Inet4Address ?: return false
    val hostAddr = v4.hostAddress ?: return false
    return isPrivateLanHost(hostAddr)
  }
}
