package org.opencrow.app

import android.app.Application
import org.opencrow.app.data.local.AppDatabase
import org.opencrow.app.data.remote.ApiClient

class OpenCrowApp : Application() {
    lateinit var database: AppDatabase
        private set
    lateinit var apiClient: ApiClient
        private set

    override fun onCreate() {
        super.onCreate()
        database = AppDatabase.getInstance(this)
        apiClient = ApiClient(database.configDao())
    }
}
