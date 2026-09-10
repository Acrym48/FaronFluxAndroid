package io.github.p1neapplexpress.openflux.service

import android.content.Context
import io.github.p1neapplexpress.openflux.NativeBridge
import io.github.p1neapplexpress.openflux.util.Logx
import io.github.p1neapplexpress.openflux.util.ProcessRunner
import java.io.File

/**
 * Launches libpdnsd.so + libtun2socks.so and hands the VPN fd over via unix socket.
 */
class Tun2SocksLauncher(private val context: Context) {

    companion object {
        private const val TAG = "Tun2SocksLauncher"
        private const val SEND_FD_ATTEMPTS = 10
        private const val SEND_FD_BASE_DELAY_MS = 500L
        private const val NETIF_IPADDR = "26.26.26.2"
        private const val NETIF_NETMASK = "255.255.255.0"
        private const val NETIF_IP6ADDR = "fdfe:dcba:9876::2"
        private const val TUN_MTU = 1500
        private const val DNS_GW = "26.26.26.1:8091"
        private const val LOG_LEVEL = "3"
    }

    fun start(
        fd: Int,
        server: String,
        port: Int,
        username: String?,
        password: String?,
        dns: String,
        dnsPort: Int,
        ipv6: Boolean,
        udpgw: String?,
    ): Boolean {
        if (fd <= 0) {
            Logx.e(TAG, "invalid fd: $fd")
            return false
        }

        val sockPath = File(context.applicationInfo.dataDir, "sock_path").apply {
            if (!exists()) createNewFile()
            setWritable(true, false)
            setReadable(true, false)
        }

        // pdnsd
        makePdnsdConf(dns, dnsPort)
        val pdnsdBin = "${context.applicationInfo.nativeLibraryDir}/libpdnsd.so"
        val pdnsdRes = ProcessRunner.run(
            listOf(pdnsdBin, "-c", "${context.filesDir}/pdnsd.conf"),
            workingDir = context.filesDir.absolutePath,
        )
        if (!pdnsdRes.ok) Logx.w(TAG, "pdnsd exit=${pdnsdRes.exitCode} ${pdnsdRes.stderr}")

        // tun2socks
        val cmd = buildCommand(fd, server, port, username, password, ipv6, udpgw)
        val res = ProcessRunner.run(cmd, workingDir = context.filesDir.absolutePath, timeoutMs = 5_000)
        if (!res.ok && res.exitCode != -1) {
            Logx.e(TAG, "tun2socks exit=${res.exitCode} ${res.stderr}")
        }

        // hand over fd
        for (attempt in 1..SEND_FD_ATTEMPTS) {
            val r = NativeBridge.sendfd(fd, sockPath.absolutePath)
            if (r != -1) {
                Logx.i(TAG, "sendfd ok after $attempt attempt(s)")
                return true
            }
            try {
                Thread.sleep(SEND_FD_BASE_DELAY_MS * attempt)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        Logx.e(TAG, "sendfd failed after $SEND_FD_ATTEMPTS attempts")
        return false
    }

    private fun buildCommand(
        fd: Int,
        server: String,
        port: Int,
        user: String?,
        passwd: String?,
        ipv6: Boolean,
        udpgw: String?,
    ): List<String> = buildList {
        add("${context.applicationInfo.nativeLibraryDir}/libtun2socks.so")
        add("--netif-ipaddr"); add(NETIF_IPADDR)
        add("--netif-netmask"); add(NETIF_NETMASK)
        add("--socks-server-addr"); add("$server:$port")
        add("--tunfd"); add(fd.toString())
        add("--tunmtu"); add(TUN_MTU.toString())
        add("--loglevel"); add(LOG_LEVEL)
        add("--pid"); add("${context.filesDir}/tun2socks.pid")
        add("--sock"); add("${context.applicationInfo.dataDir}/sock_path")
        if (!user.isNullOrEmpty()) {
            add("--username"); add(user)
            add("--password"); add(passwd ?: "")
        }
        if (ipv6) {
            add("--netif-ip6addr"); add(NETIF_IP6ADDR)
        }
        add("--dnsgw"); add(DNS_GW)
        udpgw?.let { add("--udpgw-remote-server-addr"); add(it) }
    }

    private fun makePdnsdConf(dns: String, port: Int) {
        val conf = context.getString(io.github.p1neapplexpress.openflux.R.string.pdnsd_conf)
            .replace("{DIR}", context.filesDir.toString())
            .replace("{IP}", dns)
            .replace("{PORT}", port.toString())

        val f = File(context.filesDir, "pdnsd.conf").apply { if (exists()) delete() }
        f.writeText(conf)

        val cache = File(context.filesDir, "pdnsd.cache")
        if (!cache.exists()) cache.createNewFile()
    }
}
