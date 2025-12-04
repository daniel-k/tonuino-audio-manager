package com.example.tonuinoaudiomanager

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.documentfile.provider.DocumentFile
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

    private val openTree = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = result.data?.data
            if (uri != null) {
                val flags = result.data?.flags ?: 0
                contentResolver.takePersistableUriPermission(
                    uri,
                    flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                )
                savePersistedUri(uri)
                loadFiles(uri)
            } else {
                showStatus(getString(R.string.usb_error))
            }
        } else {
            showStatus(getString(R.string.usb_prompt_permission))
        }
    }

    private val usbAttachReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (Intent.ACTION_MEDIA_MOUNTED == intent?.action || Intent.ACTION_MEDIA_REMOVED == intent?.action) {
                checkForUsbVolume(autoRequest = true)
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

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_MOUNTED)
            addAction(Intent.ACTION_MEDIA_REMOVED)
            addDataScheme("file")
        }
        registerReceiver(usbAttachReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        storageManager.registerStorageVolumeCallback(mainExecutor, volumeCallback)

        checkForUsbVolume(autoRequest = true)
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(usbAttachReceiver)
        storageManager.unregisterStorageVolumeCallback(volumeCallback)
    }

    private fun checkForUsbVolume(autoRequest: Boolean) {
        val removableVolume = storageManager.storageVolumes.firstOrNull {
            it.isRemovable && it.state == Environment.MEDIA_MOUNTED
        }

        if (removableVolume == null) {
            fileAdapter.submitList(emptyList())
            showStatus(getString(R.string.usb_waiting))
            return
        }

        val persistedUri = getPersistedUri()
        if (persistedUri != null) {
            loadFiles(persistedUri)
        } else if (autoRequest) {
            requestVolumeAccess(removableVolume)
        } else {
            showStatus(getString(R.string.usb_prompt_permission))
        }
    }

    private fun requestVolumeAccess(volume: StorageVolume) {
        val intent = volume.createAccessIntent(null)
            ?: Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                putExtra("android.provider.extra.SHOW_ADVANCED", true)
            }
        intent.addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        )
        openTree.launch(intent)
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

    companion object {
        private const val PREFS = "usb_prefs"
        private const val KEY_URI = "usb_uri"
    }
}
