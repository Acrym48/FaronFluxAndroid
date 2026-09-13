package dev.faron.flux.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.edit
import com.google.android.material.switchmaterial.SwitchMaterial
import dev.faron.flux.BuildConfig
import dev.faron.flux.R
import dev.faron.flux.event.AppEvent
import dev.faron.flux.util.Constants

class SettingsFragment : BaseFragment() {

    override fun onNewEvent(ev: AppEvent) = Unit

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?) =
        i.inflate(R.layout.fragment_settings, c, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val prefs = requireContext().getSharedPreferences(Constants.PREF, Context.MODE_PRIVATE)

        view.findViewById<View>(R.id.rowLogs).setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.main, LogsFragment())
                .addToBackStack("logs")
                .commit()
        }

        view.findViewById<View>(R.id.rowFork).setOnClickListener {
            val url = getString(R.string.settings_fork_url)
            runCatching {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }.onFailure {
                Toast.makeText(requireContext(), url, Toast.LENGTH_LONG).show()
            }
        }

        view.findViewById<TextView>(R.id.versionValue).text = BuildConfig.VERSION_NAME

        view.findViewById<SwitchMaterial>(R.id.swAutoConnect).apply {
            isChecked = prefs.getBoolean(Constants.PREF_AUTO_CONNECT, false)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit { putBoolean(Constants.PREF_AUTO_CONNECT, checked) }
            }
        }

        view.findViewById<SwitchMaterial>(R.id.swStartOnBoot).apply {
            isChecked = prefs.getBoolean(Constants.PREF_START_ON_BOOT, false)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit { putBoolean(Constants.PREF_START_ON_BOOT, checked) }
            }
        }
    }
}