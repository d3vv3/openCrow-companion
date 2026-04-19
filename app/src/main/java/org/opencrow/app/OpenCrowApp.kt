package org.opencrow.app

import android.app.Application
import org.opencrow.app.di.AppContainer

class OpenCrowApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
