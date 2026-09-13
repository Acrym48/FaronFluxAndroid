package dev.faron.flux.service

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import dev.faron.flux.IUnifiedService
import dev.faron.flux.data.Tunnel
import dev.faron.flux.data.TunnelRepository
import dev.faron.flux.event.AppEvent
import dev.faron.flux.event.EventBus
import dev.faron.flux.util.Constants
import dev.faron.flux.util.Logx
import dev.faron.flux.vpn.VPNConfig
import dev.faron.flux.vpn.VpnIntentFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Автоподключение выбранного туннеля после загрузки устройства.
 * Включено только когда в настройках активна опция "Автозапуск при загрузке".
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val ctx = context.applicationContext

        val prefs = ctx.getSharedPreferences(Constants.PREF, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(Constants.PREF_START_ON_BOOT, false)) return

        val tunnel = TunnelRepository(ctx).getSelected() ?: return

        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                connect(ctx, tunnel)
            } catch (e: Exception) {
                Logx.e(TAG, "boot connect failed", e)
                EventBus.dispatch(AppEvent.LogMessage("[E] boot auto-connect failed"))
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun CoroutineScope.connect(ctx: Context, tunnel: Tunnel) {
        EventBus.dispatch(AppEvent.LogMessage("[S] boot auto-connect started"))

        val cfg = VPNConfig(name = tunnel.name)
        val intent = VpnIntentFactory.build(ctx, cfg)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(intent)
        } else {
            ctx.startService(intent)
        }

        var service: IUnifiedService? = null
        var bindJob: Job? = null
        val done = java.util.concurrent.CountDownLatch(1)

        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                service = IUnifiedService.Stub.asInterface(binder)
                done.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                service = null
            }
        }

        bindJob = launch {
            ctx.bindService(Intent(ctx, SocksVpnService::class.java), connection, Context.BIND_AUTO_CREATE)
        }

        var connected = false
        for (i in 1..100) {
            if (done.count == 0L) { connected = true; break }
            delay(50)
        }
        if (!connected || service == null) {
            Logx.e(TAG, "failed to bind to service on boot")
            runCatching { ctx.unbindService(connection) }
            bindJob?.cancel()
            return
        }

        val svc = service
        try {
            Logx.i(TAG, "boot: starting transport")
            EventBus.dispatch(AppEvent.LogMessage("[S] boot: starting transport"))
            svc?.startOpenFluxNative(tunnel.transportType, tunnel.transportConnPayload.toTypedArray())
        } catch (e: Exception) {
            Logx.e(TAG, "boot: transport failed", e)
        }

        var transportReady = false
        for (i in 1..40) {
            delay(250)
            if (svc?.isFServiceRunning() == true) { transportReady = true; break }
        }
        if (!transportReady) {
            Logx.e(TAG, "boot: transport did not start")
            EventBus.dispatch(AppEvent.LogMessage("[E] boot: transport did not start"))
            runCatching { ctx.unbindService(connection) }
            return
        }

        try {
            Logx.i(TAG, "boot: starting tun2socks")
            EventBus.dispatch(AppEvent.LogMessage("[S] boot: starting tun2socks"))
            svc?.startTun2Socks()
        } catch (e: Exception) {
            Logx.e(TAG, "boot: tun2socks failed", e)
        }

        for (i in 1..40) {
            delay(250)
            if (svc?.isVpnRunning() == true) {
                EventBus.dispatch(AppEvent.LogMessage("[I] boot: tunnel connected"))
                Logx.i(TAG, "boot: tunnel connected")
                break
            }
        }

        runCatching { ctx.unbindService(connection) }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}