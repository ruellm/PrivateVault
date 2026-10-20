package com.privatevault.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
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

// The gallery (docs/PHASE3B_DESIGN.md Section 3, docs/PHASE3D_DESIGN.md
// Section 4.4): a grid of tiles, newest first, listed without decrypting.
// Every object is then told apart from its item header (one chunk) on the
// viewer thread: photos and video indexes get thumbnails, segments drop out
// of the list, the rest get a labelled grey tile. Every thumbnail is erased
// when the activity stops or the vault locks. Not exported; routes to the
// lock screen when not unlocked.
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
    // Every object, and the ones shown (segments are left out once known).
    private var entries: List<Vault.ObjectEntry> = emptyList()
    private var visible: List<Vault.ObjectEntry> = emptyList()
    private val kinds = HashMap<String, VideoItems.Kind>()
    // Thumbnail loads that ended without a bitmap, with the label to show.
    private val failed = HashMap<String, String>()
    private val pending = HashSet<String>()
    private var started = false

    // Thumbnails of this screen; emptied in onStop and on lock. Tests read it.
    val cache = ThumbCache()

    // Tests read these.
    val classified: Int get() = kinds.size
    val visibleCount: Int get() = visible.size
    fun kindOf(hex: String): VideoItems.Kind? = kinds[hex]
    fun thumbFailed(hex: String): String? = failed[hex]
    // The index whose thumbnail is being decoded right now, and the detail
    // line of the last video thumbnail load.
    @Volatile var thumbLoading: String? = null
        private set
    @Volatile var lastThumbDetail = ""
        private set

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

    // Rebuilds the list from the directory, then queues one classification
    // per object, newest first. Nothing is decrypted here.
    fun refresh() {
        val v = vaults.vault
        entries = v?.listObjects() ?: emptyList()
        visible = entries
        kinds.clear()
        failed.clear()
        pending.clear()
        adapter.notifyDataSetChanged()
        status.text = "${entries.size} objects"
        Log.i(TAG, "gallery: ${entries.size} objects listed")
        for (e in entries) requestClassify(e)
    }

    private fun requestClassify(entry: Vault.ObjectEntry) {
        val hex = entry.hex
        if (!pending.add("kind:$hex")) return
        PhotoLoader.executor.execute {
            val v = vaults.vault
            val kind = if (v == null) VideoItems.Kind.Cancelled else VideoItems.classify(v, entry.id)
            main.post { onClassified(hex, kind) }
        }
    }

    private fun onClassified(hex: String, kind: VideoItems.Kind) {
        pending.remove("kind:$hex")
        if (!started || lock.state != LockState.UNLOCKED) return
        if (kind is VideoItems.Kind.Cancelled) return
        kinds[hex] = kind
        if (kind is VideoItems.Kind.Segment) visible = visible.filter { it.hex != hex }
        val extra = if (kind is VideoItems.Kind.Video) " duration=${VideoItems.durationLabel(kind.index.header.totalDurationUs)} segments=${kind.index.segments.size}" else ""
        Log.i(TAG, "gallery: $hex ${kind.label()}$extra")
        if (kinds.size == entries.size) {
            status.text = "${visible.size} items"
            Log.i(TAG, "gallery: classified ${entries.size} objects, ${visible.size} shown")
        }
        adapter.notifyDataSetChanged()
    }

    private fun openViewer(position: Int) {
        val entry = visible.getOrNull(position) ?: return
        when (val kind = kinds[entry.hex]) {
            is VideoItems.Kind.Photo -> if (cache.get(entry.hex) != null) {
                startActivity(Intent(this, ViewerActivity::class.java).putExtra(ViewerActivity.EXTRA_ID, entry.hex))
            }
            is VideoItems.Kind.Video -> openVideo(entry.hex)
            // A damaged clip opens the video viewer; a damaged object whose
            // header could not be read opens the photo viewer, whose error
            // state offers Delete as well.
            is VideoItems.Kind.Damaged -> if (kind.video) openVideo(entry.hex)
            else startActivity(Intent(this, ViewerActivity::class.java).putExtra(ViewerActivity.EXTRA_ID, entry.hex))
            else -> {}
        }
    }

    private fun openVideo(hex: String) {
        startActivity(Intent(this, VideoActivity::class.java).putExtra(VideoActivity.EXTRA_ID, hex))
    }

    private fun requestThumb(entry: Vault.ObjectEntry, kind: VideoItems.Kind) {
        val hex = entry.hex
        if (!pending.add("thumb:$hex")) return
        PhotoLoader.executor.execute {
            val v = vaults.vault
            var bitmap: Bitmap? = null
            var label = "cancelled"
            if (v != null) {
                when (kind) {
                    is VideoItems.Kind.Photo -> {
                        val r = PhotoLoader.load(v, entry.id, PhotoLoader.THUMB_PX, PhotoLoader.THUMB_PX)
                        if (r is PhotoLoader.Loaded.Photo) bitmap = PhotoLoader.turned(r.bitmap, r.turnDegrees)
                        label = r.label()
                    }
                    is VideoItems.Kind.Video -> {
                        thumbLoading = hex
                        val t = VideoItems.thumbnail(v, kind.index)
                        thumbLoading = null
                        lastThumbDetail = t.detail
                        bitmap = t.bitmap
                        label = if (bitmap != null) "video" else "video (${t.detail})"
                        Log.i(TAG, "gallery: thumbnail $hex ${if (bitmap != null) "${bitmap.width}x${bitmap.height}" else "none"} ${t.detail}")
                    }
                    else -> {}
                }
            }
            main.post { onThumb(hex, bitmap, label) }
        }
    }

    private fun onThumb(hex: String, bitmap: Bitmap?, label: String) {
        pending.remove("thumb:$hex")
        if (!started || lock.state != LockState.UNLOCKED) {
            PhotoLoader.dispose(bitmap)
            return
        }
        if (bitmap != null) cache.put(hex, bitmap) else if (label != "cancelled") failed[hex] = label
        Log.i(TAG, "gallery: $hex $label")
        adapter.notifyDataSetChanged()
    }

    // A square cell: a thumbnail, with a play mark and the duration over a
    // video, or a grey square with a label.
    private class Tile(context: Context) : FrameLayout(context) {
        val image = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Color.DKGRAY)
        }
        val label = TextView(context).apply {
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
        }
        val mark = TextView(context).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            setTextColor(Color.WHITE)
            setBackgroundColor(0x80000000.toInt())
            setPadding(8, 2, 8, 2)
            visibility = GONE
        }

        init {
            addView(image, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            addView(label, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            addView(mark, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END))
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(widthMeasureSpec, widthMeasureSpec)
        }
    }

    private inner class TileAdapter : BaseAdapter() {
        override fun getCount(): Int = visible.size
        override fun getItem(position: Int): Any = visible[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val tile = (convertView as? Tile) ?: Tile(this@GalleryActivity)
            val entry = visible[position]
            val hex = entry.hex
            val kind = kinds[hex]
            val thumb = cache.get(hex)
            tile.mark.visibility = View.GONE
            if (kind == null) {
                showLabel(tile, "loading")
            } else if (kind is VideoItems.Kind.Photo || kind is VideoItems.Kind.Video) {
                val fail = failed[hex]
                if (thumb != null) {
                    tile.image.setImageBitmap(thumb)
                    tile.label.visibility = View.GONE
                } else if (fail != null) {
                    showLabel(tile, fail)
                } else {
                    showLabel(tile, "loading")
                    requestThumb(entry, kind)
                }
                if (kind is VideoItems.Kind.Video) {
                    tile.mark.text = "▶ ${VideoItems.durationLabel(kind.index.header.totalDurationUs)}"
                    tile.mark.visibility = View.VISIBLE
                }
            } else {
                showLabel(tile, kind.label())
            }
            return tile
        }

        private fun showLabel(tile: Tile, text: String) {
            tile.image.setImageDrawable(null)
            tile.label.text = text
            tile.label.visibility = View.VISIBLE
        }
    }
}
