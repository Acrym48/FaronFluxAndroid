package dev.faron.flux.event

sealed interface AppEvent {
    data class LogMessage(val message: String) : AppEvent
    data class ToggleTunnel(val id: Long, val enabled: Boolean) : AppEvent
    data object TransportConnected : AppEvent
    data object TransportDisconnected : AppEvent
}
