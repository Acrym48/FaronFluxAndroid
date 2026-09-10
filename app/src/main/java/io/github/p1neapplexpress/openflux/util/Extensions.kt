package io.github.p1neapplexpress.openflux.util

import android.content.Context

fun Int.dpToPx(context: Context): Int =
    (this * context.resources.displayMetrics.density).toInt()
