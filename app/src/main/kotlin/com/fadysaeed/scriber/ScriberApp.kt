package com.fadysaeed.scriber

import android.app.Application

class ScriberApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
    }
}
