package io.github.p1neapplexpress.openflux.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.switchmaterial.SwitchMaterial
import io.github.p1neapplexpress.openflux.R
import io.github.p1neapplexpress.openflux.data.TransportType
import io.github.p1neapplexpress.openflux.data.Tunnel
import io.github.p1neapplexpress.openflux.event.AppEvent
import io.github.p1neapplexpress.openflux.util.dpToPx
import kotlin.random.Random

class AddTunFragment : BaseFragment() {

    private val vm: TunnelsViewModel by activityViewModels()
    private var transport = TransportType.yandex
    private var debug = false

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?) =
        i.inflate(R.layout.fragment_add_tun, c, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val transportLayout = view.findViewById<View>(R.id.select_transport_layout)
        val maxContainer = view.findViewById<View>(R.id.maxContainer)
        val yandexContainer = view.findViewById<View>(R.id.yandexUrlContainer)
        val transportLabel = view.findViewById<TextView>(R.id.selectedTransport)
        val debugLabel = view.findViewById<TextView>(R.id.selectedDebug)
        val debugSwitch = view.findViewById<SwitchMaterial>(R.id.debugSwitch)
        val docUrl = view.findViewById<TextView>(R.id.documentUrl)
        val maxToken = view.findViewById<TextView>(R.id.maxToken)
        val maxUid = view.findViewById<TextView>(R.id.maxUserId)
        val name = view.findViewById<TextView>(R.id.name)
        val save = view.findViewById<Button>(R.id.saveButton)

        transportLayout.setOnClickListener {
            it.showDropdown(
                onYandex = {
                    transport = TransportType.yandex
                    maxContainer.isVisible = false
                    yandexContainer.isVisible = true
                    transportLabel.text = getString(R.string.yandex_docs_backend)
                },
                onMax = {
                    transport = TransportType.max
                    maxContainer.isVisible = true
                    yandexContainer.isVisible = false
                    transportLabel.text = getString(R.string.max_messenger_backend)
                },
            )
        }

        debugSwitch.setOnCheckedChangeListener { _, checked ->
            debug = checked
            debugLabel.text = getString(if (checked) R.string.on else R.string.off)
        }

        save.setOnClickListener {
            val n = name.text.trim().toString()
            if (n.isEmpty()) {
                Toast.makeText(requireContext(), R.string.name_required, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            createTunnel(n, docUrl.text.trim().toString(), maxToken.text.trim().toString(), maxUid.text.trim().toString())
                ?.let { vm.addTunnel(it); requireActivity().onBackPressedDispatcher.onBackPressed() }
        }

        transportLabel.text = getString(R.string.yandex_docs_backend)
        debugLabel.text = getString(R.string.off)
    }

    private fun createTunnel(name: String, docUrl: String, maxToken: String, maxUid: String): Tunnel? {
        val payload = when (transport) {
            TransportType.yandex -> {
                if (docUrl.isEmpty()) return null
                buildList {
                    add("--client"); add("--transport"); add("yandex")
                    add("--url"); add(docUrl)
                    if (debug) add("--debug")
                }
            }
            TransportType.max -> {
                if (maxToken.isEmpty() || maxUid.isEmpty()) return null
                buildList {
                    add("--client"); add("--transport"); add("oneme")
                    add("--maxToken"); add(maxToken)
                    add("--maxUid"); add(maxUid)
                    if (debug) add("--debug")
                }
            }
        }
        return Tunnel(
            id = Random(System.currentTimeMillis()).nextLong(),
            name = name,
            transportType = transport.name,
            transportConnPayload = payload,
        )
    }

    override fun onNewEvent(ev: AppEvent) = Unit

    private fun View.showDropdown(onYandex: () -> Unit, onMax: () -> Unit) {
        val popupView = LayoutInflater.from(context).inflate(R.layout.dropdown_transport_menu, null)
        val popup = PopupWindow(popupView, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            setBackgroundDrawable(ContextCompat.getDrawable(context, R.drawable.bg_dropdown_transports))
            elevation = 8.dpToPx(context).toFloat()
            animationStyle = R.style.DropdownAnimation
            isOutsideTouchable = true
            isFocusable = true
        }
        popupView.findViewById<View>(R.id.option_scan_qr)?.setOnClickListener { onYandex(); popup.dismiss() }
        popupView.findViewById<View>(R.id.option_enter_manually)?.setOnClickListener { onMax(); popup.dismiss() }
        popupView.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        popup.showAsDropDown(this, 0, -popupView.measuredHeight - height - 8.dpToPx(context))
    }

    companion object { fun new() = AddTunFragment() }
}
