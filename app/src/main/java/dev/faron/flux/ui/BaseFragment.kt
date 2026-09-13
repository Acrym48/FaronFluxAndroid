package dev.faron.flux.ui

import androidx.fragment.app.Fragment
import dev.faron.flux.event.AppEvent

abstract class BaseFragment : Fragment() {
    abstract fun onNewEvent(ev: AppEvent)
}
