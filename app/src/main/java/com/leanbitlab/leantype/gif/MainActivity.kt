package com.leanbitlab.leantype.gif

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.ImageLoader
import coil.disk.DiskCache
import coil.dispose
import coil.load
import coil.memory.MemoryCache
import coil.size.Precision
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.leanbitlab.leantype.gif.storage.LocalMediaManager
import com.leanbitlab.leantype.gif.storage.ThumbRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var refreshJob: Job? = null

    private lateinit var toolbar: MaterialToolbar
    private lateinit var recyclerViewMedia: RecyclerView
    private lateinit var headerAdapter: HeaderAdapter
    private lateinit var mediaAdapter: LocalMediaAdapter

    private var pendingImportKind: Int? = null

    private val pickFilesLauncher = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNullOrEmpty()) return@registerForActivityResult
        val targetKind = pendingImportKind
        scope.launch {
            val imported = withContext(Dispatchers.IO) {
                LocalMediaManager.importFiles(applicationContext, uris, targetKind)
            }
            Toast.makeText(this@MainActivity, "Imported $imported media files", Toast.LENGTH_SHORT).show()
            refreshData()
        }
    }

    private val pickZipLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        scope.launch {
            val imported = withContext(Dispatchers.IO) {
                LocalMediaManager.importZip(applicationContext, uri)
            }
            Toast.makeText(this@MainActivity, "Imported $imported items from pack", Toast.LENGTH_SHORT).show()
            refreshData()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Ensure status bar and navigation bar match Material 3 surface in both light and dark themes
        val surfaceColor = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface, Color.BLACK)
        window.statusBarColor = surfaceColor
        window.navigationBarColor = surfaceColor

        val isNight = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !isNight
            isAppearanceLightNavigationBars = !isNight
        }

        toolbar = findViewById(R.id.toolbar)
        recyclerViewMedia = findViewById(R.id.recyclerViewMedia)

        toolbar.setNavigationOnClickListener {
            finish()
        }

        headerAdapter = HeaderAdapter(
            onImportFilesClick = { showImportCategoryDialog() },
            onImportZipClick = {
                pickZipLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "*/*"))
            },
            onClearAllClick = { showClearAllDialog() }
        )

        mediaAdapter = LocalMediaAdapter(
            thumbLoader = getThumbLoader(applicationContext),
            onItemClick = { item, file -> showItemDetailsDialog(item, file) }
        )

        val gridLayoutManager = GridLayoutManager(this, 3).apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int = if (position == 0) 3 else 1
            }
        }
        recyclerViewMedia.layoutManager = gridLayoutManager
        recyclerViewMedia.setItemViewCacheSize(18)
        recyclerViewMedia.adapter = ConcatAdapter(headerAdapter, mediaAdapter)

        if (savedInstanceState == null) {
            handleSendIntent(intent)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleSendIntent(intent)
    }

    private fun handleSendIntent(intent: Intent?) {
        if (intent == null) return
        val action = intent.action ?: return
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return

        val type = intent.type.orEmpty().lowercase()
        val uris = mutableListOf<Uri>()

        intent.clipData?.let { clipData ->
            for (i in 0 until clipData.itemCount) {
                clipData.getItemAt(i).uri?.let { uris.add(it) }
            }
        }

        if (uris.isEmpty()) {
            if (action == Intent.ACTION_SEND) {
                val streamUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                } ?: intent.data
                streamUri?.let { uris.add(it) }
            } else if (action == Intent.ACTION_SEND_MULTIPLE) {
                val streamUris = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                }
                if (!streamUris.isNullOrEmpty()) {
                    uris.addAll(streamUris)
                }
            }
        }

        // Prevent re-importing the same share intent on configuration changes
        intent.action = null

        if (uris.isEmpty()) return

        scope.launch {
            val imported = withContext(Dispatchers.IO) {
                var total = 0
                val fileUris = mutableListOf<Uri>()
                for (uri in uris) {
                    if (isZipUri(uri, type)) {
                        total += LocalMediaManager.importZip(applicationContext, uri)
                    } else {
                        fileUris.add(uri)
                    }
                }
                if (fileUris.isNotEmpty()) {
                    total += LocalMediaManager.importFiles(applicationContext, fileUris)
                }
                total
            }

            if (imported > 0) {
                Toast.makeText(this@MainActivity, "Imported $imported items", Toast.LENGTH_SHORT).show()
                refreshData()
            }
        }
    }

    private fun isZipUri(uri: Uri, mimeType: String): Boolean {
        if (mimeType.contains("zip") || mimeType == "application/octet-stream") {
            val name = LocalMediaManager.queryDisplayName(this, uri).lowercase()
            if (name.endsWith(".zip") || name.endsWith(".wastickers")) return true
            if (mimeType.contains("zip")) return true
        }
        val name = LocalMediaManager.queryDisplayName(this, uri).lowercase()
        return name.endsWith(".zip") || name.endsWith(".wastickers")
    }

    override fun onResume() {
        super.onResume()
        refreshData()
    }

    private fun refreshData() {
        refreshJob?.cancel()
        refreshJob = scope.launch {
            val (items, totalBytes) = withContext(Dispatchers.IO) {
                val registry = ThumbRegistry(applicationContext)
                val loaded = LocalMediaManager.getItems(applicationContext, registry)
                val bytes = LocalMediaManager.getTotalBytes(applicationContext)
                loaded to bytes
            }

            headerAdapter.updateStats(items.size, totalBytes)
            mediaAdapter.submitList(items)
        }
    }

    private fun showClearAllDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Clear Local Media")
            .setMessage("Delete all imported offline GIFs and stickers? They will no longer appear in the keyboard.")
            .setPositiveButton("Clear All") { _, _ ->
                scope.launch {
                    withContext(Dispatchers.IO) {
                        LocalMediaManager.clearAll(applicationContext)
                        getThumbLoader(applicationContext).diskCache?.clear()
                        getThumbLoader(applicationContext).memoryCache?.clear()
                    }
                    Toast.makeText(this@MainActivity, "Local media cleared", Toast.LENGTH_SHORT).show()
                    refreshData()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showImportCategoryDialog() {
        val categories = arrayOf("Auto-detect", "Sticker", "GIF", "Meme", "Emoji")
        val kinds = arrayOf(null, Contract.KIND_STICKER, Contract.KIND_GIF, Contract.KIND_MEME, Contract.KIND_ANIMATED_EMOJI)
        MaterialAlertDialogBuilder(this)
            .setTitle("Import As Category")
            .setItems(categories) { _, which ->
                pendingImportKind = kinds[which]
                pickFilesLauncher.launch(arrayOf("image/gif", "image/webp", "image/png", "image/jpeg"))
            }
            .show()
    }

    private fun showItemDetailsDialog(item: GifItem, file: File) {
        val kindLabel = when (item.kind) {
            Contract.KIND_STICKER -> "Sticker"
            Contract.KIND_MEME -> "Meme"
            Contract.KIND_ANIMATED_EMOJI -> "Emoji"
            else -> "GIF"
        }
        val sizeStr = Formatter.formatFileSize(applicationContext, file.length())

        MaterialAlertDialogBuilder(this)
            .setTitle(file.name)
            .setMessage("Type: $kindLabel\nSize: $sizeStr\nPath: ${file.name}")
            .setPositiveButton("Close", null)
            .setNeutralButton("Change Category") { _, _ ->
                showCategorySelectDialog(file)
            }
            .setNegativeButton("Delete") { _, _ ->
                scope.launch {
                    withContext(Dispatchers.IO) {
                        LocalMediaManager.deleteFile(file)
                    }
                    Toast.makeText(this@MainActivity, "Item deleted", Toast.LENGTH_SHORT).show()
                    refreshData()
                }
            }
            .show()
    }

    private fun showCategorySelectDialog(file: File) {
        val categories = arrayOf("Sticker", "GIF", "Meme", "Emoji")
        val kinds = intArrayOf(
            Contract.KIND_STICKER,
            Contract.KIND_GIF,
            Contract.KIND_MEME,
            Contract.KIND_ANIMATED_EMOJI
        )
        MaterialAlertDialogBuilder(this)
            .setTitle("Select Category")
            .setItems(categories) { _, which ->
                scope.launch {
                    val newFile = withContext(Dispatchers.IO) {
                        LocalMediaManager.changeCategory(file, kinds[which])
                    }
                    if (newFile != null) {
                        Toast.makeText(this@MainActivity, "Changed to ${categories[which]}", Toast.LENGTH_SHORT).show()
                        refreshData()
                    } else {
                        Toast.makeText(this@MainActivity, "Failed to change category", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .show()
    }

    private class HeaderAdapter(
        private val onImportFilesClick: () -> Unit,
        private val onImportZipClick: () -> Unit,
        private val onClearAllClick: () -> Unit
    ) : RecyclerView.Adapter<HeaderAdapter.HeaderViewHolder>() {

        private var itemCountValue: Int = 0
        private var totalBytesValue: Long = 0L
        private var boundHolder: HeaderViewHolder? = null

        fun updateStats(count: Int, totalBytes: Long) {
            itemCountValue = count
            totalBytesValue = totalBytes
            boundHolder?.bind(count, totalBytes)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HeaderViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_gallery_header, parent, false)
            return HeaderViewHolder(view)
        }

        override fun onBindViewHolder(holder: HeaderViewHolder, position: Int) {
            boundHolder = holder
            holder.bind(itemCountValue, totalBytesValue)
        }

        override fun onViewRecycled(holder: HeaderViewHolder) {
            super.onViewRecycled(holder)
            if (boundHolder === holder) boundHolder = null
        }

        override fun getItemCount(): Int = 1

        inner class HeaderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val tvStatsSummary: TextView = itemView.findViewById(R.id.tvStatsSummary)
            private val tvGalleryTitle: TextView = itemView.findViewById(R.id.tvGalleryTitle)
            private val btnImportFiles: View = itemView.findViewById(R.id.btnImportFiles)
            private val btnImportZip: View = itemView.findViewById(R.id.btnImportZip)
            private val btnClearAll: MaterialButton = itemView.findViewById(R.id.btnClearAll)
            private val layoutEmptyState: View = itemView.findViewById(R.id.layoutEmptyState)

            init {
                btnImportFiles.setOnClickListener { onImportFilesClick() }
                btnImportZip.setOnClickListener { onImportZipClick() }
                btnClearAll.setOnClickListener { onClearAllClick() }
            }

            fun bind(count: Int, totalBytes: Long) {
                val ctx = itemView.context.applicationContext
                val formattedSize = Formatter.formatFileSize(ctx, totalBytes)
                if (count == 0) {
                    tvStatsSummary.text = "No offline media imported"
                    tvGalleryTitle.text = "Imported Media (0)"
                    btnClearAll.visibility = View.GONE
                    layoutEmptyState.visibility = View.VISIBLE
                } else {
                    tvStatsSummary.text = "$count offline items - $formattedSize"
                    tvGalleryTitle.text = "Imported Media ($count)"
                    btnClearAll.visibility = View.VISIBLE
                    layoutEmptyState.visibility = View.GONE
                }
            }
        }
    }

    private class DiffCallback : DiffUtil.ItemCallback<GifItem>() {
        override fun areItemsTheSame(oldItem: GifItem, newItem: GifItem): Boolean =
            oldItem.extra == newItem.extra && oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: GifItem, newItem: GifItem): Boolean =
            oldItem == newItem
    }

    class LocalMediaAdapter(
        private val thumbLoader: ImageLoader,
        private val onItemClick: (GifItem, File) -> Unit
    ) : ListAdapter<GifItem, LocalMediaAdapter.MediaViewHolder>(DiffCallback()) {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MediaViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_local_media, parent, false)
            return MediaViewHolder(view)
        }

        override fun onBindViewHolder(holder: MediaViewHolder, position: Int) {
            holder.bind(getItem(position))
        }

        override fun onViewRecycled(holder: MediaViewHolder) {
            super.onViewRecycled(holder)
            holder.unbind()
        }

        inner class MediaViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val ivThumbnail: ImageView = itemView.findViewById(R.id.ivThumbnail)
            private val tvKindBadge: TextView = itemView.findViewById(R.id.tvKindBadge)

            fun bind(item: GifItem) {
                val file = File(item.extra)
                ivThumbnail.load(file, thumbLoader) {
                    size(256, 256)
                    precision(Precision.INEXACT)
                }

                tvKindBadge.text = when (item.kind) {
                    Contract.KIND_STICKER -> "STICKER"
                    Contract.KIND_MEME -> "MEME"
                    Contract.KIND_ANIMATED_EMOJI -> "EMOJI"
                    else -> "GIF"
                }

                itemView.setOnClickListener {
                    onItemClick(item, file)
                }
            }

            fun unbind() {
                ivThumbnail.dispose()
                ivThumbnail.setImageDrawable(null)
            }
        }
    }

    companion object {
        @Volatile
        private var sharedThumbLoader: ImageLoader? = null

        private fun getThumbLoader(context: Context): ImageLoader = sharedThumbLoader ?: synchronized(this) {
            val appContext = context.applicationContext
            sharedThumbLoader ?: ImageLoader.Builder(appContext)
                .memoryCache {
                    MemoryCache.Builder(appContext)
                        .maxSizePercent(0.20)
                        .build()
                }
                .diskCache {
                    DiskCache.Builder()
                        .directory(File(appContext.cacheDir, "thumb_disk_cache"))
                        .maxSizeBytes(40L * 1024 * 1024)
                        .build()
                }
                .crossfade(false)
                .build()
                .also { sharedThumbLoader = it }
        }
    }
}
