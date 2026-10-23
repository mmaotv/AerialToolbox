package com.miaomiaotv.aerial.phone

import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

/** 扫描局域网里开了 ADB（默认 5555 端口）的设备。 */
object LanScanner {

    const val ADB_PORT = 5555

    /** 读本机局域网 IPv4（用于推导网段） */
    fun localIp(): String? {
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces()
            while (ifaces.hasMoreElements()) {
                val iface = ifaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue
                val addrs = iface.inetAddresses
                while (addrs.hasMoreElements()) {
                    val a = addrs.nextElement()
                    if (a is Inet4Address && !a.isLoopbackAddress) {
                        return a.hostAddress
                    }
                }
            }
        } catch (_: Exception) {
        }
        return null
    }

    /**
     * 扫描 /24 网段里开放 [ADB_PORT] 的主机。命中即回调（在子线程）。
     * @param timeoutMs 单主机连接超时，太大会让整轮扫描很慢
     */
    fun scan(timeoutMs: Int = 350, onFound: (String) -> Unit) {
        val ip = localIp() ?: return
        val base = ip.substringBeforeLast('.') + "."
        val threads = mutableListOf<Thread>()
        for (i in 1..254) {
            val host = base + i
            if (host == ip) continue
            val t = Thread {
                if (tcpOpen(host, ADB_PORT, timeoutMs)) onFound(host)
            }
            threads.add(t)
            t.start()
        }
        threads.forEach { it.join() }
    }

    private fun tcpOpen(host: String, port: Int, timeout: Int): Boolean {
        return try {
            Socket().use { s ->
                s.connect(InetSocketAddress(host, port), timeout)
                true
            }
        } catch (_: Exception) {
            false
        }
    }
}
