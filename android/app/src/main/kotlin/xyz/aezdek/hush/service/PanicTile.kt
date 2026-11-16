package xyz.aezdek.hush.service

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.launch
import xyz.aezdek.hush.Graph
import xyz.aezdek.hush.Wipe

/**
 * "Quick clear" in the phone's quick settings, for whoever adds it there: one tap takes Hush's account off this phone
 * (see [Wipe]). A locked phone asks to be unlocked first, so nobody else can do it for you. Signing in again brings
 * everything back.
 */
class PanicTile : TileService() {
    override fun onStartListening() {
        qsTile?.apply {
            state = if (Graph.client.signedIn) Tile.STATE_INACTIVE else Tile.STATE_UNAVAILABLE
            updateTile()
        }
    }

    override fun onClick() {
        val wipe = Runnable {
            val app = applicationContext
            Graph.scope.launch {
                Wipe.now(app)
                // A disguised Hush goes back behind its disguise as well.
                xyz.aezdek.hush.disguise.Disguise.lock()
            }
            qsTile?.apply {
                state = Tile.STATE_UNAVAILABLE
                updateTile()
            }
        }
        if (isLocked) unlockAndRun(wipe) else wipe.run()
    }
}
