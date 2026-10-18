package app.pockettavern

import android.app.Application
import app.pockettavern.data.AppDatabase
import app.pockettavern.data.SecretStore

class PocketApplication : Application() {
    val database by lazy { AppDatabase.create(this) }
    val secrets by lazy { SecretStore(this) }
}
