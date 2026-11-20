package com.mts.tikmod.focus

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.mts.tikmod.R
import com.mts.tikmod.utils.L

/** Quick Settings tile: switches "Stop the addiction" on / off from anywhere, TikTok included. */
class FocusTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onClick() {
        super.onClick()
        if (Focus.manualOn(this)) Focus.stop(this) else Focus.start(this, 0)
        refresh()
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val st = Focus.state(this)
        tile.state = if (st.active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = L.t(R.string.focus_title)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = when {
                !st.active -> L.t(R.string.state_off)
                st.until > 0 -> L.t(R.string.f2_until_short, android.text.format.DateFormat.getTimeFormat(this).format(java.util.Date(st.until)))
                else -> L.t(R.string.state_on)
            }
        }
        tile.updateTile()
    }
}
