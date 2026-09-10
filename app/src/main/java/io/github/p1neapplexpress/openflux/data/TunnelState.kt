package io.github.p1neapplexpress.openflux.data

import android.graphics.Color

sealed class TunnelState(val color: Int, open val tunnel: Tunnel?) {
    data object Idle : TunnelState(Color.WHITE, null)
    data class StartingTransport(override val tunnel: Tunnel) : TunnelState(Color.YELLOW, tunnel)
    data class StartingTun2Socks(override val tunnel: Tunnel) : TunnelState(Color.YELLOW, tunnel)
    data class Connecting(override val tunnel: Tunnel) : TunnelState(Color.YELLOW, tunnel)
    data class Running(override val tunnel: Tunnel) : TunnelState(Color.GREEN, tunnel)
    data class Error(val message: String) : TunnelState(Color.RED, null)
}
