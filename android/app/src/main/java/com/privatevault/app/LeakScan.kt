package com.privatevault.app

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.MediaStore
import java.io.File
import java.io.RandomAccessFile

// Read-only scan of every app-visible storage location for plaintext media.
// Findings are JPEG start markers and MP4 ftyp boxes in the first 64 KiB of
// any file, plus MediaStore rows owned by this app. Encrypted objects are
// scanned like any other file; a three-byte chance match inside ciphertext is
// reported with its offset so it can be judged.
object LeakScan {

    data class Entry(val path: String, val size: Long, val mtime: Long)
    data class Finding(val kind: String, val path: String, val offset: Long)

    class Report(
        val entries: List<Entry>,
        val findings: List<Finding>,
        val mediaStoreRows: Map<String, Int>,
        val notes: List<String>
    ) {
        val findingCount: Int get() = findings.size + mediaStoreRows.values.sum()

        fun lines(): List<String> {
            val out = ArrayList<String>()
            out.add("leak_scan: ${findingCount} findings, ${entries.size} files scanned")
            for (f in findings) out.add("  ${f.kind} at ${f.offset} in ${f.path}")
            for ((k, v) in mediaStoreRows) out.add("  MediaStore $k rows owned by app: $v")
            for (n in notes) out.add("  note: $n")
            return out
        }
    }

    private const val SCAN_BYTES = 64 * 1024
    private val JPEG_SOI = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
    private val FTYP = "ftyp".toByteArray(Charsets.US_ASCII)

    // The app-visible roots. The external ones are built as paths and walked
    // only when present: the Context getters (getExternalFilesDirs,
    // getExternalMediaDirs, and the like) create the directories they return,
    // and an empty directory under Android/media shows up as a MediaStore row
    // after the next media scan. Volumes come from StorageManager, which
    // creates nothing.
    fun roots(context: Context): List<File> {
        val roots = ArrayList<File>()
        roots.add(context.dataDir)
        val pkg = context.packageName
        @Suppress("DEPRECATION")
        val externals = ArrayList<File>()
        externals.add(Environment.getExternalStorageDirectory())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val sm = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
            for (v in sm.storageVolumes) v.directory?.let { externals.add(it) }
        }
        for (ext in externals.distinct()) {
            for (sub in listOf("Android/data/$pkg", "Android/media/$pkg", "Android/obb/$pkg")) {
                val d = File(ext, sub)
                if (d.exists()) roots.add(d)
            }
        }
        return roots.distinct()
    }

    fun run(context: Context): Report {
        val entries = ArrayList<Entry>()
        val findings = ArrayList<Finding>()
        val notes = ArrayList<String>()
        for (root in roots(context)) {
            if (!root.exists()) {
                notes.add("root missing: ${root.path}")
                continue
            }
            walk(root, entries, findings, notes)
        }
        return Report(entries, findings, mediaStoreRows(context, notes), notes)
    }

    private fun walk(dir: File, entries: MutableList<Entry>, findings: MutableList<Finding>, notes: MutableList<String>) {
        val children = dir.listFiles()
        if (children == null) {
            notes.add("unreadable directory: ${dir.path}")
            return
        }
        for (f in children.sortedBy { it.name }) {
            if (f.isDirectory) {
                walk(f, entries, findings, notes)
            } else if (f.isFile) {
                entries.add(Entry(f.path, f.length(), f.lastModified()))
                scanFile(f, findings, notes)
            }
        }
    }

    private fun scanFile(f: File, findings: MutableList<Finding>, notes: MutableList<String>) {
        val head: ByteArray
        try {
            RandomAccessFile(f, "r").use { raf ->
                val n = minOf(raf.length(), SCAN_BYTES.toLong()).toInt()
                head = ByteArray(n)
                raf.readFully(head)
            }
        } catch (e: Exception) {
            notes.add("unreadable file: ${f.path} (${e.javaClass.simpleName})")
            return
        }
        if (startsWith(head, 0, JPEG_SOI)) findings.add(Finding("jpeg_soi_at_start", f.path, 0))
        var i = indexOf(head, JPEG_SOI, 1)
        while (i >= 0) {
            findings.add(Finding("jpeg_soi", f.path, i.toLong()))
            i = indexOf(head, JPEG_SOI, i + 1)
        }
        if (head.size >= 8 && startsWith(head, 4, FTYP)) findings.add(Finding("mp4_ftyp_at_4", f.path, 4))
        var j = indexOf(head, FTYP, 0)
        while (j >= 0) {
            if (j != 4) findings.add(Finding("mp4_ftyp", f.path, j.toLong()))
            j = indexOf(head, FTYP, j + 1)
        }
    }

    private fun startsWith(buf: ByteArray, at: Int, pat: ByteArray): Boolean {
        if (at + pat.size > buf.size) return false
        for (k in pat.indices) if (buf[at + k] != pat[k]) return false
        return true
    }

    private fun indexOf(buf: ByteArray, pat: ByteArray, from: Int): Int {
        var i = from
        while (i + pat.size <= buf.size) {
            if (startsWith(buf, i, pat)) return i
            i++
        }
        return -1
    }

    // Rows in the shared media collections whose owner is this app. An app can
    // always read its own rows, so no permission is needed. The owner column
    // exists from API 29.
    private fun mediaStoreRows(context: Context, notes: MutableList<String>): Map<String, Int> {
        val out = LinkedHashMap<String, Int>()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            notes.add("MediaStore owner column needs API 29; check not available")
            return out
        }
        val collections = linkedMapOf(
            "images" to MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            "video" to MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            "files" to MediaStore.Files.getContentUri("external")
        )
        for ((name, uri) in collections) {
            try {
                context.contentResolver.query(
                    uri,
                    arrayOf(
                        MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
                        MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.MIME_TYPE,
                        MediaStore.MediaColumns.SIZE
                    ),
                    "${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ?", arrayOf(context.packageName), null
                ).use { c ->
                    // Directory rows (no MIME type) are named but not counted:
                    // the test runner makes Android/media/<package>/additional_test_output
                    // for every instrumented run, and the media scanner lists
                    // it whenever it gets there first. A media file has a type.
                    var files = 0
                    while (c != null && c.moveToNext()) {
                        val mime = c.getString(3)
                        if (mime != null) files++
                        notes.add(
                            "MediaStore $name ${if (mime == null) "directory" else "row"}: ${c.getString(1)} in ${c.getString(2)} " +
                                "mime=$mime size=${c.getLong(4)}"
                        )
                    }
                    out[name] = files
                }
            } catch (e: Exception) {
                notes.add("MediaStore $name query failed: ${e.javaClass.simpleName}")
            }
        }
        return out
    }

    // Files present or changed in `after` but not in `before`.
    fun newEntries(before: Report, after: Report): List<Entry> {
        val known = before.entries.associateBy { it.path }
        return after.entries.filter { e ->
            val b = known[e.path]
            b == null || b.size != e.size || b.mtime != e.mtime
        }
    }
}
