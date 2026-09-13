package dev.faron.flux.data

enum class TransportType {
    yandex,
    vyandex,
    max;

    companion object {
        fun from(raw: String): TransportType =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: yandex
    }
}
