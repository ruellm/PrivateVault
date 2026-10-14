package com.privatevault.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

// The gallery (docs/PHASE3B_DESIGN.md Section 3): a grid of one tile per
// object, newest first, listed without decrypting. Thumbnails decode lazily
// on the viewer thread through PhotoLoader; objects that are not photos get a
// labelled grey tile. Every thumbnail is erased when the activity stops or
// the vault locks. Not exported; routes to the lock screen when not unlocked.
class GalleryActivity : Activity(), LockController.Listener {

    companion object {
        const val TAG = "PrivateVault"
        const val COLUMNS = 3
    }

    private val main = Handler(Looper.getMainLooper())
    private val lock: LockController get() = PvApp.of(this).lock
    private val vaults: VaultSession get() = PvApp.of(this).vaultSession
    private lateinit var grid: GridView
    private lateinit var status: TextView
    private val adapter = TileAdapter()
    private var entries: List<Vault.ObjectEntry> = emptyList()
    // Tile labels of objects that are not photos, by ID hex, so each is loaded once.
    private val kinds = HashMap<String, String>()
    private val pending = HashSet<String>()
    private var started = false

    // Thumbnails of this screen; emptied in onStop and on lock. Tests read it.
    val cache = ThumbCache()

    private fun routeIfLocked(): Boolean {
        if (lock.state == LockState.UNLOCKED) return false
        startActivity(Intent(this, LockActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
        return true
    }

    override fun onLockStateChanged(state: LockState) {
        if (state != LockState.UNLOCKED && !isFinishing) {
            cache.clear()
            routeIfLocked()
        }
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        lock.touch()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        if (routeIfLocked()) return
        lock.addListener(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        status = TextView(this).apply { setPadding(16, 8, 16, 8) }
        grid = GridView(this).apply {
            numColumns = COLUMNS
            horizontalSpacing = 4
            verticalSpacing = 4
            adapter = this@GalleryActivity.adapter
            setOnItemClickListener { _, _, position, _ -> openViewer(position) }
        }
        root.addView(status)
        root.addView(grid, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    override fun onStart() {
        super.onStart()
        started = true
        if (routeIfLocked()) return
        refresh()
    }

    override fun onStop() {
        started = false
        pending.clear()
        cache.clear()
        super.onStop()
    }

    override fun onDestroy() {
        lock.removeListener(this)
        super.onDestroy()
    }

    // Rebuilds the list from the directory. Nothing is decrypted here.
    fun refresh() {
        val v = vaults.vault
        entries = v?.listObjects() ?: emptyList()
        kinds.clear()
        adapter.notifyDataSetChanged()
        status.text = "${entries.size} objects"
        Log.i(TAG, "gallery: ${entries.size} objects listed")
    }

    private fun openViewer(position: Int) {
        val entry = entries.getOrNull(position) ?: return
        if (cache.get(entry.hex) == null) return   // only photo tiles open
        startActivity(Intent(this, ViewerActivity::class.java).putExtra(ViewerActivity.EXTRA_ID, entry.hex))
    }

    private fun requestLoad(entry: Vault.ObjectEntry) {
        val hex = entry.hex
        if (!pending.add(hex)) return
        PhotoLoader.executor.execute {
            val v = vaults.vault
            val result = if (v == null) PhotoLoader.Loaded.Cancelled else PhotoLoader.load(v, entry.id, PhotoLoader.THUMB_PX, PhotoLoader.THUMB_PX)
            main.post { onLoaded(hex, result) }
        }
    }

    private fun onLoaded(hex: String, result: PhotoLoader.Loaded) {
        pending.remove(hex)
        if (result is PhotoLoader.Loaded.Photo) {
            if (!started || lock.state != LockState.UNLOCKED) {
                PhotoLoader.dispose(result.bitmap)
                return
            }
            cache.put(hex, PhotoLoader.turned(result.bitmap, result.turnDegrees))
        } else {
            if (result !is PhotoLoader.Loaded.Cancelled) kinds[hex] = result.label()
        }
        Log.i(TAG, "gallery: $hex ${result.label()}")
        if (started) adapter.notifyDataSetChanged()
    }

    // A square cell: a thumbnail, or a grey square with a label.
    private class Tile(context: Context) : FrameLayout(context) {
        val image = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Color.DKGRAY)
        }
        val label = TextView(context).apply {
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
        }

        init {
            addView(image, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            addView(label, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(widthMeasureSpec, widthMeasureSpec)
        }
    }

    private inner class TileAdapter : BaseAdapter() {
        override fun getCount(): Int = entries.size
        override fun getItem(position: Int): Any = entries[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val tile = (convertView as? Tile) ?: Tile(this@GalleryActivity)
            val entry = entries[position]
            val hex = entry.hex
            val thumb = cache.get(hex)
            val kind = kinds[hex]
            when {
                thumb != null -> {
                    tile.image.setImageBitmap(thumb)
                    tile.label.visibility = View.GONE
                }
                kind != null -> {
                    tile.image.setImageDrawable(null)
                    tile.label.text = kind
                    tile.label.visibility = View.VISIBLE
                }
                else -> {
                    tile.image.setImageDrawable(null)
                    tile.label.text = "loading"
                    tile.label.visibility = View.VISIBLE
                    requestLoad(entry)
                }
            }
            return tile
        }
    }
}
