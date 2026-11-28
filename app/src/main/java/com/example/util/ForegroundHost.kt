// SPDX-License-Identifier: GPL-3.0-or-later
package com.example.util

import android.app.Activity
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Knows which Activity is in front. The agent browser attaches its (invisible) WebView to it: a WebView that is not part of a
 * window is treated as hidden by Chromium and never paints new content, which made screenshots after a scroll come out black.
 */
object ForegroundHost {
    @Volatile private var ref: WeakReference<Activity>? = null
    private val destroyHooks = CopyOnWriteArrayList<(Activity) -> Unit>()

    fun current(): Activity? = ref?.get()?.takeIf { !it.isFinishing && !it.isDestroyed }

    fun onResumed(a: Activity) { ref = WeakReference(a) }

    fun onPaused(a: Activity) { if (ref?.get() === a) ref = null }

    fun onDestroyed(a: Activity) {
        if (ref?.get() === a) ref = null
        destroyHooks.forEach { runCatching { it(a) } }
    }

    fun addDestroyHook(hook: (Activity) -> Unit) { destroyHooks.add(hook) }
}
