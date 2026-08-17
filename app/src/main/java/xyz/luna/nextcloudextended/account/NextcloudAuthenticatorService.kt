package xyz.luna.nextcloudextended.account

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * Bound service that exposes [NextcloudAccountAuthenticator] to the Android account system.
 * The system binds to this service when adding/managing a Nextcloud Extended account.
 */
class NextcloudAuthenticatorService : Service() {

    private lateinit var authenticator: NextcloudAccountAuthenticator

    override fun onCreate() {
        super.onCreate()
        authenticator = NextcloudAccountAuthenticator(this)
    }

    override fun onBind(intent: Intent?): IBinder = authenticator.iBinder
}