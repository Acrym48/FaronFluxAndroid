package dev.faron.flux

import android.app.Application
import dev.faron.flux.util.Logx

class OpenFluxApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Logx.init(BuildConfig.DEBUG)
    }
}
