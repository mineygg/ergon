package com.mineygg.ergon

import android.app.Application
import com.mineygg.ergon.core.DeviceHeaders
import com.mineygg.ergon.core.Notifier
import com.mineygg.ergon.core.RunBus
import com.mineygg.ergon.data.Store
import com.mineygg.ergon.work.Scheduler

class ClaimApp : Application() {
    lateinit var store: Store
        private set

    override fun onCreate() {
        super.onCreate()
        DeviceHeaders.init(this)
        store = Store(this)
        Notifier.createChannels(this)
        RunBus.restore(store.savedLog)
        Scheduler.schedule(this, store.settings.value, replace = false)
    }
}
