package dev.faron.flux.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.bottomnavigation.BottomNavigationView
import dev.faron.flux.R
import dev.faron.flux.event.AppEvent

class MainFragment : BaseFragment() {

    override fun onNewEvent(ev: AppEvent) = Unit

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_main, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val pager = view.findViewById<ViewPager2>(R.id.view_pager)
        val nav = view.findViewById<BottomNavigationView>(R.id.bottomNav)

        pager.adapter = PagerAdapter(requireActivity())

        nav.isVisible = true
        nav.setOnItemSelectedListener { item ->
            val position = when (item.itemId) {
                R.id.nav_tunnels -> 0
                R.id.nav_settings -> 1
                else -> return@setOnItemSelectedListener false
            }
            pager.setCurrentItem(position, true)
            true
        }

        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                nav.menu.getItem(position).isChecked = true
            }
        })
    }

    private inner class PagerAdapter(activity: FragmentActivity) : FragmentStateAdapter(activity) {
        override fun getItemCount(): Int = 2
        override fun createFragment(position: Int): Fragment = when (position) {
            0 -> TunnelsFragment()
            1 -> SettingsFragment()
            else -> error("bad position $position")
        }
    }
}