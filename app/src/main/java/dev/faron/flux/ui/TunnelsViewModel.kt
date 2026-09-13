package dev.faron.flux.ui

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.faron.flux.IUnifiedService
import dev.faron.flux.data.Tunnel
import dev.faron.flux.data.TunnelRepository
import dev.faron.flux.data.TunnelState
import dev.faron.flux.data.TunnelViewType
import dev.faron.flux.service.SocksVpnService
import dev.faron.flux.util.Constants
import dev.faron.flux.util.Logx
import dev.faron.flux.vpn.VPNConfig
import dev.faron.flux.vpn.VpnIntentFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TunnelsViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        private const val TAG = "TunnelsViewModel"
    }

    private val repo = TunnelRepository(app)

    private var service: IUnifiedService? = null
    private var bound = false
    private var activeTunnelData: Tunnel? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = IUnifiedService.Stub.asInterface(binder)
            bound = true
            Logx.d(TAG, "service connected")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
            Logx.d(TAG, "service disconnected")
            if (_active.value !is TunnelState.Idle) {
                _active.value = TunnelState.Idle
                stopUptimeCounter()
                refresh()
            }
        }
    }

    private val _tunnels = MutableStateFlow<List<TunnelViewType>>(emptyList())
    val tunnels: StateFlow<List<TunnelViewType>> = _tunnels.asStateFlow()

    private val _active = MutableStateFlow<TunnelState>(TunnelState.Idle)
    val active: StateFlow<TunnelState> = _active.asStateFlow()

    private val _uptimeSeconds = MutableStateFlow(0L)
    val uptimeSeconds: StateFlow<Long> = _uptimeSeconds.asStateFlow()

    private val _selected = MutableStateFlow<Tunnel?>(null)
    val selected: StateFlow<Tunnel?> = _selected.asStateFlow()

    val selectedTunnelId: Long? get() = _selected.value?.id

    private var uptimeJob: Job? = null
    private var autoConnectHandled = false

    init { refresh() }

    fun startCurrent() {
        val tunnel = _active.value.tunnel
            ?: _selected.value
            ?: repo.getSelected()
            ?: return
        startTunnel(tunnel)
    }

    /** Возвращает туннель для одноразового автоподключения при запуске, либо null. */
    fun peekAutoConnectTarget(): Tunnel? {
        if (autoConnectHandled) return null
        autoConnectHandled = true
        if (!prefsAutoConnect()) return null
        if (_active.value.isActive) return null
        refresh()
        return _selected.value ?: repo.getSelected()
    }

    private fun prefsAutoConnect(): Boolean =
        getApplication<Application>()
            .getSharedPreferences(Constants.PREF, Context.MODE_PRIVATE)
            .getBoolean(Constants.PREF_AUTO_CONNECT, false)

    fun startTunnel(tunnel: Tunnel) {
        val running = _active.value
        if (running is TunnelState.Running && running.tunnel == tunnel) return

        viewModelScope.launch {
            if (_active.value.isActive) stopSuspend()
            startTunnelFlow(tunnel)
        }
    }

    private suspend fun startTunnelFlow(tunnel: Tunnel) {
        _active.value = TunnelState.Connecting(tunnel)

        val ctx = getApplication<Application>()
        val cfg = VPNConfig(name = tunnel.name)
        val intent = VpnIntentFactory.build(ctx, cfg)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(intent)
        } else {
            ctx.startService(intent)
        }

        ctx.bindService(
            Intent(ctx, SocksVpnService::class.java),
            connection,
            Context.BIND_AUTO_CREATE,
        )

        activeTunnelData = tunnel

        withContext(Dispatchers.IO) {
            var attempts = 0
            while (!bound && attempts < 100) {
                delay(50)
                attempts++
            }
            if (!bound || service == null) {
                Logx.e(TAG, "failed to bind to service")
                _active.value = TunnelState.Error("Service not connected")
                return@withContext
            }
            Logx.i(TAG, "service bound, starting transport")

            _active.value = TunnelState.StartingTransport(tunnel)
            try {
                service?.startOpenFluxNative(
                    tunnel.transportType,
                    tunnel.transportConnPayload.toTypedArray()
                )
            } catch (e: Exception) {
                Logx.e(TAG, "startOpenFluxNative failed", e)
                _active.value = TunnelState.Error("Transport failed: ${e.message}")
                return@withContext
            }

            var transportReady = false
            for (i in 1..40) {
                delay(250)
                try {
                    if (service?.isFServiceRunning() == true) {
                        transportReady = true
                        Logx.i(TAG, "transport started after ${i * 250}ms")
                        break
                    }
                } catch (e: Exception) {
                    Logx.e(TAG, "isFServiceRunning threw", e)
                }
            }
            if (!transportReady) {
                Logx.e(TAG, "transport did not start")
                _active.value = TunnelState.Error("Transport did not start")
                return@withContext
            }

            _active.value = TunnelState.StartingTun2Socks(tunnel)
            try {
                service?.startTun2Socks()
            } catch (e: Exception) {
                Logx.e(TAG, "startTun2Socks failed", e)
                _active.value = TunnelState.Error("tun2socks failed: ${e.message}")
                return@withContext
            }

            var vpnReady = false
            for (i in 1..40) {
                delay(250)
                try {
                    if (service?.isVpnRunning() == true) {
                        vpnReady = true
                        Logx.i(TAG, "tun2socks started after ${i * 250}ms")
                        break
                    }
                } catch (e: Exception) {
                    Logx.e(TAG, "isVpnRunning threw", e)
                }
            }

            if (vpnReady) {
                _active.value = TunnelState.Running(tunnel)
                startUptimeCounter()
            } else {
                Logx.e(TAG, "tun2socks did not start")
                _active.value = TunnelState.Error("tun2socks did not start")
            }
            refresh()
        }
    }

    private suspend fun stopSuspend() {
        Logx.i(TAG, "stopSuspend()")
        withContext(Dispatchers.IO) {
            try {
                service?.stopOpenFluxNative()
                service?.stopVpn()
            } catch (e: Exception) {
                Logx.e(TAG, "stop failed", e)
            }
            val ctx = getApplication<Application>()
            try { ctx.unbindService(connection) } catch (_: Exception) {}
            bound = false
            service = null
            activeTunnelData = null
            _active.value = TunnelState.Idle
            stopUptimeCounter()
            refresh()
        }
    }

    fun stop() {
        Logx.i(TAG, "stop()")
        viewModelScope.launch { stopSuspend() }
    }

    fun refresh() {
        val list = repo.load()
        val running = (_active.value as? TunnelState.Running)?.tunnel
        _tunnels.value = list.map { TunnelViewType(it, enabled = it == running) }
        _selected.value = repo.getSelected()
    }

    fun selectTunnel(tunnel: Tunnel) {
        if (_active.value.isActive) {
            
            stop()
        }
        repo.setSelectedId(tunnel.id)
        _selected.value = tunnel
    }

    fun addTunnel(tunnel: Tunnel) {
        val current = repo.load().toMutableList()
        if (current.none { it.id == tunnel.id }) {
            current.add(tunnel)
            repo.save(current)
            refresh()
        }
    }

    fun removeTunnel(tunnel: Tunnel) {
        if (_active.value.tunnel == tunnel) stop()
        val current = repo.load().toMutableList()
        current.removeAll { it.id == tunnel.id }
        repo.save(current)
        refresh()
    }

    fun updateTunnel(old: Tunnel, new: Tunnel) {
        val current = repo.load().toMutableList()
        val idx = current.indexOfFirst { it.id == old.id }
        if (idx < 0) return
        if (_active.value.tunnel == old) stop()
        current[idx] = new
        repo.save(current)
        refresh()
    }

    private fun startUptimeCounter() {
        uptimeJob?.cancel()
        _uptimeSeconds.value = 0L
        uptimeJob = viewModelScope.launch {
            val startedAt = System.currentTimeMillis()
            while (isActive) {
                _uptimeSeconds.value = (System.currentTimeMillis() - startedAt) / 1000L
                delay(1_000L)
            }
        }
    }

    private fun stopUptimeCounter() {
        uptimeJob?.cancel()
        uptimeJob = null
        _uptimeSeconds.value = 0L
    }

    override fun onCleared() {
        super.onCleared()
        stopUptimeCounter()
        try { getApplication<Application>().unbindService(connection) } catch (_: Exception) {}
    }
}
