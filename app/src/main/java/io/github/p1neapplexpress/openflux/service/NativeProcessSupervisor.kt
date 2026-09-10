package io.github.p1neapplexpress.openflux.service

import android.content.Context
import android.os.Handler
import android.os.Looper
import io.github.p1neapplexpress.openflux.event.AppEvent
import io.github.p1neapplexpress.openflux.event.EventBus
import io.github.p1neapplexpress.openflux.util.Logx
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Supervises the native transport process (libp1npplydtransport.so).
 * Restarts on unexpected death with backoff; stops cleanly on user request.
 */
class NativeProcessSupervisor(private val context: Context) {

    companion object {
        private val TOKEN_REGEX = Regex("(--maxToken\\s+)\\S+", RegexOption.IGNORE_CASE)
        private val URL_REGEX = Regex("(https?://)\\S+", RegexOption.IGNORE_CASE)

        private const val TAG = "NativeProcSupervisor"
        private const val MAX_RESTART_ATTEMPTS = 5
        private const val STARTUP_GRACE_MS = 2_000L
        private const val MONITOR_INTERVAL_MS = 5_000L
        private const val BACKOFF_STEP_MS = 2_000L
        private const val FORCE_KILL_GRACE_MS = 1_000L

        private const val NATIVE_LIB = "libp1npplydtransport.so"
    }

    private val handler = Handler(Looper.getMainLooper())
    private var process: Process? = null
    private var stdoutThread: Thread? = null
    private var monitorRunnable: Runnable? = null

    private val running = AtomicBoolean(false)
    private val connected = AtomicBoolean(false)
    private val shuttingDown = AtomicBoolean(false)
    private var restartAttempts = 0
    private var transport = ""
    private var args: List<String> = emptyList()

    val isConnected: Boolean get() = connected.get()
    val isRunning: Boolean get() = running.get()

    fun start(transportType: String, payload: List<String>) {
        if (running.getAndSet(true)) {
            Logx.d(TAG, "already running, ignoring start")
            return
        }
        shuttingDown.set(false)
        restartAttempts = 0
        transport = transportType
        args = payload
        Logx.i(TAG, "start transport=$transportType")
        spawn()
    }

    fun stop() {
        Logx.i(TAG, "stop()")
        shuttingDown.set(true)
        running.set(false)
        connected.set(false)
        monitorRunnable?.let { handler.removeCallbacks(it) }
        monitorRunnable = null
        cleanup()
    }

    private fun spawn() {
        val libPath = "${context.applicationInfo.nativeLibraryDir}/$NATIVE_LIB"
        try {
            // Never log args: they may contain MAX token / URLs.
            val pb = ProcessBuilder(listOf(libPath, "--debug") + args)
                .directory(context.filesDir)
                .redirectErrorStream(true)
            process = pb.start()

            stdoutThread = Thread {
                val batch = StringBuilder(4096)
                var lastFlush = System.currentTimeMillis()

                fun flush() {
                    if (batch.isEmpty()) return
                    val payload = batch.toString().trimEnd('\n')
                    batch.setLength(0)
                    lastFlush = System.currentTimeMillis()
                    if (payload.isNotEmpty()) {
                        EventBus.dispatch(AppEvent.LogMessage(redact(payload)))
                    }
                }

                runCatching {
                    BufferedReader(InputStreamReader(process!!.inputStream)).use { r ->
                        var line: String?
                        while (r.readLine().also { line = it } != null) {
                            val l = line ?: continue
                            if (l.isEmpty()) continue
                            if (batch.isNotEmpty()) batch.append('\n')
                            batch.append(l)

                            // Flush either every 20 lines or every 200ms
                            val now = System.currentTimeMillis()
                            if (batch.length > 2048 || now - lastFlush > 200) {
                                flush()
                            }
                        }
                        flush()
                    }
                }
            }.apply {
                name = "NativeStdoutReader"
                isDaemon = true
                start()
            }

            scheduleMonitor()
            handler.postDelayed({
                if (process?.isAlive == true) {
                    restartAttempts = 0
                    connected.set(true)
                    EventBus.dispatch(AppEvent.TransportConnected)
                    Logx.i(TAG, "native process up")
                } else {
                    Logx.e(TAG, "native process dead after grace")
                    handleDeath()
                }
            }, STARTUP_GRACE_MS)
        } catch (e: Exception) {
            Logx.e(TAG, "spawn failed", e)
            handleDeath()
        }
    }

    private fun scheduleMonitor() {
        val r = object : Runnable {
            override fun run() {
                if (shuttingDown.get()) return
                if (process?.isAlive != true) {
                    handleDeath()
                    return
                }
                handler.postDelayed(this, MONITOR_INTERVAL_MS)
            }
        }
        monitorRunnable = r
        handler.postDelayed(r, MONITOR_INTERVAL_MS)
    }

    private fun handleDeath() {
        if (shuttingDown.get()) return
        connected.set(false)
        EventBus.dispatch(AppEvent.TransportDisconnected)
        restartAttempts++
        cleanup()

        if (restartAttempts >= MAX_RESTART_ATTEMPTS) {
            Logx.e(TAG, "max restart attempts reached, giving up")
            stop()
            return
        }
        val delay = restartAttempts * BACKOFF_STEP_MS
        Logx.w(TAG, "restart #$restartAttempts in ${delay}ms")
        handler.postDelayed({
            if (!shuttingDown.get()) spawn()
        }, delay)
    }

    private fun cleanup() {
        stdoutThread?.interrupt()
        stdoutThread = null
        process?.let { p ->
            if (p.isAlive) p.destroy()
            handler.postDelayed({ if (p.isAlive) p.destroyForcibly() }, FORCE_KILL_GRACE_MS)
        }
        process = null
    }

    /** Redacts obvious secrets (MAX token, URLs). Compiled once. */
    private fun redact(line: String): String {
        // Fast path: most lines don't contain either pattern.
        if (line.indexOf("--maxToken") < 0 &&
            line.indexOf("http://") < 0 &&
            line.indexOf("https://") < 0) {
            return line
        }
        return line
            .replace(TOKEN_REGEX, "$1<redacted>")
            .replace(URL_REGEX, "$1<redacted>")
    }
}
