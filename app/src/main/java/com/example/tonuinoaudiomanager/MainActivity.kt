package com.example.tonuinoaudiomanager

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.media.MediaMetadataRetriever
import android.os.Bundle
import android.os.Environment
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.text.InputType
import android.view.Menu
import android.view.MenuItem
import android.widget.EditText
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.example.tonuinoaudiomanager.databinding.ActivityMainBinding
import com.google.android.material.color.DynamicColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var storageManager: StorageManager
    private val directoryStack = ArrayDeque<DocumentFile>()
    private val fileCache = FileCache()
    private val fileAdapter = UsbFileAdapter { directory ->
        navigateIntoDirectory(directory)
    }
    private var isRequestingAccess = false
    private var isReordering = false
    private var itemTouchHelper: ItemTouchHelper? = null
    private var pendingVolume: StorageVolume? = null
    private var lastRemovableVolume: StorageVolume? = null
    private var actionsExpanded = false
    private var showHidden = false

    private val openTree = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        handleTreeResult(uri)
    }
    private val pickFile =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            handlePickedFile(uri)
        }

    private val storageBroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_MEDIA_MOUNTED,
                Intent.ACTION_MEDIA_REMOVED,
                Intent.ACTION_MEDIA_UNMOUNTED,
                UsbManager.ACTION_USB_DEVICE_ATTACHED,
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    checkForUsbVolume(autoRequest = true)
                }
            }
        }
    }

    private val volumeCallback = object : StorageManager.StorageVolumeCallback() {
        override fun onStateChanged(storageVolume: StorageVolume) {
            if (storageVolume.isRemovable) {
                checkForUsbVolume(autoRequest = true)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        showHidden = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_SHOW_HIDDEN, false)

        binding.fileList.layoutManager = LinearLayoutManager(this)
        binding.fileList.adapter = fileAdapter
        binding.navigateUpButton.setOnClickListener { navigateUpDirectory() }
        binding.mainActionFab.setOnClickListener { toggleActionsMenu() }
        binding.addFolderAction.setOnClickListener { promptNewFolder() }
        binding.addFolderFab.setOnClickListener { promptNewFolder() }
        binding.addFileAction.setOnClickListener { promptAddFile() }
        binding.addFileFab.setOnClickListener { promptAddFile() }
        binding.reorderAction.setOnClickListener { promptReorderFiles() }
        binding.reorderFab.setOnClickListener { promptReorderFiles() }
        binding.reorderCancel.setOnClickListener { stopReorder(cancelAndRefresh = true) }
        binding.reorderApply.setOnClickListener { applyReorderFromList() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (actionsExpanded) {
                    setActionsExpanded(false)
                    return
                }
                if (isReordering) {
                    stopReorder(cancelAndRefresh = true)
                    return
                }
                if (directoryStack.size > 1) {
                    navigateUpDirectory()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        storageManager = getSystemService(Context.STORAGE_SERVICE) as StorageManager
        binding.requestAccessButton.setOnClickListener {
            val volume = lastRemovableVolume
            if (volume != null) {
                requestVolumeAccess(volume)
            } else {
                showStatus(getString(R.string.usb_waiting))
                checkForUsbVolume(autoRequest = true)
            }
        }

        val storageFilter = IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_MOUNTED)
            addAction(Intent.ACTION_MEDIA_REMOVED)
            addAction(Intent.ACTION_MEDIA_UNMOUNTED)
            addDataScheme("file")
        }
        val usbFilter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        registerReceiver(storageBroadcastReceiver, storageFilter, Context.RECEIVER_NOT_EXPORTED)
        registerReceiver(storageBroadcastReceiver, usbFilter, Context.RECEIVER_NOT_EXPORTED)
        storageManager.registerStorageVolumeCallback(mainExecutor, volumeCallback)

        updateNavigationUi()
        checkForUsbVolume(autoRequest = true)
    }

    override fun onResume() {
        super.onResume()
        pendingVolume?.let {
            if (!isRequestingAccess) {
                requestVolumeAccess(it)
            }
        } ?: checkForUsbVolume(autoRequest = true)
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(storageBroadcastReceiver)
        storageManager.unregisterStorageVolumeCallback(volumeCallback)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        menu.findItem(R.id.action_show_hidden)?.isChecked = showHidden
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_show_hidden -> {
                val newValue = !item.isChecked
                setShowHidden(newValue)
                item.isChecked = newValue
                true
            }

            R.id.action_reload_drive -> {
                reloadDrive()
                true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun checkForUsbVolume(autoRequest: Boolean) {
        val removableVolume = storageManager.storageVolumes.firstOrNull { it.isRemovable }
        lastRemovableVolume = removableVolume

        if (removableVolume == null) {
            directoryStack.clear()
            fileCache.clearAll()
            fileAdapter.submitList(emptyList())
            updateNavigationUi()
            showStatus(getString(R.string.usb_waiting))
            return
        }

        val isMounted = removableVolume.state == Environment.MEDIA_MOUNTED
        val savedUri = getPersistedUri()
        val persistedUri = savedUri?.takeIf { hasPersistedPermission(it) }
        if (savedUri != null && persistedUri == null) {
            clearPersistedUri(savedUri)
        }

        if (!isMounted) {
            directoryStack.clear()
            fileCache.clearAll()
            fileAdapter.submitList(emptyList())
            updateNavigationUi()
            showStatus(getString(R.string.usb_preparing))
            return
        }

        if (persistedUri != null) {
            loadFiles(persistedUri)
            return
        }

        if (autoRequest && !isRequestingAccess) {
            pendingVolume = removableVolume
            requestVolumeAccess(removableVolume)
        } else {
            showStatus(getString(R.string.usb_prompt_permission))
        }
    }

    private fun requestVolumeAccess(volume: StorageVolume?) {
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            pendingVolume = volume
            showStatus(getString(R.string.usb_prompt_permission))
            return
        }

        pendingVolume = volume
        isRequestingAccess = true

        binding.root.post {
            runCatching {
                openTree.launch(buildInitialTreeUri(volume))
            }.onFailure {
                showStatus(getString(R.string.usb_prompt_permission))
                isRequestingAccess = false
            }
        }
    }

    private fun handleTreeResult(uri: Uri?) {
        isRequestingAccess = false
        pendingVolume = null
        if (uri != null) {
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            savePersistedUri(uri)
            loadFiles(uri)
        } else {
            showStatus(getString(R.string.usb_prompt_permission))
        }
    }

    private fun loadFiles(rootUri: Uri) {
        showLoading(true)
        lifecycleScope.launch {
            val rootResult = withContext(Dispatchers.IO) {
                runCatching { DocumentFile.fromTreeUri(this@MainActivity, rootUri) }
            }
            val rootDocument = rootResult.getOrNull()
            directoryStack.clear()
            fileCache.clearAll()

            if (rootDocument == null) {
                showLoading(false)
                showStatus(getString(R.string.usb_error))
                fileAdapter.submitList(emptyList())
                updateNavigationUi()
                return@launch
            }

            directoryStack.add(rootDocument)
            showDirectory(rootDocument)
        }
    }

    private fun showDirectory(directory: DocumentFile) {
        setActionsExpanded(false)
        showStatus("")
        showLoading(true)
        lifecycleScope.launch {
            val isRoot = directoryStack.size == 1
            val isChildOfRoot = directoryStack.size == 2
            val showHiddenFiles = showHidden
            val filesResult = withContext(Dispatchers.IO) {
                runCatching {
                    buildDirectoryEntries(directory, isRoot, isChildOfRoot, showHiddenFiles)
                }
            }
            val files = filesResult.getOrElse { emptyList() }

            showLoading(false)
            if (files.isEmpty()) {
                val message = if (filesResult.isFailure) {
                    getString(R.string.usb_error)
                } else {
                    getString(R.string.usb_empty)
                }
                showStatus(message)
            }
            fileAdapter.submitList(files)
            updateNavigationUi()
        }
    }

    private fun buildDirectoryEntries(
        directory: DocumentFile,
        isRoot: Boolean,
        isChildOfRoot: Boolean,
        showHiddenFiles: Boolean
    ): List<UsbFile> {
        val cacheKey = EntriesKey(directory.uri.toString(), isRoot, isChildOfRoot, showHiddenFiles)
        fileCache.getEntries(cacheKey)?.let { return it }
        return getDirectoryChildren(directory).asSequence()
            .mapNotNull { child ->
                val isAllowed = when {
                    isRoot -> child.isDirectory && child.name?.let { ROOT_WHITELIST.matches(it) } == true
                    isChildOfRoot -> child.isFile && child.name?.let { TRACK_WHITELIST.matches(it) } == true
                    else -> true
                }
                if (!isAllowed && !showHiddenFiles) {
                    return@mapNotNull null
                }
                val metadata =
                    if (child.isFile && child.name?.endsWith(".mp3", ignoreCase = true) == true) {
                        getMetadataCached(child)
                    } else {
                        null
                    }
                val directorySummary =
                    if (child.isDirectory && isRoot) getDirectorySummaryCached(child) else null
                UsbFile(
                    child,
                    isHidden = !isAllowed,
                    metadata = metadata,
                    directorySummary = directorySummary
                )
            }
            .sortedWith(compareBy({ !it.document.isDirectory }, { it.document.name ?: "" }))
            .toList()
            .also { fileCache.putEntries(cacheKey, it) }
    }

    private fun getDirectoryChildren(directory: DocumentFile): List<DocumentFile> {
        return fileCache.getDirectory(directory) ?: directory.listFiles().toList().also {
            fileCache.putDirectory(directory, it)
        }
    }

    private fun getMetadataCached(file: DocumentFile): AudioMetadata? {
        return fileCache.getOrPutMetadata(file) { extractMetadata(file) }
    }

    private fun getDirectorySummaryCached(directory: DocumentFile): DirectorySummary {
        return fileCache.getOrPutDirectorySummary(directory) { collectDirectorySummary(directory) }
    }

    private fun navigateIntoDirectory(directory: DocumentFile) {
        if (isReordering) {
            stopReorder(cancelAndRefresh = true)
            return
        }
        directoryStack.add(directory)
        showDirectory(directory)
    }

    private fun navigateUpDirectory() {
        if (isReordering) {
            stopReorder(cancelAndRefresh = true)
            return
        }
        if (directoryStack.size <= 1) return
        directoryStack.removeLast()
        val parent = directoryStack.lastOrNull()
        if (parent != null) {
            showDirectory(parent)
        } else {
            showStatus(getString(R.string.usb_error))
            fileAdapter.submitList(emptyList())
            updateNavigationUi()
        }
    }

    private fun updateNavigationUi() {
        val hasDirectory = directoryStack.isNotEmpty()
        val canGoUp = directoryStack.size > 1
        val isChildOfRoot = directoryStack.size == 2
        binding.navigationContainer.isVisible = hasDirectory
        binding.navigateUpButton.isVisible = canGoUp
        binding.navigateUpButton.isEnabled = canGoUp
        binding.addFileAction.isVisible = canGoUp && !isReordering
        binding.addFolderAction.isVisible = hasDirectory && !isChildOfRoot && !isReordering
        binding.reorderAction.isVisible = canGoUp && !isReordering
        binding.actionMenuContainer.isVisible = hasDirectory && !isReordering
        binding.reorderBar.isVisible = isReordering
        if (!hasDirectory) {
            setActionsExpanded(false)
        }

        val path = directoryStack.mapIndexed { index, document ->
            document.name?.takeIf { it.isNotBlank() } ?: if (index == 0) {
                getString(R.string.usb_root)
            } else {
                "(unnamed)"
            }
        }.joinToString(" / ").ifEmpty { getString(R.string.usb_root) }

        binding.pathText.text = path
    }

    private fun showLoading(isLoading: Boolean) {
        binding.loading.isVisible = isLoading
    }

    private fun showStatus(message: String) {
        binding.statusText.text = message
        binding.statusText.isVisible = message.isNotEmpty()
        binding.requestAccessButton.isVisible = message == getString(R.string.usb_prompt_permission)
    }

    private fun setShowHidden(enabled: Boolean) {
        showHidden = enabled
        getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_SHOW_HIDDEN, enabled)
            .apply()
        invalidateOptionsMenu()
        directoryStack.lastOrNull()?.let { showDirectory(it) }
    }

    private fun toggleActionsMenu() {
        setActionsExpanded(!actionsExpanded)
    }

    private fun setActionsExpanded(expanded: Boolean) {
        actionsExpanded = expanded
        binding.actionList.isVisible = expanded
        val icon = if (expanded) {
            R.drawable.ic_close_24
        } else {
            R.drawable.ic_add_24
        }
        binding.mainActionFab.setImageResource(icon)
    }

    private fun reloadDrive() {
        if (isReordering) {
            stopReorder(cancelAndRefresh = false)
        }
        fileCache.clearAll()
        val currentDirectory = directoryStack.lastOrNull()
        if (currentDirectory != null) {
            showDirectory(currentDirectory)
            return
        }
        val persistedUri = getPersistedUri()?.takeIf { hasPersistedPermission(it) }
        if (persistedUri != null) {
            loadFiles(persistedUri)
        } else {
            checkForUsbVolume(autoRequest = true)
        }
    }

    private fun savePersistedUri(uri: Uri) {
        getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_URI, uri.toString())
            .apply()
    }

    private fun getPersistedUri(): Uri? {
        val uriString = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_URI, null) ?: return null
        return Uri.parse(uriString)
    }

    private fun clearPersistedUri(uri: Uri) {
        runCatching {
            contentResolver.releasePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
        getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_URI)
            .apply()
    }

    private fun hasPersistedPermission(uri: Uri): Boolean {
        return contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission
        }
    }

    private fun promptNewFolder() {
        val currentDirectory = directoryStack.lastOrNull()
        if (currentDirectory == null) {
            showSnackbar(getString(R.string.usb_waiting))
            return
        }
        if (directoryStack.size == 2) {
            showSnackbar(getString(R.string.new_folder_error_not_allowed))
            return
        }
        setActionsExpanded(false)

        val padding = (16 * resources.displayMetrics.density).toInt()
        val input = EditText(this).apply {
            hint = getString(R.string.dialog_new_folder_name)
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine()
        }
        val container = FrameLayout(this).apply {
            setPadding(padding, padding, padding, 0)
            addView(
                input,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_new_folder_title)
            .setView(container)
            .setPositiveButton(R.string.dialog_create) { _, _ ->
                val name = input.text?.toString()?.trim().orEmpty()
                createFolder(currentDirectory, name)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun createFolder(targetDirectory: DocumentFile, name: String) {
        if (name.isBlank()) {
            showSnackbar(getString(R.string.new_folder_error_blank))
            return
        }

        showLoading(true)
        lifecycleScope.launch {
            val createResult = withContext(Dispatchers.IO) {
                runCatching { targetDirectory.createDirectory(name) }
            }
            val created = createResult.getOrNull()
            showLoading(false)

            if (created != null) {
                val activeDirectory = directoryStack.lastOrNull() ?: targetDirectory
                fileCache.invalidateDirectory(activeDirectory)
                showDirectory(activeDirectory)
            } else {
                showSnackbar(getString(R.string.new_folder_error_failed))
            }
        }
    }

    private fun promptAddFile() {
        val targetDirectory = directoryStack.lastOrNull()
        val canAdd = targetDirectory != null && directoryStack.size > 1
        if (!canAdd) {
            showSnackbar(getString(R.string.add_file_error_no_folder))
            setActionsExpanded(false)
            return
        }
        setActionsExpanded(false)
        pickFile.launch(arrayOf("*/*"))
    }

    private fun handlePickedFile(uri: Uri?) {
        if (uri == null) return
        val targetDirectory = directoryStack.lastOrNull()
        if (targetDirectory == null || directoryStack.size <= 1) {
            showSnackbar(getString(R.string.add_file_error_no_folder))
            return
        }

        showLoading(true)
        lifecycleScope.launch {
            val displayName = withContext(Dispatchers.IO) {
                queryDisplayName(uri)
            } ?: "imported_file"
            val isChildOfRoot = directoryStack.size == 2 && directoryStack.last() == targetDirectory
            if (isChildOfRoot && !TRACK_WHITELIST.matches(displayName)) {
                showLoading(false)
                showSnackbar(getString(R.string.add_file_error_invalid_name))
                return@launch
            }
            val copyResult = withContext(Dispatchers.IO) {
                runCatching {
                    val mimeType = contentResolver.getType(uri) ?: "application/octet-stream"
                    val createdFile = targetDirectory.createFile(mimeType, displayName)
                        ?: error("Could not create target file")
                    contentResolver.openInputStream(uri).use { input ->
                        contentResolver.openOutputStream(createdFile.uri).use { output ->
                            if (input == null || output == null) error("Stream unavailable")
                            input.copyTo(output)
                        }
                    }
                    createdFile
                }
            }
            showLoading(false)

            if (copyResult.isSuccess) {
                fileCache.invalidateDirectory(targetDirectory)
                directoryStack.lastOrNull()?.let { showDirectory(it) }
                showSnackbar(getString(R.string.add_file_success))
            } else {
                showSnackbar(getString(R.string.add_file_error_failed))
            }
        }
    }

    private fun promptReorderFiles() {
        val currentDirectory = directoryStack.lastOrNull()
        val canReorder = currentDirectory != null && directoryStack.size > 1
        if (!canReorder) {
            showSnackbar(getString(R.string.reorder_error_no_folder))
            setActionsExpanded(false)
            return
        }
        if (isReordering) return
        val files = fileAdapter.getItems()
            .filter {
                it.document.isFile &&
                        !it.isHidden &&
                        it.document.name?.endsWith(".mp3", ignoreCase = true) == true
            }
        if (files.isEmpty()) {
            showSnackbar(getString(R.string.reorder_no_files))
            return
        }
        startReorder()
    }

    private fun startReorder() {
        isReordering = true
        setActionsExpanded(false)
        fileAdapter.setReorderMode(true)
        fileAdapter.setOnStartDrag { viewHolder ->
            itemTouchHelper?.startDrag(viewHolder)
        }

        val callback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN,
            0
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val from = viewHolder.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (!fileAdapter.isReorderable(from) || !fileAdapter.isReorderable(to)) return false
                fileAdapter.onItemMove(from, to)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
            }

            override fun getMovementFlags(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder
            ): Int {
                return if (fileAdapter.isReorderable(viewHolder.bindingAdapterPosition)) {
                    makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0)
                } else {
                    0
                }
            }

            override fun isLongPressDragEnabled(): Boolean = false
        }
        itemTouchHelper = ItemTouchHelper(callback).also { helper ->
            helper.attachToRecyclerView(binding.fileList)
        }
        updateNavigationUi()
    }

    private fun stopReorder(cancelAndRefresh: Boolean) {
        isReordering = false
        itemTouchHelper?.attachToRecyclerView(null)
        itemTouchHelper = null
        fileAdapter.setOnStartDrag(null)
        fileAdapter.setReorderMode(false)
        updateNavigationUi()
        if (cancelAndRefresh) {
            directoryStack.lastOrNull()?.let { showDirectory(it) }
        }
    }

    private fun applyReorderFromList() {
        val directory = directoryStack.lastOrNull()
        if (directory == null || !isReordering) {
            showSnackbar(getString(R.string.reorder_error_no_folder))
            return
        }

        val newOrder = fileAdapter.getItems()
            .filter {
                it.document.isFile &&
                        !it.isHidden &&
                        it.document.name?.endsWith(".mp3", ignoreCase = true) == true
            }
            .map { it.document }

        if (newOrder.isEmpty()) {
            showSnackbar(getString(R.string.reorder_no_files))
            stopReorder(cancelAndRefresh = true)
            return
        }

        stopReorder(cancelAndRefresh = false)
        applyReorder(directory, newOrder)
    }

    private fun applyReorder(directory: DocumentFile, newOrder: List<DocumentFile>) {
        showLoading(true)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val timestamp = System.currentTimeMillis()
                    val tempFiles = mutableListOf<DocumentFile>()
                    newOrder.forEachIndexed { index, file ->
                        val tempName = "__tmp_reorder_${timestamp}_$index.tmp"
                        if (!file.renameTo(tempName)) {
                            error("Temp rename failed")
                        }
                        tempFiles.add(file)
                    }

                    tempFiles.forEachIndexed { index, file ->
                        val targetName = "%03d.mp3".format(index + 1)
                        if (!file.renameTo(targetName)) {
                            error("Rename failed")
                        }
                    }
                }
            }
            showLoading(false)
            fileCache.invalidateDirectory(directory)
            showDirectory(directory)
            if (result.isSuccess) {
                showSnackbar(getString(R.string.reorder_success))
            } else {
                showSnackbar(getString(R.string.reorder_error_failed))
            }
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        return contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
    }

    private fun collectDirectorySummary(directory: DocumentFile): DirectorySummary {
        return runCatching {
            val mp3Files = getDirectoryChildren(directory)
                .asSequence()
                .filter { it.isFile && it.name?.endsWith(".mp3", ignoreCase = true) == true }
                .filter { it.name?.let { name -> TRACK_WHITELIST.matches(name) } == true }
                .sortedBy { it.name ?: "" }
                .toList()

            val metadataByTrack = mp3Files.map { it to getMetadataCached(it) }
            val firstMetadata = metadataByTrack.firstOrNull { it.second != null }?.second
            val albumArt = metadataByTrack.firstOrNull { it.second?.albumArt != null }?.second?.albumArt
            val distinctAlbums = metadataByTrack.mapNotNull { it.second }
                .map { it.artist to it.album }
                .distinct()
            val distinctArtists = metadataByTrack.mapNotNull { metadata ->
                metadata.second?.artist?.takeIf { it.isNotBlank() }
            }.distinct()

            DirectorySummary(
                album = firstMetadata?.album,
                artist = firstMetadata?.artist,
                albumArt = albumArt,
                trackCount = mp3Files.size,
                otherAlbumCount = (distinctAlbums.size - 1).coerceAtLeast(0),
                otherArtistCount = (distinctArtists.size - 1).coerceAtLeast(0)
            )
        }.getOrElse {
            DirectorySummary()
        }
    }

    private fun extractMetadata(file: DocumentFile): AudioMetadata? {
        val retriever = MediaMetadataRetriever()
        return try {
            contentResolver.openFileDescriptor(file.uri, "r")?.use { pfd ->
                retriever.setDataSource(pfd.fileDescriptor)
                val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                val album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                val track = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
                val albumArt = retriever.embeddedPicture?.let { data ->
                    decodeAlbumArt(data)
                }

                val hasMetadata = listOf(title, artist, album, track).any { !it.isNullOrBlank() } ||
                        albumArt != null

                if (!hasMetadata) {
                    null
                } else {
                    AudioMetadata(
                        title = title?.trim().takeUnless { it.isNullOrEmpty() },
                        artist = artist?.trim().takeUnless { it.isNullOrEmpty() },
                        album = album?.trim().takeUnless { it.isNullOrEmpty() },
                        trackNumber = track?.substringBefore('/')?.trim()
                            ?.takeUnless { it.isNullOrEmpty() },
                        albumArt = albumArt
                    )
                }
            }
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun decodeAlbumArt(data: ByteArray): Bitmap? {
        return try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(data, 0, data.size, options)
            val maxSize = 128
            val sampleSize = calculateInSampleSize(options.outWidth, options.outHeight, maxSize, maxSize)
            val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            BitmapFactory.decodeByteArray(data, 0, data.size, decodeOptions)
        } catch (_: Exception) {
            null
        }
    }

    private fun calculateInSampleSize(width: Int, height: Int, reqWidth: Int, reqHeight: Int): Int {
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            var halfHeight = height / 2
            var halfWidth = width / 2

            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    private fun showSnackbar(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
    }

    private fun buildInitialTreeUri(volume: StorageVolume?): Uri? {
        val uuid = volume?.uuid ?: return null
        val docId = "$uuid:"
        return runCatching {
            DocumentsContract.buildTreeDocumentUri("com.android.externalstorage.documents", docId)
        }.getOrNull()
    }

    private class FileCache {
        private val entriesCache = mutableMapOf<EntriesKey, List<UsbFile>>()
        private val directoryCache = mutableMapOf<String, List<DocumentFile>>()
        private val metadataCache = mutableMapOf<String, AudioMetadata?>()
        private val directorySummaryCache = mutableMapOf<String, DirectorySummary>()

        @Synchronized
        fun getDirectory(directory: DocumentFile): List<DocumentFile>? {
            return directoryCache[directory.uri.toString()]
        }

        @Synchronized
        fun putDirectory(directory: DocumentFile, children: List<DocumentFile>) {
            directoryCache[directory.uri.toString()] = children
        }

        @Synchronized
        fun getEntries(key: EntriesKey): List<UsbFile>? = entriesCache[key]

        @Synchronized
        fun putEntries(key: EntriesKey, entries: List<UsbFile>) {
            entriesCache[key] = entries
        }

        @Synchronized
        fun invalidateDirectory(directory: DocumentFile) {
            val key = directory.uri.toString()
            directoryCache.remove(key)
            directorySummaryCache.remove(key)
            entriesCache.keys.removeAll { it.directoryUri == key }
        }

        @Synchronized
        fun clearAll() {
            entriesCache.clear()
            directoryCache.clear()
            metadataCache.clear()
            directorySummaryCache.clear()
        }

        @Synchronized
        fun getOrPutMetadata(file: DocumentFile, loader: () -> AudioMetadata?): AudioMetadata? {
            val key = file.uri.toString()
            return metadataCache.getOrPut(key) { loader() }
        }

        @Synchronized
        fun getOrPutDirectorySummary(
            directory: DocumentFile,
            loader: () -> DirectorySummary
        ): DirectorySummary {
            val key = directory.uri.toString()
            return directorySummaryCache.getOrPut(key) { loader() }
        }
    }

    private data class EntriesKey(
        val directoryUri: String,
        val isRoot: Boolean,
        val isChildOfRoot: Boolean,
        val showHidden: Boolean
    )

    companion object {
        private const val PREFS = "usb_prefs"
        private const val KEY_URI = "usb_uri"
        private const val KEY_SHOW_HIDDEN = "show_hidden"
        private val ROOT_WHITELIST = Regex("^(0[1-9]|[1-9][0-9])$")
        private val TRACK_WHITELIST = Regex("^(?!000)\\d{3}\\.mp3$", RegexOption.IGNORE_CASE)
    }
}
