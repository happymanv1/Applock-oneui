package com.example.service

import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.example.ui.AuthenticationActivity

class AppLockTileService : TileService() {
    
    companion object {
        var isPaused = false
    }

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        val tile = qsTile ?: return
        
        if (isPaused) {
            // Resume immediately without auth
            isPaused = false
            updateTile()
        } else {
            // Unpause requires auth
            val intent = Intent(this, AuthenticationActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(AuthenticationActivity.EXTRA_ACTION, AuthenticationActivity.ACTION_TOGGLE_PAUSE)
            }
            startActivityAndCollapse(intent)
        }
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        if (isPaused) {
            tile.state = Tile.STATE_INACTIVE
            tile.label = "AppLock: Paused"
            tile.subtitle = "Tap to resume"
        } else {
            tile.state = Tile.STATE_ACTIVE
            tile.label = "AppLock: Active"
            tile.subtitle = "Tap to pause"
        }
        tile.updateTile()
    }
}
