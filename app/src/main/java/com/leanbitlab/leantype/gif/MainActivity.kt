package com.leanbitlab.leantype.gif

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
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.request.CachePolicy
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.leanbitlab.leantype.gif.storage.LocalMediaManager
import com.leanbitlab.leantype.gif.storage.ThumbRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {

    private val scope = CoroutineScope(Dispatchers.Main)

    private lateinit var toolbar: MaterialToolbar
    private lateinit var tvStatsSummary: TextView
    private lateinit var tvGalleryTitle: TextView
    private lateinit var btnImportFiles: View
    private lateinit var btnImportZip: View
    private lateinit var btnClearAll: MaterialButton
    private lateinit var layoutEmptyState: View
    private lateinit var recyclerViewMedia: RecyclerView

    private val mediaAdapter = LocalMediaAdapter(
        onItemClick = { item, file -> showItemDetailsDialog(item, file) }
    )

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
        tvStatsSummary = findViewById(R.id.tvStatsSummary)
        tvGalleryTitle = findViewById(R.id.tvGalleryTitle)
        btnImportFiles = findViewById(R.id.btnImportFiles)
        btnImportZip = findViewById(R.id.btnImportZip)
        btnClearAll = findViewById(R.id.btnClearAll)
        layoutEmptyState = findViewById(R.id.layoutEmptyState)
        recyclerViewMedia = findViewById(R.id.recyclerViewMedia)

        toolbar.setNavigationOnClickListener {
            finish()
        }

        btnImportFiles.setOnClickListener {
            showImportCategoryDialog()
        }

        btnImportZip.setOnClickListener {
            pickZipLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "*/*"))
        }

        btnClearAll.setOnClickListener {
            showClearAllDialog()
        }

        recyclerViewMedia.layoutManager = GridLayoutManager(this, 3)
        recyclerViewMedia.adapter = mediaAdapter

        refreshData()
        handleSendIntent(intent)
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
        scope.launch {
            val registry = ThumbRegistry(applicationContext)
            val items = withContext(Dispatchers.IO) {
                try {
                    File(cacheDir, "image_cache").deleteRecursively()
                    registry.trimIfNeeded()
                } catch (_: Exception) {}
                LocalMediaManager.getItems(applicationContext, registry)
            }
            val totalBytes = withContext(Dispatchers.IO) {
                LocalMediaManager.getTotalBytes(applicationContext)
            }

            val formattedSize = Formatter.formatFileSize(applicationContext, totalBytes)
            if (items.isEmpty()) {
                tvStatsSummary.text = "No offline media imported"
                tvGalleryTitle.text = "Imported Media (0)"
                btnClearAll.visibility = View.GONE
                layoutEmptyState.visibility = View.VISIBLE
                recyclerViewMedia.visibility = View.GONE
            } else {
                tvStatsSummary.text = "${items.size} offline items - $formattedSize"
                tvGalleryTitle.text = "Imported Media (${items.size})"
                btnClearAll.visibility = View.VISIBLE
                layoutEmptyState.visibility = View.GONE
                recyclerViewMedia.visibility = View.VISIBLE
            }

            mediaAdapter.submitList(items)
        }
    }

    private fun showClearAllDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Clear Local Media")
            .setMessage("Delete all imported offline GIFs and stickers? They will no longer appear in the keyboard.")
            .setPositiveButton("Clear All") { _, _ ->
                LocalMediaManager.clearAll(applicationContext)
                Toast.makeText(this, "Local media cleared", Toast.LENGTH_SHORT).show()
                refreshData()
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
                LocalMediaManager.deleteFile(file)
                Toast.makeText(this, "Item deleted", Toast.LENGTH_SHORT).show()
                refreshData()
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
                val newFile = LocalMediaManager.changeCategory(file, kinds[which])
                if (newFile != null) {
                    Toast.makeText(this, "Changed to ${categories[which]}", Toast.LENGTH_SHORT).show()
                    refreshData()
                } else {
                    Toast.makeText(this, "Failed to change category", Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    class LocalMediaAdapter(
        private val onItemClick: (GifItem, File) -> Unit
    ) : RecyclerView.Adapter<LocalMediaAdapter.MediaViewHolder>() {

        private val items = mutableListOf<GifItem>()

        fun submitList(newItems: List<GifItem>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MediaViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_local_media, parent, false)
            return MediaViewHolder(view)
        }

        override fun onBindViewHolder(holder: MediaViewHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size

        inner class MediaViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val ivThumbnail: ImageView = itemView.findViewById(R.id.ivThumbnail)
            private val tvKindBadge: TextView = itemView.findViewById(R.id.tvKindBadge)

            fun bind(item: GifItem) {
                val file = File(item.extra)
                ivThumbnail.load(file) {
                    crossfade(true)
                    diskCachePolicy(CachePolicy.DISABLED)
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
        }
    }
}
