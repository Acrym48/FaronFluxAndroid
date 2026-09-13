package dev.faron.flux.ui

import android.animation.ValueAnimator
import android.app.Activity.RESULT_OK
import android.content.res.ColorStateList
import android.net.VpnService
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.switchmaterial.SwitchMaterial
import io.github.g00fy2.quickie.QRResult
import io.github.g00fy2.quickie.ScanQRCode
import dev.faron.flux.R
import dev.faron.flux.data.TransportType
import dev.faron.flux.data.Tunnel
import dev.faron.flux.data.TunnelState
import dev.faron.flux.event.AppEvent
import dev.faron.flux.util.toUptimeHms
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

class TunnelsFragment : BaseFragment() {

    private val vm: TunnelsViewModel by activityViewModels()

    private lateinit var headerSubtitle: TextView
    private lateinit var addButton: View
    private lateinit var heroDot: View
    private lateinit var heroStatus: TextView
    private lateinit var heroTunnel: TextView
    private lateinit var heroUptime: TextView
    private lateinit var heroButton: com.google.android.material.button.MaterialButton
    private lateinit var sectionCount: TextView
    private lateinit var itemsList: LinearLayout
    private lateinit var emptyView: View

    private val dotAnims = mutableMapOf<Long, ValueAnimator>()
    private var heroPulse: ValueAnimator? = null
    private var popup: PopupWindow? = null
    private var pendingTunnel: Tunnel? = null

