package com.fadysaeed.scriber.core

import java.util.Locale

object Units {
    fun bytes(n: Long): String {
        val v = n.toDouble()
        return when {
            n >= 1_000_000_000L -> String.format(Locale.ROOT, "%.2f GB", v / 1e9)
            n >= 1_000_000L -> String.format(Locale.ROOT, "%.0f MB", v / 1e6)
            n >= 1_000L -> String.format(Locale.ROOT, "%.0f KB", v / 1e3)
            else -> "$n B"
        }
    }

    fun seconds(ms: Long): String = String.format(Locale.ROOT, "%.1f s", ms / 1000.0)
}
