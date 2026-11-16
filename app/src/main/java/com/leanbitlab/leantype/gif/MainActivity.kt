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
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
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
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
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
    private lateinit var categoryAdapter: CategoryAdapter
    private lateinit var groupHeaderAdapter: GroupHeaderAdapter
    private lateinit var homeAdapter: ConcatAdapter
    private lateinit var groupAdapter: ConcatAdapter

    private var allItems: List<GifItem> = emptyList()
    // null shows the category overview, otherwise the full grid of that kind
    private var openKind: Int? = null
    // null is every pack of the open kind, NO_PACK is the files imported without a pack
    private var openPack: String? = null

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (selected.isNotEmpty()) clearSelection() else closeCategory()
        }
    }

    private var pendingImportKind: Int? = null
    private var pendingImportPack: String? = null

    // file paths of the items selected in the open category
    private val selected = LinkedHashSet<String>()

    private val pickFilesLauncher = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNullOrEmpty()) return@registerForActivityResult
        val targetKind = pendingImportKind
        scope.launch {
            val imported = withContext(Dispatchers.IO) {
                LocalMediaManager.importFiles(applicationContext, uris, targetKind, pendingImportPack)
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
        openKind = savedInstanceState?.getInt(STATE_KIND, -1)?.takeIf { it >= 0 }
        openPack = savedInstanceState?.getString(STATE_PACK)
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
            when {
                selected.isNotEmpty() -> clearSelection()
                openKind != null -> closeCategory()
                else -> finish()
            }
        }
        toolbar.setOnMenuItemClickListener { onSelectionAction(it.itemId) }
        onBackPressedDispatcher.addCallback(this, backCallback)

        headerAdapter = HeaderAdapter(
            onImportFilesClick = { showImportCategoryDialog() },
            onImportZipClick = {
                pickZipLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "*/*"))
            },
            onClearAllClick = { showClearAllDialog() }
        )

        mediaAdapter = LocalMediaAdapter(
            thumbLoader = getThumbLoader(applicationContext),
            showKindBadge = false,
            onItemLongClick = { item -> toggleSelection(item) },
            onItemClick = { item, file ->
                if (selected.isNotEmpty()) toggleSelection(item) else showItemDetailsDialog(item, file)
            }
        )
        categoryAdapter = CategoryAdapter(getThumbLoader(applicationContext)) { kind -> openCategory(kind) }
        groupHeaderAdapter = GroupHeaderAdapter(
            onPackSelected = { pack ->
                openPack = pack
                render()
                recyclerViewMedia.scrollToPosition(0)
            },
            onDeleteClick = { showDeleteGroupDialog() }
        )
        homeAdapter = ConcatAdapter(headerAdapter, categoryAdapter)
        groupAdapter = ConcatAdapter(groupHeaderAdapter, mediaAdapter)

        // 6 columns: header 6, category tile 3 (two per row), media 2 (three per row)
        val gridLayoutManager = GridLayoutManager(this, 6).apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int = when {
                    position == 0 -> 6
                    openKind == null -> 3
                    else -> 2
                }
            }
        }
        recyclerViewMedia.layoutManager = gridLayoutManager
        recyclerViewMedia.setItemViewCacheSize(18)
        recyclerViewMedia.adapter = homeAdapter

        if (savedInstanceState == null) {
            handleSendIntent(intent)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_KIND, openKind ?: -1)
        outState.putString(STATE_PACK, openPack)
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
            allItems = items
            render()
        }
    }

    private fun packOf(item: GifItem): String? = item.extra?.let { LocalMediaManager.getPackName(File(it)) }

    private fun kindLabel(kind: Int): String = when (kind) {
        Contract.KIND_STICKER -> "Stickers"
        Contract.KIND_MEME -> "Memes"
        Contract.KIND_ANIMATED_EMOJI -> "Emojis"
        else -> "GIFs"
    }

    private fun countLabel(count: Int, one: String, many: String): String = "$count ${if (count == 1) one else many}"

    private fun openCategory(kind: Int) {
        openKind = kind
        openPack = null
        render()
        recyclerViewMedia.scrollToPosition(0)
    }

    private fun closeCategory() {
        openKind = null
        openPack = null
        selected.clear()
        render()
    }

    private fun shownItems(): List<GifItem> {
        val kind = openKind ?: return emptyList()
        val ofKind = allItems.filter { it.kind == kind }
        return when (openPack) {
            null -> ofKind
            NO_PACK -> ofKind.filter { packOf(it) == null }
            else -> ofKind.filter { packOf(it) == openPack }
        }
    }

    private fun showAdapter(adapter: RecyclerView.Adapter<*>) {
        if (recyclerViewMedia.adapter !== adapter) {
            recyclerViewMedia.adapter = adapter
            recyclerViewMedia.scrollToPosition(0)
        }
    }

    private fun render() {
        val existing = allItems.mapNotNull { it.extra }.toSet()
        selected.retainAll(existing)
        val kind = openKind
        // the last item of a category was deleted, go back to the overview
        if (kind != null && allItems.none { it.kind == kind }) {
            openKind = null
            openPack = null
        }
        val activeKind = openKind
        if (activeKind == null) selected.clear()
        if (activeKind == null) {
            toolbar.title = "Offline Media"
            val rows = listOf(Contract.KIND_STICKER, Contract.KIND_GIF, Contract.KIND_MEME, Contract.KIND_ANIMATED_EMOJI).mapNotNull { k ->
                val ofKind = allItems.filter { it.kind == k }
                if (ofKind.isEmpty()) return@mapNotNull null
                val cover = ofKind.first().extra?.let { File(it) }
                CategoryRow(k, ofKind.size, ofKind.mapNotNull { packOf(it) }.distinct().size, cover)
            }
            categoryAdapter.submitList(rows)
            showAdapter(homeAdapter)
            updateSelectionUi()
            return
        }

        val ofKind = allItems.filter { it.kind == activeKind }
        val packCounts = HashMap<String, Int>()
        var unpacked = 0
        for (item in ofKind) {
            val pack = packOf(item)
            if (pack == null) unpacked++ else packCounts[pack] = (packCounts[pack] ?: 0) + 1
        }
        val selected = openPack
        if (selected != null && selected != NO_PACK && selected !in packCounts) openPack = null
        if (selected == NO_PACK && unpacked == 0) openPack = null

        val chips = ArrayList<Pair<String?, String>>()
        if (packCounts.isNotEmpty()) {
            chips.add(null to "All (${ofKind.size})")
            for ((pack, n) in packCounts.entries.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.key })) chips.add(pack to "$pack ($n)")
            if (unpacked > 0) chips.add(NO_PACK to "No pack ($unpacked)")
        }
        val shown = shownItems()
        val inPack = openPack != null && openPack != NO_PACK
        groupHeaderAdapter.submit(
            GroupHeaderAdapter.Model(
                summary = countLabel(shown.size, "item", "items") + " - long press to select",
                deleteLabel = if (inPack) "Delete pack" else "Delete all",
                chips = chips,
                selected = openPack
            )
        )
        toolbar.title = kindLabel(activeKind)
        mediaAdapter.submitList(shown)
        showAdapter(groupAdapter)
        updateSelectionUi()
    }

    private fun toggleSelection(item: GifItem) {
        val path = item.extra ?: return
        if (!selected.remove(path)) selected.add(path)
        updateSelectionUi()
    }

    private fun clearSelection() {
        selected.clear()
        updateSelectionUi()
    }

    private fun updateSelectionUi() {
        val selecting = selected.isNotEmpty()
        backCallback.isEnabled = selecting || openKind != null
        mediaAdapter.setSelection(selected)
        if (selecting) {
            toolbar.title = "${selected.size} selected"
            toolbar.setNavigationIcon(R.drawable.ic_close)
            toolbar.navigationContentDescription = "Clear selection"
            if (toolbar.menu.size() == 0) toolbar.inflateMenu(R.menu.menu_selection)
            toolbar.menu.findItem(R.id.action_rename)?.isVisible = selected.size == 1
        } else {
            toolbar.title = openKind?.let { kindLabel(it) } ?: "Offline Media"
            toolbar.setNavigationIcon(R.drawable.ic_arrow_back)
            toolbar.navigationContentDescription = "Back"
            toolbar.menu.clear()
        }
    }

    private fun selectedFiles(): List<File> = selected.map { File(it) }.filter { it.exists() }

    private fun onSelectionAction(itemId: Int): Boolean {
        when (itemId) {
            R.id.action_select_all -> {
                selected.addAll(shownItems().mapNotNull { it.extra })
                updateSelectionUi()
            }
            R.id.action_move_pack -> {
                val files = selectedFiles()
                showPackPickerDialog("Move to Pack") { pack ->
                    organize({ "Moved $it items" }) { files.count { LocalMediaManager.setPack(it, pack) != null } }
                }
            }
            R.id.action_change_category -> showCategorySelectDialog(selectedFiles())
            R.id.action_rename -> selectedFiles().firstOrNull()?.let { file ->
                showTextInputDialog("Item Title", "Title", LocalMediaManager.getTitle(file), "Rename") { title ->
                    organize({ if (it > 0) "Renamed" else "Could not rename, the name is empty or already used" }) {
                        if (LocalMediaManager.renameTitle(file, title) != null) 1 else 0
                    }
                }
            }
            R.id.action_delete -> {
                val files = selectedFiles()
                MaterialAlertDialogBuilder(this)
                    .setTitle("Delete Selected?")
                    .setMessage("Delete ${files.size} items? They will no longer appear in the keyboard.")
                    .setPositiveButton("Delete") { _, _ ->
                        organize({ "Deleted $it items" }) { LocalMediaManager.deleteFiles(files) }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            else -> return false
        }
        return true
    }

    /** Runs a change on the selected files in the background, then clears the selection and reloads. */
    private fun organize(message: (Int) -> String, work: () -> Int) {
        scope.launch {
            val done = withContext(Dispatchers.IO) { work() }
            selected.clear()
            Toast.makeText(this@MainActivity, message(done), Toast.LENGTH_SHORT).show()
            refreshData()
        }
    }

    /** No pack, one of the existing packs, or a new one. */
    private fun showPackPickerDialog(title: String, onPicked: (String?) -> Unit) {
        val packs = allItems.mapNotNull { packOf(it) }.distinct().sortedWith(String.CASE_INSENSITIVE_ORDER)
        val labels = ArrayList<String>()
        labels.add("No pack")
        labels.addAll(packs)
        labels.add("New pack...")
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setItems(labels.toTypedArray()) { _, which ->
                when (which) {
                    0 -> onPicked(null)
                    labels.size - 1 -> showTextInputDialog("New Pack", "Pack name", "", "Create") { name -> onPicked(name) }
                    else -> onPicked(packs[which - 1])
                }
            }
            .show()
    }

    private fun showTextInputDialog(title: String, hint: String, initial: String, positive: String, onDone: (String) -> Unit) {
        val input = EditText(this).apply {
            setText(initial)
            setSelection(text.length)
            this.hint = hint
            isSingleLine = true
        }
        val pad = (24 * resources.displayMetrics.density).toInt()
        val container = FrameLayout(this).apply {
            setPadding(pad, pad / 3, pad, 0)
            addView(input, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setView(container)
            .setPositiveButton(positive) { _, _ ->
                val text = input.text.toString().trim()
                if (text.isNotEmpty()) onDone(text)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDeleteGroupDialog() {
        val targets = shownItems()
        if (targets.isEmpty()) return
        val kind = openKind ?: return
        val pack = openPack
        val what = when {
            pack == null -> "all ${targets.size} ${kindLabel(kind)}"
            pack == NO_PACK -> "${targets.size} ${kindLabel(kind)} without a pack"
            else -> "${targets.size} ${kindLabel(kind)} of the pack \"$pack\""
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete Items?")
            .setMessage("Delete $what? They will no longer appear in the keyboard.")
            .setPositiveButton("Delete") { _, _ ->
                scope.launch {
                    val deleted = withContext(Dispatchers.IO) {
                        LocalMediaManager.deleteFiles(targets.mapNotNull { it.extra?.let { path -> File(path) } })
                    }
                    Toast.makeText(this@MainActivity, "Deleted $deleted items", Toast.LENGTH_SHORT).show()
                    refreshData()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
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
                showPackPickerDialog("Add to Pack") { pack ->
                    pendingImportPack = pack
                    pickFilesLauncher.launch(arrayOf("image/gif", "image/webp", "image/png", "image/jpeg"))
                }
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
        val packLine = LocalMediaManager.getPackName(file)?.let { "\nPack: $it" }.orEmpty()

        MaterialAlertDialogBuilder(this)
            .setTitle(file.name)
            .setMessage("Type: $kindLabel$packLine\nSize: $sizeStr\nPath: ${file.name}")
            .setPositiveButton("Close", null)
            .setNeutralButton("Change Category") { _, _ ->
                showCategorySelectDialog(listOf(file))
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

    private fun showCategorySelectDialog(files: List<File>) {
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
                organize({ if (it > 0) "Changed $it items to ${categories[which]}" else "Failed to change category" }) {
                    files.count { LocalMediaManager.changeCategory(it, kinds[which]) != null }
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
                    tvGalleryTitle.text = "Library (0)"
                    btnClearAll.visibility = View.GONE
                    layoutEmptyState.visibility = View.VISIBLE
                } else {
                    tvStatsSummary.text = "$count offline items - $formattedSize"
                    tvGalleryTitle.text = "Library ($count)"
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
        private val showKindBadge: Boolean = true,
        private val onItemLongClick: (GifItem) -> Unit = {},
        private val onItemClick: (GifItem, File) -> Unit
    ) : ListAdapter<GifItem, LocalMediaAdapter.MediaViewHolder>(DiffCallback()) {

        private var selection: Set<String> = emptySet()

        fun setSelection(newSelection: Set<String>) {
            if (selection == newSelection) return
            selection = newSelection.toSet()
            if (itemCount > 0) notifyItemRangeChanged(0, itemCount, SELECTION_PAYLOAD)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MediaViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_local_media, parent, false)
            return MediaViewHolder(view)
        }

        override fun onBindViewHolder(holder: MediaViewHolder, position: Int) {
            holder.bind(getItem(position))
        }

        override fun onBindViewHolder(holder: MediaViewHolder, position: Int, payloads: MutableList<Any>) {
            if (payloads.isNotEmpty()) holder.bindSelection(getItem(position)) else super.onBindViewHolder(holder, position, payloads)
        }

        override fun onViewRecycled(holder: MediaViewHolder) {
            super.onViewRecycled(holder)
            holder.unbind()
        }

        inner class MediaViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val ivThumbnail: ImageView = itemView.findViewById(R.id.ivThumbnail)
            private val tvKindBadge: TextView = itemView.findViewById(R.id.tvKindBadge)
            private val ivSelected: ImageView = itemView.findViewById(R.id.ivSelected)

            fun bindSelection(item: GifItem) {
                val isSelected = item.extra != null && selection.contains(item.extra)
                ivSelected.visibility = if (isSelected) View.VISIBLE else View.GONE
                ivThumbnail.alpha = if (isSelected) 0.5f else 1f
            }

            fun bind(item: GifItem) {
                val file = File(item.extra)
                ivThumbnail.load(file, thumbLoader) {
                    size(256, 256)
                    precision(Precision.INEXACT)
                }

                tvKindBadge.visibility = if (showKindBadge) View.VISIBLE else View.GONE
                tvKindBadge.text = when (item.kind) {
                    Contract.KIND_STICKER -> "STICKER"
                    Contract.KIND_MEME -> "MEME"
                    Contract.KIND_ANIMATED_EMOJI -> "EMOJI"
                    else -> "GIF"
                }

                bindSelection(item)
                itemView.setOnClickListener {
                    onItemClick(item, file)
                }
                itemView.setOnLongClickListener {
                    onItemLongClick(item)
                    true
                }
            }

            fun unbind() {
                ivThumbnail.dispose()
                ivThumbnail.setImageDrawable(null)
            }
        }
    }

    private data class CategoryRow(val kind: Int, val count: Int, val packCount: Int, val cover: File?)

    private class CategoryDiff : DiffUtil.ItemCallback<CategoryRow>() {
        override fun areItemsTheSame(oldItem: CategoryRow, newItem: CategoryRow): Boolean = oldItem.kind == newItem.kind
        override fun areContentsTheSame(oldItem: CategoryRow, newItem: CategoryRow): Boolean = oldItem == newItem
    }

    /** One tile per kind that has items, tapping it opens the full grid of that kind. */
    private class CategoryAdapter(
        private val thumbLoader: ImageLoader,
        private val onClick: (Int) -> Unit
    ) : ListAdapter<CategoryRow, CategoryAdapter.CategoryViewHolder>(CategoryDiff()) {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CategoryViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_category, parent, false)
            return CategoryViewHolder(view)
        }

        override fun onBindViewHolder(holder: CategoryViewHolder, position: Int) {
            holder.bind(getItem(position))
        }

        override fun onViewRecycled(holder: CategoryViewHolder) {
            super.onViewRecycled(holder)
            holder.unbind()
        }

        inner class CategoryViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val ivCover: ImageView = itemView.findViewById(R.id.ivCover)
            private val tvName: TextView = itemView.findViewById(R.id.tvCategoryName)
            private val tvMeta: TextView = itemView.findViewById(R.id.tvCategoryMeta)

            fun bind(row: CategoryRow) {
                tvName.text = when (row.kind) {
                    Contract.KIND_STICKER -> "Stickers"
                    Contract.KIND_MEME -> "Memes"
                    Contract.KIND_ANIMATED_EMOJI -> "Emojis"
                    else -> "GIFs"
                }
                val items = "${row.count} ${if (row.count == 1) "item" else "items"}"
                tvMeta.text = if (row.packCount > 0) "$items - ${row.packCount} ${if (row.packCount == 1) "pack" else "packs"}" else items
                row.cover?.let {
                    ivCover.load(it, thumbLoader) {
                        size(256, 256)
                        precision(Precision.INEXACT)
                    }
                }
                itemView.setOnClickListener { onClick(row.kind) }
            }

            fun unbind() {
                ivCover.dispose()
                ivCover.setImageDrawable(null)
            }
        }
    }

    /** Pack filter chips, item count and the delete action above the grid of an open category. */
    private class GroupHeaderAdapter(
        private val onPackSelected: (String?) -> Unit,
        private val onDeleteClick: () -> Unit
    ) : RecyclerView.Adapter<GroupHeaderAdapter.GroupViewHolder>() {

        class Model(
            val summary: String,
            val deleteLabel: String,
            /** key (null is all) to label, empty when the category has no packs */
            val chips: List<Pair<String?, String>>,
            val selected: String?
        )

        private var model: Model? = null

        fun submit(newModel: Model) {
            model = newModel
            notifyItemChanged(0, Unit)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GroupViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_group_header, parent, false)
            return GroupViewHolder(view)
        }

        override fun onBindViewHolder(holder: GroupViewHolder, position: Int) {
            model?.let { holder.bind(it) }
        }

        override fun getItemCount(): Int = 1

        inner class GroupViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val scrollPacks: View = itemView.findViewById(R.id.scrollPacks)
            private val chipGroup: ChipGroup = itemView.findViewById(R.id.chipGroupPacks)
            private val tvSummary: TextView = itemView.findViewById(R.id.tvGroupSummary)
            private val btnDelete: MaterialButton = itemView.findViewById(R.id.btnDeleteGroup)

            init {
                btnDelete.setOnClickListener { onDeleteClick() }
            }

            fun bind(m: Model) {
                tvSummary.text = m.summary
                btnDelete.text = m.deleteLabel
                chipGroup.removeAllViews()
                scrollPacks.visibility = if (m.chips.isEmpty()) View.GONE else View.VISIBLE
                val inflater = LayoutInflater.from(itemView.context)
                for ((key, label) in m.chips) {
                    val chip = inflater.inflate(R.layout.item_pack_chip, chipGroup, false) as Chip
                    chip.id = View.generateViewId()
                    chip.text = label
                    chip.isChecked = key == m.selected
                    chip.setOnClickListener { onPackSelected(key) }
                    chipGroup.addView(chip)
                }
            }
        }
    }

    companion object {
        /** Filter value for files imported without a pack, never a real pack name. */
        private const val NO_PACK = "\u0000no_pack"
        private val SELECTION_PAYLOAD = Any()
        private const val STATE_KIND = "open_kind"
        private const val STATE_PACK = "open_pack"

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
