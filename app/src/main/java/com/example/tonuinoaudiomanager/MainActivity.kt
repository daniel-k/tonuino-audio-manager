package com.example.tonuinoaudiomanager

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.provider.DocumentsContract
import android.text.InputType
import android.widget.EditText
import android.widget.FrameLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
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
    private val fileAdapter = UsbFileAdapter { directory ->
        navigateIntoDirectory(directory)
    }
    private var isRequestingAccess = false
    private var pendingVolume: StorageVolume? = null
    private var lastRemovableVolume: StorageVolume? = null
    private var actionsExpanded = false

    private val openTree = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        handleTreeResult(uri)
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

        binding.fileList.layoutManager = LinearLayoutManager(this)
        binding.fileList.adapter = fileAdapter
        binding.navigateUpButton.setOnClickListener { navigateUpDirectory() }
        binding.mainActionFab.setOnClickListener { toggleActionsMenu() }
        binding.addFolderAction.setOnClickListener { promptNewFolder() }
        binding.addFolderFab.setOnClickListener { promptNewFolder() }

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

    private fun checkForUsbVolume(autoRequest: Boolean) {
        val removableVolume = storageManager.storageVolumes.firstOrNull { it.isRemovable }
        lastRemovableVolume = removableVolume

        if (removableVolume == null) {
            directoryStack.clear()
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
            val filesResult = withContext(Dispatchers.IO) {
                runCatching {
                    val files = directory.listFiles().asSequence()
                        .let { children ->
                            if (isRoot) {
                                children.filter { child ->
                                    child.isDirectory && child.name?.let { ROOT_WHITELIST.matches(it) } == true
                                }
                            } else {
                                children
                            }
                        }
                        .sortedWith(compareBy({ !it.isDirectory }, { it.name ?: "" }))
                        .map { UsbFile(it) }
                        .toList()
                    files
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

    private fun navigateIntoDirectory(directory: DocumentFile) {
        directoryStack.add(directory)
        showDirectory(directory)
    }

    private fun navigateUpDirectory() {
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
        binding.navigationContainer.isVisible = hasDirectory
        binding.navigateUpButton.isVisible = canGoUp
        binding.navigateUpButton.isEnabled = canGoUp
        binding.actionMenuContainer.isVisible = hasDirectory
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
                showDirectory(activeDirectory)
            } else {
                showSnackbar(getString(R.string.new_folder_error_failed))
            }
        }
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

    companion object {
        private const val PREFS = "usb_prefs"
        private const val KEY_URI = "usb_uri"
        private val ROOT_WHITELIST = Regex("^(0[1-9]|[1-9][0-9])$")
    }
}