    private val vpnPermission = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val tunnel = pendingTunnel
        pendingTunnel = null
        if (result.resultCode == RESULT_OK) {
            if (tunnel != null) vm.startTunnel(tunnel) else vm.startCurrent()
        } else {
            Toast.makeText(requireContext(), R.string.vpn_permission_required, Toast.LENGTH_LONG).show()
        }
    }

    private val qrScanner = registerForActivityResult(ScanQRCode()) { result ->
        val raw = (result as? QRResult.QRSuccess)?.content?.rawValue
            ?: return@registerForActivityResult
        runCatching { Json.decodeFromString<Tunnel>(raw) }
            .onSuccess { vm.addTunnel(it); vm.selectTunnel(it); requestVpnAndStart(it) }
            .onFailure {
                Toast.makeText(requireContext(), R.string.qr_scan_failed, Toast.LENGTH_LONG).show()
            }
    }

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?) =
        i.inflate(R.layout.fragment_tunnels, c, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<View>(R.id.headerTitle).setOnLongClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            parentFragmentManager.beginTransaction()
                .replace(R.id.main, LogsFragment())
                .addToBackStack("logs")
                .commit()
            true
        }

        headerSubtitle = view.findViewById(R.id.headerSubtitle)
        addButton = view.findViewById(R.id.addButton)
        heroDot = view.findViewById(R.id.heroDot)
        heroStatus = view.findViewById(R.id.heroStatus)
        heroTunnel = view.findViewById(R.id.heroTunnel)
        heroUptime = view.findViewById(R.id.heroUptime)
        heroButton = view.findViewById(R.id.heroButton)
        sectionCount = view.findViewById(R.id.sectionCount)
        itemsList = view.findViewById(R.id.itemsList)
        emptyView = view.findViewById(R.id.emptyView)

        addButton.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            showAddMenu()
        }

        heroButton.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            handleHeroClick()
        }

        observe()

        vm.peekAutoConnectTarget()?.let { requestVpnAndStart(it) }
    }

    private fun observe() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    vm.active.collect { state ->
                        renderHero(state)
                        renderList()
                    }
                }
                launch {
                    vm.selected.collect {
                        renderHero(vm.active.value)
                        renderList()
                    }
                }
                launch {
                    vm.tunnels.collect {
                        renderList()
                        renderHero(vm.active.value)
                    }
                }
                launch {
                    vm.uptimeSeconds.collect { renderUptime(it) }
                }
            }
        }
    }

    // ───────────── Hero ─────────────

    private fun handleHeroClick() {
        if (vm.tunnels.value.isEmpty()) {
            Toast.makeText(requireContext(), R.string.no_configs, Toast.LENGTH_SHORT).show()
            return
        }
        val state = vm.active.value
        when (state) {
            is TunnelState.Idle, is TunnelState.Error -> {
                val target = vm.selected.value ?: vm.tunnels.value.firstOrNull()?.tunnel
                if (target != null) requestVpnAndStart(target) else {
                    Toast.makeText(requireContext(), R.string.no_tunnel, Toast.LENGTH_SHORT).show()
                }
            }
            else -> vm.stop()
        }
    }

    private fun renderHero(state: TunnelState) {
        val ctx = requireContext()
        val hasTunnels = vm.tunnels.value.isNotEmpty()
        val isOn = state.isActive

        val buttonColor = when (state) {
            is TunnelState.Idle -> MaterialColors.getColor(
                heroButton,
                com.google.android.material.R.attr.colorPrimary,
                ContextCompat.getColor(ctx, R.color.accent_blue)
            )
            else -> state.color
        }
        heroButton.text = getString(if (isOn) R.string.action_disconnect else R.string.action_connect)
        heroButton.backgroundTintList = ColorStateList.valueOf(buttonColor)
        heroButton.isEnabled = hasTunnels

        val label = when (state) {
            is TunnelState.Idle -> getString(R.string.tap_to_connect)
            is TunnelState.Connecting -> getString(R.string.connecting)
            is TunnelState.StartingTransport -> getString(R.string.starting_transport)
            is TunnelState.StartingTun2Socks -> getString(R.string.starting_tsocks)
            is TunnelState.Running -> getString(R.string.running)
            is TunnelState.Error -> state.message
        }
        heroStatus.text = label
        heroStatus.setTextColor(
            if (state is TunnelState.Error) {
                ContextCompat.getColor(ctx, R.color.state_error)
            } else {
                MaterialColors.getColor(
                    ctx,
                    com.google.android.material.R.attr.colorOnSurface,
                    ContextCompat.getColor(ctx, R.color.text_primary)
                )
            }
        )

        heroDot.backgroundTintList = ColorStateList.valueOf(
            if (state is TunnelState.Idle) {
                ContextCompat.getColor(ctx, R.color.state_idle)
            } else {
                state.color
            }
        )
        if (isOn) startHeroPulse() else stopHeroPulse()

        heroTunnel.text = state.tunnel?.name
            ?: vm.selected.value?.name
            ?: getString(R.string.no_tunnel)

        headerSubtitle.text = when {
            hasTunnels && vm.selected.value != null -> vm.selected.value?.name
            hasTunnels -> getString(R.string.section_configs)
            else -> getString(R.string.empty_tunnels)
        }
    }

    private fun renderUptime(seconds: Long) {
        val active = vm.active.value is TunnelState.Running
        heroUptime.visibility = if (active && seconds > 0L) View.VISIBLE else View.GONE
        if (heroUptime.visibility == View.VISIBLE) {
            heroUptime.text = seconds.toUptimeHms()
        }
    }

    private fun startHeroPulse() {
        if (heroPulse?.isRunning == true) return
        heroPulse = ValueAnimator.ofFloat(1f, 1.35f).apply {
            duration = 900L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                val v = it.animatedValue as Float
                heroDot.scaleX = v
                heroDot.scaleY = v
            }
        }.also { it.start() }
    }

    private fun stopHeroPulse() {
        heroPulse?.cancel()
        heroPulse = null
        heroDot.scaleX = 1f
        heroDot.scaleY = 1f
    }

    // ───────────── List ─────────────

    private fun renderList() {
        dotAnims.values.forEach(ValueAnimator::cancel)
        dotAnims.clear()

        val list = vm.tunnels.value
        itemsList.removeAllViews()
        emptyView.isVisible = list.isEmpty()
        sectionCount.text = list.size.toString()

        val state = vm.active.value
        val selectedId = vm.selected.value?.id

        list.forEach { vt ->
            val t = vt.tunnel
            val row = layoutInflater.inflate(R.layout.item_tunnel, itemsList, false)
            val name = row.findViewById<TextView>(R.id.itemName)
            val subtitle = row.findViewById<TextView>(R.id.itemSubtitle)
            val dot = row.findViewById<View>(R.id.itemDot)
            val sw = row.findViewById<SwitchMaterial>(R.id.itemSwitch)
            val card = row.findViewById<MaterialCardView>(R.id.tunnelRow)

            name.text = t.name
            subtitle.text = transportLabel(t)

            val isActive = state.tunnel == t
            dot.backgroundTintList = ColorStateList.valueOf(
                when {
                    isActive -> state.color
                    selectedId == t.id -> ContextCompat.getColor(requireContext(), R.color.state_idle)
                    else -> MaterialColors.getColor(
                        requireContext(),
                        com.google.android.material.R.attr.colorOutlineVariant,
                        ContextCompat.getColor(requireContext(), R.color.bg_card_stroke)
                    )
                }
            )
            if (isActive) startDotPulse(t.id, dot)

            sw.setOnCheckedChangeListener(null)
            sw.isChecked = isActive
            sw.setOnCheckedChangeListener { _, checked ->
                if (checked) onRowClicked(t) else vm.stop()
            }

            card.setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                onRowClicked(t)
            }
            card.setOnLongClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                showItemContextMenu(card, t)
                true
            }

            itemsList.addView(row)
        }
    }

    private fun startDotPulse(id: Long, dot: View) {
        dotAnims[id] = ValueAnimator.ofFloat(1f, 1.4f).apply {
            duration = 800L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                val v = it.animatedValue as Float
                dot.scaleX = v
                dot.scaleY = v
            }
            start()
        }
    }

    private fun onRowClicked(t: Tunnel) {
        val state = vm.active.value
        if (state.isActive && state.tunnel == t) return
        vm.selectTunnel(t)
        requestVpnAndStart(t)
    }

    private fun requestVpnAndStart(t: Tunnel) {
        val intent = VpnService.prepare(requireActivity())
        if (intent != null) {
            pendingTunnel = t
            vpnPermission.launch(intent)
        } else {
            vm.startTunnel(t)
        }
    }

    private fun transportLabel(t: Tunnel): String = when (TransportType.from(t.transportType)) {
        TransportType.yandex -> getString(R.string.yandex_docs_backend)
        TransportType.vyandex -> getString(R.string.vyandex_backend)
        TransportType.max -> getString(R.string.max_messenger_backend)
    }

    // ───────────── Add menu ─────────────

    private fun showAddMenu() {
        val inflater = LayoutInflater.from(requireContext())
        val content = inflater.inflate(R.layout.dropdown_menu, null)

        val pw = PopupWindow(
            content,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            elevation = 12f
            isOutsideTouchable = true
            isFocusable = true
            animationStyle = R.style.DropdownAnimation
            setBackgroundDrawable(
                ContextCompat.getDrawable(requireContext(), R.drawable.bg_dropdown_menu)
            )
        }

        content.findViewById<View>(R.id.option_scan_qr).setOnClickListener {
            pw.dismiss()
            qrScanner.launch(null)
        }
        content.findViewById<View>(R.id.option_enter_manually).setOnClickListener {
            pw.dismiss()
            parentFragmentManager.beginTransaction()
                .replace(R.id.main, AddTunFragment.new())
                .addToBackStack("add")
                .commit()
        }

        popup = pw
        content.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val x = addButton.width - content.measuredWidth
        pw.showAsDropDown(addButton, x, 8)
        pw.setOnDismissListener { popup = null }
    }

    // ───────────── Context menu / delete ─────────────

    private fun showItemContextMenu(anchor: View, tunnel: Tunnel) {
        val inflater = LayoutInflater.from(requireContext())
        val menuView = inflater.inflate(R.layout.popup_item_menu, null)

        val menu = PopupWindow(
            menuView,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            elevation = 12f
            isOutsideTouchable = true
            isFocusable = true
            animationStyle = R.style.DropdownAnimation
            setBackgroundDrawable(
                ContextCompat.getDrawable(requireContext(), R.drawable.bg_menu_popup)
            )
        }

        menuView.findViewById<View>(R.id.menu_edit).setOnClickListener {
            menu.dismiss()
            popup?.dismiss()
            parentFragmentManager.beginTransaction()
                .replace(R.id.main, AddTunFragment.edit(tunnel))
                .addToBackStack("edit")
                .commit()
        }

        menuView.findViewById<View>(R.id.menu_delete).setOnClickListener {
            menu.dismiss()
            confirmDelete(tunnel)
        }

        menu.showAsDropDown(anchor, 0, 4)
    }

    private fun confirmDelete(tunnel: Tunnel) {
        val active = vm.active.value
        if (active.isActive && active.tunnel == tunnel) {
            Toast.makeText(requireContext(), R.string.cannot_delete_active, Toast.LENGTH_SHORT).show()
            return
        }

        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.delete_config_title)
            .setMessage(R.string.delete_config_msg)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                vm.removeTunnel(tunnel)
                popup?.dismiss()
                Toast.makeText(requireContext(), R.string.config_deleted, Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    override fun onDestroyView() {
        dotAnims.values.forEach(ValueAnimator::cancel)
        dotAnims.clear()
        stopHeroPulse()
        popup?.dismiss()
        popup = null
        super.onDestroyView()
    }

    override fun onNewEvent(ev: AppEvent) = Unit

    companion object {
        fun new() = TunnelsFragment()
    }
}