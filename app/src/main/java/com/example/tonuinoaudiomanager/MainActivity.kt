package com.example.tonuinoaudiomanager

import android.app.Activity
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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.tonuinoaudiomanager.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var storageManager: StorageManager
    private val fileAdapter = UsbFileAdapter()
    private var isRequestingAccess = false
    private var pendingVolume: StorageVolume? = null
    private var lastRemovableVolume: StorageVolume? = null

    private val openTree = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        handleTreeResult(result.resultCode == Activity.RESULT_OK, result.data?.data, result.data?.flags ?: 0)
    }

    private val openTreeFallback = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        handleTreeResult(
            uri != null,
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
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
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)

        binding.fileList.layoutManager = LinearLayoutManager(this)
        binding.fileList.adapter = fileAdapter

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
            fileAdapter.submitList(emptyList())
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

    private fun requestVolumeAccess(volume: StorageVolume) {
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            pendingVolume = volume
            showStatus(getString(R.string.usb_prompt_permission))
            return
        }

        val intents = listOfNotNull(
            volume.createAccessIntent(null),
            Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                putExtra("android.provider.extra.SHOW_ADVANCED", true)
            }
        ).map {
            it.addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )
            it
        }

        pendingVolume = volume
        isRequestingAccess = true

        binding.root.post {
            for (intent in intents) {
                val launched = runCatching {
                    openTree.launch(intent)
                    true
                }.getOrElse { false }
                if (launched) return@post
            }

            // Fallback: generic document tree picker
            runCatching {
                openTreeFallback.launch(null)
            }.onFailure {
                showStatus(getString(R.string.usb_prompt_permission))
                isRequestingAccess = false
            }
        }
    }

    private fun handleTreeResult(success: Boolean, uri: Uri?, flags: Int) {
        isRequestingAccess = false
        pendingVolume = null
        if (success && uri != null) {
            contentResolver.takePersistableUriPermission(
                uri,
                flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            )
            savePersistedUri(uri)
            loadFiles(uri)
        } else {
            showStatus(getString(R.string.usb_prompt_permission))
        }
    }

    private fun loadFiles(rootUri: Uri) {
        showLoading(true)
        lifecycleScope.launch {
            val filesResult = withContext(Dispatchers.IO) {
                runCatching {
                    DocumentFile.fromTreeUri(this@MainActivity, rootUri)
                        ?.listFiles()
                        ?.sortedWith(compareBy({ !it.isDirectory }, { it.name ?: "" }))
                        ?.map { UsbFile(it.name ?: "(unnamed)", it.isDirectory) }
                        .orEmpty()
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
            } else {
                showStatus("")
            }
            fileAdapter.submitList(files)
        }
    }

    private fun showLoading(isLoading: Boolean) {
        binding.loading.isVisible = isLoading
    }

    private fun showStatus(message: String) {
        binding.statusText.text = message
        binding.statusText.isVisible = message.isNotEmpty()
        binding.requestAccessButton.isVisible = message == getString(R.string.usb_prompt_permission)
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

    companion object {
        private const val PREFS = "usb_prefs"
        private const val KEY_URI = "usb_uri"
    }
}
