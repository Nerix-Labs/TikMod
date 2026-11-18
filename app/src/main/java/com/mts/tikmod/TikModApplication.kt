package com.mts.tikmod

import android.app.Application
import com.mts.tikmod.utils.L

class TikModApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        L.init(this)
    }
}
