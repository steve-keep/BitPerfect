package com.bitperfect.core.utils

import android.os.SystemClock

interface MonotonicClock {
    fun nowMs(): Long
}

class DefaultMonotonicClock : MonotonicClock {
    override fun nowMs(): Long {
        return SystemClock.elapsedRealtime()
    }
}
