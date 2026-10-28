package app.pockettavern

import android.app.Application
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import app.pockettavern.data.AppDatabase
import app.pockettavern.data.SecretStore

class PocketApplication : Application() {
    val database by lazy { AppDatabase.create(this) }
    val secrets by lazy { SecretStore(this) }
    // The task owner lives for this process; closing/recreating a screen only detaches UI.
    private val controllers = ViewModelStore()
    val controller: AppController by lazy {
        ViewModelProvider(controllers, ViewModelProvider.AndroidViewModelFactory.getInstance(this))[AppController::class.java]
    }
}
