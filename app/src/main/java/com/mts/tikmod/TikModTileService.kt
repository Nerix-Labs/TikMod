package com.mts.tikmod

import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast

class TikModTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()
        val isActive = Prefs.isModuleActive(this)
        if (isActive) {
            Prefs.forceRestartTikTok(this)
            Toast.makeText(this, "⚡ TikMod: נשלחה בקשת הפעלה מחדש ל-TikTok", Toast.LENGTH_SHORT).show()
        } else {
            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                runCatching { startActivity(intent) }
            } else {
                startActivityAndCollapse(intent)
            }
        }
        updateTileState()
    }

    private fun updateTileState() {
        val tile = qsTile ?: return
        val isActive = Prefs.isModuleActive(this)

        tile.state = if (isActive) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = if (isActive) "TikMod (פעיל)" else "TikMod (לא פעיל)"
        tile.subtitle = if (isActive) "לחץ לרענון TikTok" else "לחץ לפתח הגדרות"
        tile.updateTile()
    }
}
