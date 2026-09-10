package io.github.p1neapplexpress.openflux.ui

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.p1neapplexpress.openflux.IUnifiedService
import io.github.p1neapplexpress.openflux.data.Tunnel
import io.github.p1neapplexpress.openflux.data.TunnelRepository
import io.github.p1neapplexpress.openflux.data.TunnelState
import io.github.p1neapplexpress.openflux.data.TunnelViewType
import io.github.p1neapplexpress.openflux.service.SocksVpnService
import io.github.p1neapplexpress.openflux.util.Logx
import io.github.p1neapplexpress.openflux.vpn.VPNConfig
import io.github.p1neapplexpress.openflux.vpn.VpnIntentFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class TunnelsViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        private const val TAG = "TunnelsViewModel"
        private const val BIND_TIMEOUT_MS = 5_000L
        private const val BIND_POLL_MS = 50L
        private const val TRANSPORT_TIMEOUT_MS = 7_500L
        private const val TUN2SOCKS_TIMEOUT_MS = 3_750L
        private const val POLL_MS = 250L
    }

    private val repo = TunnelRepository(app)

    private var service: IUnifiedService? = null
    private var bound = false
    private var activeTunnel: Tunnel? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = IUnifiedService.Stub.asInterface(binder)
            bound = true
            Logx.d(TAG, "service connected")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
            if (_active.value !is TunnelState.Idle) {
                _active.value = TunnelState.Idle
                refresh()
            }
        }
    }

    private val _tunnels = MutableStateFlow<List<TunnelViewType>>(emptyList())
    val tunnels: StateFlow<List<TunnelViewType>> = _tunnels.asStateFlow()

    private val _active = MutableStateFlow<TunnelState>(TunnelState.Idle)
    val active: StateFlow<TunnelState> = _active.asStateFlow()

    init { refresh() }

    fun startTunnel(tunnel: Tunnel) {
        val running = _active.value
        if (running is TunnelState.Running && running.tunnel == tunnel) return
        if (running !is TunnelState.Idle) stop()

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

        activeTunnel = tunnel

        viewModelScope.launch(Dispatchers.IO) {
            if (!awaitBind()) {
                _active.value = TunnelState.Error("Service not connected")
                return@launch
            }

            _active.value = TunnelState.StartingTransport(tunnel)
            runCatching {
                service?.startOpenFluxNative(tunnel.transportType, tunnel.transportConnPayload.toTypedArray())
            }.onFailure {
                _active.value = TunnelState.Error("Transport start failed: ${it.message}")
                return@launch
            }

            if (!awaitFlag(TRANSPORT_TIMEOUT_MS) { service?.isFServiceRunning() == true }) {
                _active.value = TunnelState.Error("Transport did not start")
                return@launch
            }

            _active.value = TunnelState.StartingTun2Socks(tunnel)
            runCatching { service?.startTun2Socks() }
                .onFailure {
                    _active.value = TunnelState.Error("tun2socks start failed: ${it.message}")
                    return@launch
                }

            if (!awaitFlag(TUN2SOCKS_TIMEOUT_MS) { service?.isVpnRunning() == true }) {
                _active.value = TunnelState.Error("tun2socks did not start")
                return@launch
            }

            _active.value = TunnelState.Running(tunnel)
            refresh()
        }
    }

    fun stop() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                service?.stopOpenFluxNative()
                service?.stopVpn()
            }
            val ctx = getApplication<Application>()
            runCatching { ctx.unbindService(connection) }
            bound = false
            service = null
            activeTunnel = null
            _active.value = TunnelState.Idle
            refresh()
        }
    }

    fun refresh() {
        val list = repo.load()
        val running = (_active.value as? TunnelState.Running)?.tunnel
        _tunnels.value = list.map { TunnelViewType(it, enabled = it == running) }
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

    private suspend fun awaitBind(): Boolean {
        var waited = 0L
        while (!bound && waited < BIND_TIMEOUT_MS) {
            delay(BIND_POLL_MS); waited += BIND_POLL_MS
        }
        return bound && service != null
    }

    private suspend fun awaitFlag(timeoutMs: Long, check: () -> Boolean): Boolean {
        var waited = 0L
        while (waited < timeoutMs) {
            if (runCatching { check() }.getOrDefault(false)) return true
            delay(POLL_MS); waited += POLL_MS
        }
        return false
    }

    override fun onCleared() {
        super.onCleared()
        runCatching { getApplication<Application>().unbindService(connection) }
    }
}
