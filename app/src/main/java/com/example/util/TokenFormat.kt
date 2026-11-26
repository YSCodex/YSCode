// SPDX-License-Identifier: GPL-3.0-or-later
package com.example.util

import java.util.Locale

/** 950 -> "950", 12_500 -> "12.5k", 100_000 -> "100k", 4_504_000 -> "4.5M", 1_200_000_000 -> "1.2B". */
fun formatTokens(n: Long): String {
    fun trim(v: Double, suffix: String): String {
        val s = String.format(Locale.US, "%.1f", v)
        return (if (s.endsWith(".0")) s.dropLast(2) else s) + suffix
    }
    return when {
        n >= 1_000_000_000L -> trim(n / 1_000_000_000.0, "B")
        n >= 1_000_000L -> trim(n / 1_000_000.0, "M")
        n >= 100_000L -> String.format(Locale.US, "%.0fk", n / 1000.0)
        n >= 1_000L -> trim(n / 1000.0, "k")
        else -> n.toString()
    }
}
