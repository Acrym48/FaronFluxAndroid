package io.github.p1neapplexpress.openflux.ui

import android.app.Activity.RESULT_OK
import android.net.VpnService
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupWindow
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.github.g00fy2.quickie.QRResult
import io.github.g00fy2.quickie.ScanQRCode
import io.github.p1neapplexpress.openflux.R
import io.github.p1neapplexpress.openflux.data.Tunnel
import io.github.p1neapplexpress.openflux.event.AppEvent
import io.github.p1neapplexpress.openflux.ui.rv.TunAdapter
import io.github.p1neapplexpress.openflux.util.dpToPx
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

class TunnelsFragment : BaseFragment() {

    private val vm: TunnelsViewModel by activityViewModels()
    private val adapter = TunAdapter()
    private var pendingToggle: AppEvent.ToggleTunnel? = null

    private val vpnPermission = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) startPending()
        else Toast.makeText(requireContext(), R.string.vpn_permission_required, Toast.LENGTH_LONG).show()
    }

    private val qrScanner = registerForActivityResult(ScanQRCode()) { result ->
        val raw = (result as? QRResult.QRSuccess)?.content?.rawValue ?: return@registerForActivityResult
        runCatching { Json.decodeFromString<Tunnel>(raw) }
            .onSuccess { vm.addTunnel(it) }
            .onFailure { Toast.makeText(requireContext(), R.string.qr_scan_failed, Toast.LENGTH_LONG).show() }
    }

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?) =
        i.inflate(R.layout.fragment_yandex_transport, c, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val addButton = view.findViewById<View>(R.id.add_tunnel)
        val overlay = view.findViewById<View>(R.id.by)
        val rv = view.findViewById<RecyclerView>(R.id.tunnels_rv)

        addButton.setOnClickListener {
            it.showDropdown(overlay, onScan = { qrScanner.launch(null) }, onManual = {
                parentFragmentManager.beginTransaction()
                    .replace(R.id.main, AddTunFragment.new())
                    .addToBackStack("add")
                    .commit()
            })
        }

        rv.adapter = adapter
        rv.layoutManager = LinearLayoutManager(requireContext())

        viewLifecycleOwner.lifecycleScope.launch {
            vm.active.collect { adapter.activeTunnel = it }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            vm.tunnels.collect { adapter.items = it }
        }
    }

    private fun requestVpn() {
        val intent = VpnService.prepare(requireActivity())
        if (intent != null) vpnPermission.launch(intent) else startPending()
    }

    private fun startPending() {
        val t = pendingToggle ?: return
        vm.tunnels.value.firstOrNull { it.tunnel.id == t.id }?.let { vm.startTunnel(it.tunnel) }
    }

    override fun onNewEvent(ev: AppEvent) {
        if (ev !is AppEvent.ToggleTunnel) return
        if (ev.enabled) {
            pendingToggle = ev
            requestVpn()
        } else vm.stop()
    }

    private fun View.showDropdown(overlay: View, onScan: () -> Unit, onManual: () -> Unit) {
        val popupView = LayoutInflater.from(context).inflate(R.layout.dropdown_menu, null)
        val popup = PopupWindow(popupView, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            setBackgroundDrawable(ContextCompat.getDrawable(context, R.drawable.bg_dropdown))
            elevation = 8.dpToPx(context).toFloat()
            animationStyle = R.style.DropdownAnimation
            isOutsideTouchable = true
            isFocusable = true
            setOnDismissListener { overlay.isInvisible = true }
            overlay.isVisible = true
        }
        popupView.findViewById<View>(R.id.option_scan_qr)?.setOnClickListener { onScan(); popup.dismiss() }
        popupView.findViewById<View>(R.id.option_enter_manually)?.setOnClickListener { onManual(); popup.dismiss() }
        popupView.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        popup.showAsDropDown(this, 0, -popupView.measuredHeight - height - 8.dpToPx(context))
    }

    companion object { fun new() = TunnelsFragment() }
}
