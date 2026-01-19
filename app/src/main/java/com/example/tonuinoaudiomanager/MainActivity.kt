package com.example.tonuinoaudiomanager

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.app.PendingIntent
import android.hardware.usb.UsbManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.media.MediaMetadataRetriever
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import android.os.Environment
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.view.Menu
import android.view.MenuItem
import android.util.Log
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
import com.example.tonuinoaudiomanager.databinding.BottomSheetItemActionsBinding
import com.example.tonuinoaudiomanager.nfc.NfcIntentHelper
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.color.DynamicColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var storageManager: StorageManager
    private val directoryStack = ArrayDeque<DocumentFile>()
    private val fileCache = FileCache()
    private val fileAdapter = UsbFileAdapter(
        onDirectoryClick = { directory ->
            navigateIntoDirectory(directory)
        },
        onItemLongPress = { item ->
            showItemActions(item)
        }
    )
    private val audioConverter by lazy { MediaCodecMp3Converter(this) }
    private var isRequestingAccess = false
    private var isReordering = false
    private var itemTouchHelper: ItemTouchHelper? = null
    private var pendingVolume: StorageVolume? = null
    private var lastRemovableVolume: StorageVolume? = null
    private var actionsExpanded = false
    private var showHidden = false
    private var transcodeMp3Sources = false
    private var itemActionsSheet: BottomSheetDialog? = null
    private var nfcAdapter: NfcAdapter? = null
    private var nfcPendingIntent: PendingIntent? = null

    private val openTree =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val uri = if (result.resultCode == Activity.RESULT_OK) {
                result.data?.data
            } else {
                null
            }
            handleTreeResult(uri)
        }
    private val pickFiles =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            handlePickedFiles(uris)
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
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        showHidden = prefs.getBoolean(KEY_SHOW_HIDDEN, false)
        transcodeMp3Sources = prefs.getBoolean(KEY_TRANSCODE_MP3, false)

        binding.fileList.layoutManager = LinearLayoutManager(this)
        binding.fileList.adapter = fileAdapter
        binding.navigateUpButton.setOnClickListener { navigateUpDirectory() }
        binding.mainActionFab.setOnClickListener { toggleActionsMenu() }
        binding.addFolderAction.setOnClickListener { promptNewFolder() }
        binding.addFolderFab.setOnClickListener { promptNewFolder() }
        binding.addFileAction.setOnClickListener { promptAddFiles() }
        binding.addFileFab.setOnClickListener { promptAddFiles() }
        binding.reorderAction.setOnClickListener { promptReorderFiles() }
        binding.reorderFab.setOnClickListener { promptReorderFiles() }
        binding.reorderCancel.setOnClickListener { stopReorder(cancelAndRefresh = true) }
        binding.reorderAuto.setOnClickListener { autoReorderByTrackNumber() }
        binding.reorderApply.setOnClickListener { applyReorderFromList() }
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        nfcPendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_MUTABLE
        )

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
        nfcAdapter?.enableForegroundDispatch(
            this,
            nfcPendingIntent,
            NfcIntentHelper.intentFilters,
            NfcIntentHelper.techLists
        )
        pendingVolume?.let {
            if (!isRequestingAccess) {
                requestVolumeAccess(it)
            }
        } ?: checkForUsbVolume(autoRequest = true)
    }

    override fun onDestroy() {
        itemActionsSheet?.dismiss()
        super.onDestroy()
        unregisterReceiver(storageBroadcastReceiver)
        storageManager.unregisterStorageVolumeCallback(volumeCallback)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        menu.findItem(R.id.action_show_hidden)?.isChecked = showHidden
        menu.findItem(R.id.action_transcode_mp3)?.isChecked = transcodeMp3Sources
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

            R.id.action_transcode_mp3 -> {
                val newValue = !item.isChecked
                setTranscodeMp3Sources(newValue)
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

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableForegroundDispatch(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val tag: Tag? = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
        if (tag != null) {
            val readIntent = Intent(this, ReadNfcActivity::class.java).apply {
                putExtra(NfcAdapter.EXTRA_TAG, tag)
            }
            startActivity(readIntent)
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
            val currentRootUri = directoryStack.firstOrNull()?.uri
            val currentDocId = runCatching {
                currentRootUri?.let { DocumentsContract.getDocumentId(it) }
            }.getOrNull()
            val persistedDocId = runCatching {
                DocumentsContract.getTreeDocumentId(persistedUri)
            }.getOrNull()

            if (currentDocId != null && persistedDocId != null && currentDocId == persistedDocId) {
                return
            }

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
                val intent = buildOpenTreeIntent(volume)
                openTree.launch(intent)
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
        val loadToken = beginDirectoryLoading()
        lifecycleScope.launch {
            val isRoot = directoryStack.size == 1
            val isChildOfRoot = directoryStack.size == 2
            val showHiddenFiles = showHidden
            val basePath = buildCurrentRelativePath()
            val totalFiles = withContext(Dispatchers.IO) {
                countFilesForProgress(directory, isRoot, isChildOfRoot, showHiddenFiles)
            }
            val reporter = DirectoryLoadReporter(totalFiles) { processed, total, path ->
                updateDirectoryLoadingProgress(loadToken, processed, total, path)
            }
            updateDirectoryLoadingProgress(loadToken, 0, totalFiles, null)
            val filesResult = withContext(Dispatchers.IO) {
                runCatching {
                    buildDirectoryEntries(
                        directory,
                        isRoot,
                        isChildOfRoot,
                        showHiddenFiles,
                        basePath,
                        reporter
                    )
                }
            }
            val files = filesResult.getOrElse { emptyList() }

            finishDirectoryLoading(loadToken)
            if (files.isEmpty()) {
                val message = if (filesResult.isFailure) {
                    getString(R.string.usb_error)
                } else {
                    getString(R.string.usb_empty)
                }
                showStatus(message)
            } else {
                showStatus("")
            }
            fileAdapter.submitList(files)
            updateNavigationUi()
        }
    }

    private fun buildDirectoryEntries(
        directory: DocumentFile,
        isRoot: Boolean,
        isChildOfRoot: Boolean,
        showHiddenFiles: Boolean,
        basePath: String,
        progressReporter: DirectoryLoadReporter? = null
    ): List<UsbFile> {
        val cacheKey = EntriesKey(directory.uri.toString(), isRoot, isChildOfRoot, showHiddenFiles)
        fileCache.getEntries(cacheKey)?.let { cached ->
            progressReporter?.complete()
            return cached
        }
        val children = getDirectoryChildren(directory)
        val entries = ArrayList<UsbFile>(children.size)
        for (child in children) {
            val isAllowed = isAllowedEntry(child, isRoot, isChildOfRoot)
            val shouldInclude = isAllowed || showHiddenFiles
            if (shouldInclude) {
                val childPath = appendRelativePath(basePath, child.name)
                if (child.isDirectory) {
                    progressReporter?.onFolder(childPath)
                } else {
                    progressReporter?.onFile(childPath)
                }
                val metadata =
                    if (child.isFile && child.name?.endsWith(".mp3", ignoreCase = true) == true) {
                        getMetadataCached(child)
                    } else {
                        null
                    }
                val directorySummary =
                    if (child.isDirectory && isRoot) {
                        getDirectorySummaryCached(child, childPath, progressReporter)
                    } else {
                        null
                    }
                entries.add(
                    UsbFile(
                        child,
                        isHidden = !isAllowed,
                        metadata = metadata,
                        directorySummary = directorySummary
                    )
                )
            }
        }
        return entries.sortedWith(compareBy({ !it.document.isDirectory }, { it.document.name ?: "" }))
            .also { fileCache.putEntries(cacheKey, it) }
    }

    private fun isAllowedEntry(
        child: DocumentFile,
        isRoot: Boolean,
        isChildOfRoot: Boolean
    ): Boolean {
        return when {
            isRoot -> child.isDirectory && child.name?.let { ROOT_WHITELIST.matches(it) } == true
            isChildOfRoot -> child.isFile && child.name?.let { TRACK_WHITELIST.matches(it) } == true
            else -> true
        }
    }

    private fun buildCurrentRelativePath(): String {
        return directoryStack
            .toList()
            .drop(1)
            .map { it.name?.takeIf { name -> name.isNotBlank() } ?: "(unnamed)" }
            .joinToString("/")
    }

    private fun appendRelativePath(basePath: String, name: String?): String {
        val segment = name?.takeIf { it.isNotBlank() } ?: "(unnamed)"
        return if (basePath.isBlank()) segment else "$basePath/$segment"
    }

    private fun getDirectoryChildren(directory: DocumentFile): List<DocumentFile> {
        return fileCache.getDirectory(directory) ?: directory.listFiles().toList().also {
            fileCache.putDirectory(directory, it)
        }
    }

    private fun getMetadataCached(file: DocumentFile): AudioMetadata? {
        return fileCache.getOrPutMetadata(file) { extractMetadata(file) }
    }

    private fun getDirectorySummaryCached(
        directory: DocumentFile,
        basePath: String,
        progressReporter: DirectoryLoadReporter?
    ): DirectorySummary {
        fileCache.getDirectorySummary(directory)?.let { summary ->
            if (progressReporter != null) {
                reportDirectoryFiles(directory, basePath, progressReporter)
            }
            return summary
        }
        return collectDirectorySummary(directory, basePath, progressReporter)
            .also { fileCache.putDirectorySummary(directory, it) }
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
        binding.loading.isIndeterminate = true
        binding.loading.setProgressCompat(0, false)
    }

    private var directoryLoadToken = 0

    private fun beginDirectoryLoading(): Int {
        directoryLoadToken += 1
        val token = directoryLoadToken
        binding.loading.isVisible = true
        binding.loading.isIndeterminate = true
        binding.loading.setProgressCompat(0, false)
        showStatus(getString(R.string.usb_loading))
        return token
    }

    private fun updateDirectoryLoadingProgress(
        token: Int,
        processed: Int,
        total: Int,
        currentPath: String?
    ) {
        binding.loading.post {
            if (token != directoryLoadToken) return@post
            val safePath = currentPath?.takeIf { it.isNotBlank() }
            if (total > 0) {
                val safeProcessed = processed.coerceIn(0, total)
                val percent = ((safeProcessed.toFloat() / total.toFloat()) * 100f)
                    .toInt()
                    .coerceIn(0, 100)
                binding.loading.isIndeterminate = false
                binding.loading.setProgressCompat(percent, true)
                val message = if (safePath != null) {
                    getString(R.string.usb_loading_progress_path, safeProcessed, total, safePath)
                } else {
                    getString(R.string.usb_loading_progress, safeProcessed, total)
                }
                showStatus(message)
            } else {
                binding.loading.isIndeterminate = true
                binding.loading.setProgressCompat(0, false)
                val message = if (safePath != null) {
                    getString(R.string.usb_loading_path, safePath)
                } else {
                    getString(R.string.usb_loading)
                }
                showStatus(message)
            }
        }
    }

    private fun finishDirectoryLoading(token: Int) {
        if (token != directoryLoadToken) return
        binding.loading.isVisible = false
        binding.loading.isIndeterminate = true
        binding.loading.setProgressCompat(0, false)
        directoryLoadToken += 1
    }

    private class DirectoryLoadReporter(
        private val totalFiles: Int,
        private val onUpdate: (processed: Int, total: Int, currentPath: String?) -> Unit
    ) {
        private var processedFiles = 0

        fun onFile(path: String) {
            processedFiles += 1
            onUpdate(processedFiles, totalFiles, path)
        }

        fun onFolder(path: String) {
            onUpdate(processedFiles, totalFiles, path)
        }

        fun complete() {
            processedFiles = totalFiles
            onUpdate(processedFiles, totalFiles, null)
        }
    }

    private var transcodeDialog: androidx.appcompat.app.AlertDialog? = null
    private var transcodeProgressBar: com.google.android.material.progressindicator.LinearProgressIndicator? = null
    private var transcodeProgressText: android.widget.TextView? = null

    private fun showTranscodeDialog(totalToTranscode: Int) {
        if (transcodeDialog?.isShowing == true) return
        val view = layoutInflater.inflate(R.layout.dialog_transcode_progress, null)
        transcodeProgressBar = view.findViewById(R.id.transcodeProgressBar)
        transcodeProgressText = view.findViewById(R.id.transcodeProgressText)
        transcodeProgressBar?.apply {
            isIndeterminate = true
            progress = 0
        }
        transcodeProgressText?.text = getString(
            R.string.transcode_progress_batch_initial,
            0,
            totalToTranscode
        )
        transcodeDialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.transcode_dialog_title)
            .setView(view)
            .setCancelable(false)
            .create()
        transcodeDialog?.show()
    }

    private fun updateTranscodeDialogProgress(
        totalToTranscode: Int,
        completedTranscodes: Int,
        currentItemProgress: Float?
    ) {
        val bar = transcodeProgressBar ?: return
        val text = transcodeProgressText
        if (totalToTranscode <= 0) return
        val percent = calculateTranscodeOverallProgress(totalToTranscode, completedTranscodes, currentItemProgress)
        val activeIndex = when {
            currentItemProgress != null && completedTranscodes < totalToTranscode -> completedTranscodes + 1
            else -> completedTranscodes
        }.coerceIn(0, totalToTranscode)

        bar.isIndeterminate = false
        bar.setProgressCompat(percent, true)
        text?.text = getString(
            R.string.transcode_progress_batch_percent,
            activeIndex,
            totalToTranscode,
            percent
        )
    }

    private fun calculateTranscodeOverallProgress(
        totalToTranscode: Int,
        completedTranscodes: Int,
        currentItemProgress: Float?
    ): Int {
        if (totalToTranscode <= 0) return 0
        val clampedTotal = totalToTranscode.coerceAtLeast(1)
        val clampedCompleted = completedTranscodes.coerceIn(0, clampedTotal)
        val inProgress = currentItemProgress?.coerceIn(0f, 1f) ?: 0f
        val fraction = (clampedCompleted.toFloat() + inProgress) / clampedTotal.toFloat()
        return (fraction * 100).toInt().coerceIn(0, 100)
    }

    private fun dismissTranscodeDialog() {
        transcodeDialog?.dismiss()
        transcodeDialog = null
        transcodeProgressBar = null
        transcodeProgressText = null
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

    private fun setTranscodeMp3Sources(enabled: Boolean) {
        transcodeMp3Sources = enabled
        getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_TRANSCODE_MP3, enabled)
            .apply()
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

        lifecycleScope.launch {
            val nextFolderName = withContext(Dispatchers.IO) {
                val rootDirectory = directoryStack.firstOrNull()
                rootDirectory?.let { findNextFolderName(it) }
            }
            if (nextFolderName == null) {
                showSnackbar(getString(R.string.new_folder_error_full))
                return@launch
            }
            createFolder(currentDirectory, nextFolderName)
        }
    }

    private suspend fun createFolder(targetDirectory: DocumentFile, name: String) {
        showLoading(true)
        val createResult = withContext(Dispatchers.IO) {
            runCatching { targetDirectory.createDirectory(name) }
        }
        val created = createResult.getOrNull()
        showLoading(false)

        if (created != null) {
            fileCache.invalidateDirectory(targetDirectory)
            directoryStack.add(created)
            showDirectory(created)
        } else {
            showSnackbar(getString(R.string.new_folder_error_failed))
        }
    }

    private fun promptAddFiles() {
        val targetDirectory = directoryStack.lastOrNull()
        val canAdd = targetDirectory != null && directoryStack.size > 1
        if (!canAdd) {
            showSnackbar(getString(R.string.add_file_error_no_folder))
            setActionsExpanded(false)
            return
        }
        setActionsExpanded(false)
        pickFiles.launch(AUDIO_MIME_TYPES)
    }

    private fun handlePickedFiles(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val targetDirectory = directoryStack.lastOrNull()
        if (targetDirectory == null || directoryStack.size <= 1) {
            showSnackbar(getString(R.string.add_file_error_no_folder))
            return
        }

        showLoading(true)
        lifecycleScope.launch {
            val pickedFiles = withContext(Dispatchers.IO) {
                uris.map { uri ->
                    val mimeType = contentResolver.getType(uri)
                    val displayName = queryDisplayName(uri) ?: "imported_file"
                    Triple(uri, displayName, mimeType)
                }
            }
            var nextTrackNumber = withContext(Dispatchers.IO) {
                findNextTrackNumber(targetDirectory)
            }
            if (nextTrackNumber == null) {
                showLoading(false)
                showTrackLimitDialog()
                return@launch
            }
            var totalTranscode = pickedFiles.count { (_, displayName, mimeType) ->
                shouldTranscodeFile(displayName, mimeType)
            }
            var completedTranscode = 0
            var successCount = 0
            var invalidCount = 0
            var conversionFailures = 0
            var noSpaceCount = 0

            if (totalTranscode > 0) {
                showTranscodeDialog(totalTranscode)
                updateTranscodeDialogProgress(totalTranscode, completedTranscode, 0f)
            }

            try {
                for ((uri, displayName, mimeType) in pickedFiles) {
                    if (!isAudioFile(displayName, mimeType)) {
                        invalidCount++
                        continue
                    }

                    val needsTranscode = shouldTranscodeFile(displayName, mimeType)
                    val trackNumber = nextTrackNumber
                    if (trackNumber == null) {
                        noSpaceCount++
                        if (needsTranscode && totalTranscode > 0) {
                            totalTranscode--
                            updateTranscodeDialogProgress(
                                totalTranscode,
                                completedTranscode,
                                null
                            )
                        }
                        continue
                    }

                    val targetFileName = "%03d.mp3".format(Locale.ROOT, trackNumber)
                    val copyResult = withContext(Dispatchers.IO) {
                        runCatching {
                            val targetMimeType = resolveMp3MimeType(mimeType)
                            val createdFile = targetDirectory.createFile(targetMimeType, targetFileName)
                                ?: error("Could not create target file")
                            try {
                                if (needsTranscode) {
                                    audioConverter.convertToMp3(uri, createdFile.uri) { progress ->
                                        if (needsTranscode && totalTranscode > 0) {
                                            updateTranscodeDialogProgress(
                                                totalTranscode,
                                                completedTranscode,
                                                progress
                                            )
                                        }
                                    }
                                } else {
                                    copyUriToTarget(uri, createdFile.uri)
                                }
                            } catch (t: Throwable) {
                                runCatching { createdFile.delete() }
                                throw t
                            }
                            createdFile
                        }
                    }
                    if (copyResult.isSuccess) {
                        successCount++
                        nextTrackNumber = (trackNumber + 1).takeIf { it <= 255 }
                    } else {
                        val error = copyResult.exceptionOrNull()
                        val shouldShowConversion = needsTranscode
                        if (error is AudioConversionException || shouldShowConversion) {
                            conversionFailures++
                        }
                        error?.let { Log.e(TAG, "Failed to add audio file $displayName", it) }
                    }

                    if (needsTranscode && totalTranscode > 0) {
                        completedTranscode++
                        updateTranscodeDialogProgress(
                            totalTranscode,
                            completedTranscode,
                            null
                        )
                    }
                }
            } finally {
                dismissTranscodeDialog()
                showLoading(false)
            }

            if (successCount > 0) {
                fileCache.invalidateDirectory(targetDirectory)
                directoryStack.firstOrNull()
                    ?.takeIf { it != targetDirectory }
                    ?.let { fileCache.invalidateDirectory(it) }
                directoryStack.lastOrNull()?.let { showDirectory(it) }
            }

            if (noSpaceCount > 0) {
                showTrackLimitDialog()
            }

            val totalCount = uris.size
            val message = when {
                successCount == totalCount -> {
                    resources.getQuantityString(
                        R.plurals.add_files_success,
                        successCount,
                        successCount
                    )
                }

                successCount > 0 -> getString(R.string.add_files_partial_success, successCount, totalCount)
                invalidCount > 0 -> getString(R.string.add_file_error_invalid_type)
                conversionFailures > 0 -> getString(R.string.add_file_error_conversion_failed)
                noSpaceCount > 0 -> null
                else -> getString(R.string.add_file_error_failed)
            }
            message?.let { showSnackbar(it) }
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
            .filter { isReorderableItem(it) }
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

    private fun isReorderableItem(item: UsbFile): Boolean {
        return item.document.isFile &&
                !item.isHidden &&
                item.document.name?.endsWith(".mp3", ignoreCase = true) == true
    }

    private fun autoReorderByTrackNumber() {
        if (!isReordering) {
            showSnackbar(getString(R.string.reorder_error_no_folder))
            return
        }

        val items = fileAdapter.getItems()
        val reorderableItems = items.filter { isReorderableItem(it) }
        if (reorderableItems.isEmpty()) {
            showSnackbar(getString(R.string.reorder_no_files))
            return
        }

        val sortedByTrack = reorderableItems.sortedWith(
            compareBy<UsbFile> {
                parseMetadataTrackNumber(it.metadata?.trackNumber) ?: Int.MAX_VALUE
            }.thenBy { it.document.name ?: "" }
        )
        val iterator = sortedByTrack.iterator()
        val updatedItems = items.map { item ->
            if (isReorderableItem(item)) {
                iterator.next()
            } else {
                item
            }
        }
        fileAdapter.submitList(updatedItems)
        fileAdapter.setReorderMode(true)
        showSnackbar(getString(R.string.reorder_auto_success))
    }

    private fun parseMetadataTrackNumber(trackNumber: String?): Int? {
        return trackNumber
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.takeWhile { it.isDigit() }
            ?.toIntOrNull()
    }

    private fun applyReorderFromList() {
        val directory = directoryStack.lastOrNull()
        if (directory == null || !isReordering) {
            showSnackbar(getString(R.string.reorder_error_no_folder))
            return
        }

        val newOrder = fileAdapter.getItems()
            .filter { isReorderableItem(it) }
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
                        val targetName = "%03d.mp3".format(Locale.ROOT, index + 1)
                        if (!file.renameTo(targetName)) {
                            error("Rename failed")
                        }
                    }
                }
            }
            showLoading(false)
            reloadDrive()
            if (result.isSuccess) {
                showSnackbar(getString(R.string.reorder_success))
            } else {
                showSnackbar(getString(R.string.reorder_error_failed))
            }
        }
    }

    private fun showItemActions(target: UsbFile) {
        if (isReordering) {
            showSnackbar(getString(R.string.delete_error_reordering))
            return
        }
        if (directoryStack.isEmpty()) {
            showSnackbar(getString(R.string.usb_waiting))
            return
        }
        setActionsExpanded(false)

        val displayName = target.document.name?.takeIf { it.isNotBlank() }
            ?: getString(R.string.delete_unknown_item)
        val isDirectory = target.document.isDirectory
        val binding = BottomSheetItemActionsBinding.inflate(layoutInflater)
        binding.itemTitle.text = displayName
        binding.itemSubtitle.text = if (isDirectory) {
            getString(R.string.item_actions_type_folder)
        } else {
            getString(R.string.item_actions_type_file)
        }
        val iconBitmap = target.metadata?.albumArt ?: target.directorySummary?.albumArt
        if (iconBitmap != null) {
            binding.itemIcon.setImageBitmap(iconBitmap)
        } else {
            val iconRes = if (isDirectory) R.drawable.ic_folder_24 else R.drawable.ic_file_24
            binding.itemIcon.setImageResource(iconRes)
        }

        binding.deleteAction.setOnClickListener {
            itemActionsSheet?.dismiss()
            promptDelete(target)
        }
        val folderNumber = parseFolderNumber(target.document.name)
        if (isDirectory && folderNumber != null) {
            binding.writeNfcAction.isVisible = true
            binding.writeNfcAction.setOnClickListener {
                itemActionsSheet?.dismiss()
                val uriString = target.document.uri.toString()
                val intent = Intent(this, WriteNfcActivity::class.java).apply {
                    putExtra(WriteNfcActivity.EXTRA_FOLDER_URI, uriString)
                    putExtra(WriteNfcActivity.EXTRA_FOLDER_NUMBER, folderNumber)
                }
                startActivity(intent)
            }
        } else {
            binding.writeNfcAction.isVisible = false
            binding.writeNfcAction.setOnClickListener(null)
        }

        itemActionsSheet?.dismiss()
        itemActionsSheet = BottomSheetDialog(this).apply {
            setContentView(binding.root)
            setOnDismissListener { itemActionsSheet = null }
            show()
        }
    }

    private fun promptDelete(target: UsbFile) {
        if (isReordering) {
            showSnackbar(getString(R.string.delete_error_reordering))
            return
        }

        val currentDirectory = directoryStack.lastOrNull()
        if (currentDirectory == null) {
            showSnackbar(getString(R.string.usb_waiting))
            return
        }
        setActionsExpanded(false)

        val document = target.document
        val displayName = document.name?.takeIf { it.isNotBlank() }
            ?: getString(R.string.delete_unknown_item)

        lifecycleScope.launch {
            val folderCounts = if (document.isDirectory) {
                withContext(Dispatchers.IO) { computeFolderCounts(document) }
            } else {
                null
            }
            val deleteDetails = buildDeleteDetails(target, displayName, folderCounts)

            val message = listOf(deleteDetails.confirmationLine, deleteDetails.details)
                .filter { it.isNotBlank() }
                .joinToString("\n\n")

            val dialog = MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle(deleteDetails.titleRes)
                .setMessage(message)
                .setPositiveButton(R.string.dialog_delete_confirm) { _, _ ->
                    deleteDocument(target, currentDirectory)
                }
                .setNegativeButton(android.R.string.cancel, null)
            deleteDetails.iconBitmap?.let { bitmap ->
                dialog.setIcon(BitmapDrawable(resources, bitmap))
            }
            dialog.show()
        }
    }

    private fun deleteDocument(target: UsbFile, parentDirectory: DocumentFile) {
        showLoading(true)
        lifecycleScope.launch {
            val deleteResult = withContext(Dispatchers.IO) {
                runCatching { target.document.delete() }
            }
            showLoading(false)

            if (deleteResult.getOrDefault(false)) {
                fileCache.invalidateDirectory(parentDirectory)
                if (target.document.isDirectory) {
                    fileCache.invalidateDirectory(target.document)
                }
                directoryStack.firstOrNull()
                    ?.takeIf { it != parentDirectory }
                    ?.let { fileCache.invalidateDirectory(it) }
                showDirectory(parentDirectory)
                showSnackbar(getString(R.string.delete_success))
            } else {
                showSnackbar(getString(R.string.delete_error_failed))
            }
        }
    }

    private data class DeleteDetails(
        val titleRes: Int,
        val confirmationLine: String,
        val details: String,
        val iconBitmap: Bitmap?
    )

    private data class FolderCounts(
        val folderCount: Int,
        val fileCount: Int
    )

    private fun buildDeleteDetails(
        target: UsbFile,
        displayName: String,
        folderCounts: FolderCounts?
    ): DeleteDetails {
        return if (target.document.isDirectory) {
            val folderCount = folderCounts?.folderCount ?: 0
            val fileCount = folderCounts?.fileCount ?: 0
            val summary = target.directorySummary
            val albumText = summary?.let { dir ->
                val baseAlbum = dir.album?.takeIf { it.isNotBlank() } ?: displayName
                if (dir.otherAlbumCount > 0 && baseAlbum.isNotBlank()) {
                    "$baseAlbum + ${dir.otherAlbumCount} more"
                } else {
                    baseAlbum
                }
            }.orEmpty()
            val artistText = summary?.let { dir ->
                val baseArtist = dir.artist.orEmpty()
                if (dir.otherArtistCount > 0 && baseArtist.isNotBlank()) {
                    "$baseArtist + ${dir.otherArtistCount} more"
                } else {
                    baseArtist
                }
            }.orEmpty()
            val detailLines = listOf(
                getString(R.string.delete_detail_name, displayName),
                albumText.takeIf { it.isNotBlank() }?.let { getString(R.string.delete_detail_album, it) },
                artistText.takeIf { it.isNotBlank() }?.let { getString(R.string.delete_detail_artist, it) },
                getString(R.string.delete_detail_files, fileCount)
            ).filterNotNull()
            DeleteDetails(
                titleRes = R.string.dialog_delete_folder_title,
                confirmationLine = getString(R.string.dialog_delete_folder_message, displayName),
                details = detailLines.joinToString("\n"),
                iconBitmap = summary?.albumArt ?: target.metadata?.albumArt
            )
        } else {
            val meta = target.metadata
            val detailLines = mutableListOf<String>()
            detailLines += getString(R.string.delete_detail_name, displayName)
            meta?.title?.takeIf { it.isNotBlank() }?.let {
                detailLines += getString(R.string.delete_detail_title, it)
            }
            meta?.artist?.takeIf { it.isNotBlank() }?.let {
                detailLines += getString(R.string.delete_detail_artist, it)
            }
            meta?.album?.takeIf { it.isNotBlank() }?.let {
                detailLines += getString(R.string.delete_detail_album, it)
            }

            DeleteDetails(
                titleRes = R.string.dialog_delete_file_title,
                confirmationLine = getString(R.string.dialog_delete_file_message, displayName),
                details = detailLines.joinToString("\n"),
                iconBitmap = meta?.albumArt
            )
        }
    }

    private fun computeFolderCounts(document: DocumentFile): FolderCounts {
        val children = runCatching { getDirectoryChildren(document) }.getOrElse { emptyList() }
        val folderCount = children.count { it.isDirectory }
        val fileCount = children.count { it.isFile }
        return FolderCounts(folderCount, fileCount)
    }

    private fun queryDisplayName(uri: Uri): String? {
        return contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
    }

    private fun collectDirectorySummary(
        directory: DocumentFile,
        basePath: String,
        progressReporter: DirectoryLoadReporter?
    ): DirectorySummary {
        return runCatching {
            val mp3Files = getSummaryFiles(directory)
            val metadataByTrack = mp3Files.map { file ->
                progressReporter?.onFile(appendRelativePath(basePath, file.name))
                file to getMetadataCached(file)
            }
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

    private fun getSummaryFiles(directory: DocumentFile): List<DocumentFile> {
        return getDirectoryChildren(directory)
            .asSequence()
            .filter { it.isFile && it.name?.endsWith(".mp3", ignoreCase = true) == true }
            .filter { it.name?.let { name -> TRACK_WHITELIST.matches(name) } == true }
            .sortedBy { it.name ?: "" }
            .toList()
    }

    private fun reportDirectoryFiles(
        directory: DocumentFile,
        basePath: String,
        progressReporter: DirectoryLoadReporter
    ) {
        val mp3Files = runCatching { getSummaryFiles(directory) }.getOrElse { emptyList() }
        for (file in mp3Files) {
            progressReporter.onFile(appendRelativePath(basePath, file.name))
        }
    }

    private fun countFilesForProgress(
        directory: DocumentFile,
        isRoot: Boolean,
        isChildOfRoot: Boolean,
        showHiddenFiles: Boolean
    ): Int {
        return runCatching {
            val children = getDirectoryChildren(directory)
            if (children.isEmpty()) return@runCatching 0
            if (isRoot) {
                var total = 0
                for (child in children) {
                    val isAllowed = isAllowedEntry(child, isRoot, isChildOfRoot)
                    val shouldInclude = isAllowed || showHiddenFiles
                    if (!shouldInclude) continue
                    total += if (child.isDirectory) {
                        getSummaryFiles(child).size
                    } else if (child.isFile) {
                        1
                    } else {
                        0
                    }
                }
                total
            } else {
                children.count { child ->
                    val isAllowed = isAllowedEntry(child, isRoot, isChildOfRoot)
                    val shouldInclude = isAllowed || showHiddenFiles
                    shouldInclude && child.isFile
                }
            }
        }.getOrElse { 0 }
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

    private fun findNextFolderName(rootDirectory: DocumentFile): String? {
        val existingNumbers = getDirectoryChildren(rootDirectory)
            .asSequence()
            .filter { it.isDirectory }
            .mapNotNull { parseFolderNumber(it.name) }
            .toSet()
        val nextNumber = (1..99).firstOrNull { it !in existingNumbers } ?: return null
        return "%02d".format(Locale.ROOT, nextNumber)
    }

    private fun parseFolderNumber(folderName: String?): Int? {
        val match = folderName?.let { ROOT_WHITELIST.matchEntire(it) } ?: return null
        return match.value.toIntOrNull()
    }

    private fun findNextTrackNumber(directory: DocumentFile): Int? {
        val highest = getDirectoryChildren(directory)
            .asSequence()
            .mapNotNull { parseTrackNumberFromFileName(it.name) }
            .maxOrNull() ?: 0
        val next = highest + 1
        return if (next in 1..255) next else null
    }

    private fun parseTrackNumberFromFileName(fileName: String?): Int? {
        val match = fileName?.let { TRACK_WHITELIST.matchEntire(it) } ?: return null
        return match.value.substring(0, 3).toIntOrNull()
    }

    private fun isAudioFile(displayName: String?, mimeType: String?): Boolean {
        val normalizedMimeType = mimeType?.substringBefore(';')?.lowercase(Locale.ROOT)
        if (normalizedMimeType?.startsWith("audio/") == true) return true

        val extension = displayName
            ?.substringAfterLast('.', "")
            ?.lowercase(Locale.ROOT)
            .orEmpty()
        return AUDIO_FILE_EXTENSIONS.contains(extension)
    }

    private fun shouldTranscodeFile(displayName: String?, mimeType: String?): Boolean {
        if (!isAudioFile(displayName, mimeType)) return false
        val isMp3Source = isMp3File(displayName, mimeType)
        return !isMp3Source || transcodeMp3Sources
    }

    private fun isMp3File(displayName: String?, mimeType: String?): Boolean {
        val hasMp3Extension = displayName?.lowercase(Locale.ROOT)?.endsWith(".mp3") == true
        val normalizedMimeType = mimeType?.substringBefore(';')?.lowercase(Locale.ROOT)
        val hasMp3MimeType = normalizedMimeType != null && MP3_MIME_TYPES.contains(normalizedMimeType)
        return hasMp3Extension || hasMp3MimeType
    }

    private fun resolveMp3MimeType(sourceMimeType: String?): String {
        val normalized = sourceMimeType?.substringBefore(';')?.lowercase(Locale.ROOT)
        return if (normalized != null && MP3_MIME_TYPES.contains(normalized)) {
            normalized
        } else {
            "audio/mpeg"
        }
    }

    private fun copyUriToTarget(sourceUri: Uri, targetUri: Uri) {
        contentResolver.openInputStream(sourceUri).use { input ->
            if (input == null) error("Stream unavailable")
            contentResolver.withSyncedOutputStream(targetUri) { output ->
                input.copyTo(output)
            }
        }
    }

    private fun showTrackLimitDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.add_file_error_full_title)
            .setMessage(R.string.add_file_error_full_message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showSnackbar(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
    }

    private fun buildInitialTreeUri(volume: StorageVolume?): Uri? {
        val uuid = volume?.uuid ?: return null
        val docId = "$uuid:"
        return runCatching {
            DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", docId)
        }.getOrNull()
    }

    private fun buildOpenTreeIntent(volume: StorageVolume?): Intent {
        val intent = runCatching { volume?.createOpenDocumentTreeIntent() }.getOrNull()
            ?: Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)

        buildInitialTreeUri(volume)?.let { initialUri ->
            intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, initialUri)
        }

        intent.addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
        )
        return intent
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

        @Synchronized
        fun getDirectorySummary(directory: DocumentFile): DirectorySummary? {
            return directorySummaryCache[directory.uri.toString()]
        }

        @Synchronized
        fun putDirectorySummary(directory: DocumentFile, summary: DirectorySummary) {
            directorySummaryCache[directory.uri.toString()] = summary
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
        private const val KEY_TRANSCODE_MP3 = "transcode_mp3"
        private val ROOT_WHITELIST = Regex("^(0[1-9]|[1-9][0-9])$")
        private val TRACK_WHITELIST = Regex("^(?!000)\\d{3}\\.mp3$", RegexOption.IGNORE_CASE)
        private val AUDIO_MIME_TYPES = arrayOf("audio/*")
        private val AUDIO_FILE_EXTENSIONS = setOf(
            "mp3",
            "m4a",
            "aac",
            "wav",
            "flac",
            "ogg",
            "oga",
            "opus",
            "wma",
            "3gp",
            "mka"
        )
        private val MP3_MIME_TYPES = setOf(
            "audio/mpeg",
            "audio/mp3",
            "audio/mpeg3",
            "audio/x-mpeg",
            "audio/x-mpeg-3",
            "audio/mpeg-3"
        )
        private const val TAG = "MainActivity"
    }
}
