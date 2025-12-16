package com.example.tonuinoaudiomanager

import android.net.Uri
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import androidx.core.view.isVisible
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.tonuinoaudiomanager.databinding.ActivityReadNfcBinding
import com.example.tonuinoaudiomanager.nfc.NfcIntentActivity
import com.example.tonuinoaudiomanager.nfc.describeTagType
import com.example.tonuinoaudiomanager.nfc.readFromTag
import com.example.tonuinoaudiomanager.nfc.tagIdAsString
import com.example.tonuinoaudiomanager.nfc.tonuinoCookie
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@ExperimentalUnsignedTypes
class ReadNfcActivity : NfcIntentActivity() {
    override val TAG: String = "ReadNfcActivity"

    private lateinit var binding: ActivityReadNfcBinding
    private val trackAdapter = UsbFileAdapter(onDirectoryClick = {}, onItemLongPress = {})

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReadNfcBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupRecycler()
        showWaitingState()

        intent?.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)?.let { onNfcTag(it) }
    }

    private fun setupToolbar() {
        binding.readToolbar.setNavigationOnClickListener { finish() }
        binding.readToolbar.title = getString(R.string.nfc_read_title)
    }

    private fun setupRecycler() {
        binding.trackList.layoutManager = LinearLayoutManager(this)
        binding.trackList.adapter = trackAdapter
    }

    override fun onNfcTag(tag: Tag) {
        val tagId = tagIdAsString(tag)
        Log.i("$TAG.onNfcTag", "Tag $tagId")
        val data = readFromTag(tag)
        if (data.isEmpty()) {
            showError(getString(R.string.nfc_read_error_generic))
            return
        }

        val parsed = parseTonuinoData(data)
        if (parsed == null) {
            showError(getString(R.string.nfc_read_error_unknown_format))
            return
        }

        val description = runCatching { describeTagType(tag) }.getOrDefault("")
        binding.modeValue.text = modeLabel(parsed.mode)
        binding.folderValue.text = getString(R.string.nfc_write_folder_label, parsed.folder)
        binding.nfcStatus.text = getString(R.string.nfc_read_status_ready, tagId, description)
        binding.emptyTracksText.isVisible = false

        loadTracks(parsed.folder)
    }

    private fun showWaitingState() {
        binding.nfcStatus.text = getString(R.string.nfc_read_status_waiting)
        binding.modeValue.text = getString(R.string.nfc_mode_unknown)
        binding.folderValue.text = getString(R.string.nfc_folder_unknown)
        binding.emptyTracksText.isVisible = false
    }

    private fun showError(message: String) {
        binding.nfcStatus.text = message
        binding.emptyTracksText.isVisible = true
        binding.emptyTracksText.text = message
        trackAdapter.submitList(emptyList())
    }

    private fun loadTracks(folderNumber: Int) {
        val root = getPersistedRoot()
        if (root == null) {
            Toast.makeText(this, getString(R.string.nfc_read_missing_drive), Toast.LENGTH_LONG).show()
            showError(getString(R.string.nfc_read_missing_drive))
            return
        }

        val folder = findFolder(root, folderNumber)
        if (folder == null) {
            showError(getString(R.string.nfc_read_missing_folder, folderNumber))
            return
        }

        binding.loadingIndicator.isVisible = true
        binding.emptyTracksText.isVisible = false
        lifecycleScope.launch {
            val files = withContext(Dispatchers.IO) {
                runCatching {
                    folder.listFiles()
                        .asSequence()
                        .filter { it.isFile && it.name?.let { name -> TRACK_WHITELIST.matches(name) } == true }
                        .sortedBy { it.name ?: "" }
                        .map { file ->
                            val metadata = extractMetadata(file)
                            UsbFile(file, isHidden = false, metadata = metadata, directorySummary = null)
                        }
                        .toList()
                }.getOrElse { emptyList() }
            }

            binding.loadingIndicator.isVisible = false
            binding.emptyTracksText.isVisible = files.isEmpty()
            binding.emptyTracksText.text = getString(R.string.nfc_track_list_empty)
            trackAdapter.submitList(files)
        }
    }

    private fun modeLabel(mode: Int): String {
        return when (mode) {
            AUDIOBOOK_MULTIPLE_MODE -> getString(R.string.nfc_mode_audiobook_multiple)
            AUDIOBOOK_RANDOM_MODE -> getString(R.string.nfc_mode_audiobook_random)
            ALBUM_MODE -> getString(R.string.nfc_mode_album)
            PARTY_MODE -> getString(R.string.nfc_mode_party)
            SINGLE_MODE -> getString(R.string.nfc_mode_single)
            else -> getString(R.string.nfc_mode_unknown)
        }
    }

    private fun parseTonuinoData(data: UByteArray): TonuinoData? {
        if (data.size < 7) return null
        val cookie = data.take(tonuinoCookie.size)
        if (cookie != tonuinoCookie) return null
        val folder = data[5].toInt()
        val mode = data[6].toInt()
        return TonuinoData(folder = folder, mode = mode, raw = data)
    }

    private fun getPersistedRoot(): DocumentFile? {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val uriString = prefs.getString(KEY_URI, null) ?: return null
        val uri = Uri.parse(uriString)
        val hasPermission = contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission
        }
        if (!hasPermission) return null
        return DocumentFile.fromTreeUri(this, uri)
    }

    private fun findFolder(root: DocumentFile, folderNumber: Int): DocumentFile? {
        val target = "%02d".format(folderNumber)
        return root.listFiles().firstOrNull { it.isDirectory && it.name == target }
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

    data class TonuinoData(
        val folder: Int,
        val mode: Int,
        val raw: UByteArray
    )

    companion object {
        private val TRACK_WHITELIST = Regex("^(?!000)\\d{3}\\.mp3$", RegexOption.IGNORE_CASE)
        private const val PREFS = "usb_prefs"
        private const val KEY_URI = "usb_uri"
        private const val AUDIOBOOK_MULTIPLE_MODE = 5
        private const val AUDIOBOOK_RANDOM_MODE = 1
        private const val ALBUM_MODE = 2
        private const val PARTY_MODE = 3
        private const val SINGLE_MODE = 4
    }
}
