package com.example.tonuinoaudiomanager

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.nfc.Tag
import android.nfc.tech.TagTechnology
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.tonuinoaudiomanager.databinding.ActivityWriteNfcBinding
import com.example.tonuinoaudiomanager.nfc.NfcIntentActivity
import com.example.tonuinoaudiomanager.nfc.WriteResult
import com.example.tonuinoaudiomanager.nfc.connectTo
import com.example.tonuinoaudiomanager.nfc.describeTagType
import com.example.tonuinoaudiomanager.nfc.tagIdAsString
import com.example.tonuinoaudiomanager.nfc.techListOf
import com.example.tonuinoaudiomanager.nfc.toHex
import com.example.tonuinoaudiomanager.nfc.tonuinoCookie
import com.example.tonuinoaudiomanager.nfc.writeTonuino
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@ExperimentalUnsignedTypes
class WriteNfcActivity : NfcIntentActivity() {
    override val TAG: String = "WriteNfcActivity"

    private lateinit var binding: ActivityWriteNfcBinding
    private val trackAdapter = UsbFileAdapter(onDirectoryClick = {}, onItemLongPress = {})
    private var folderUri: Uri? = null
    private var folderNumber: Int = -1
    private var currentTag: TagTechnology? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWriteNfcBinding.inflate(layoutInflater)
        setContentView(binding.root)

        folderUri = intent?.getStringExtra(EXTRA_FOLDER_URI)?.let { Uri.parse(it) }
        folderNumber = intent?.getIntExtra(EXTRA_FOLDER_NUMBER, -1) ?: -1

        if (folderUri == null || folderNumber !in 1..99) {
            Toast.makeText(this, getString(R.string.nfc_write_missing_folder), Toast.LENGTH_LONG).show()
            finish()
            return
        }

        setupToolbar()
        setupSummary()
        setupRecycler()
        setupWriteButton()
        loadTracks()
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { currentTag?.close() }
    }

    private fun setupToolbar() {
        binding.writeToolbar.setNavigationOnClickListener { finish() }
        binding.writeToolbar.title = getString(R.string.nfc_write_title)
    }

    private fun setupSummary() {
        binding.modeValue.text = getString(R.string.nfc_mode_audiobook_multiple)
        binding.folderValue.text = getString(R.string.nfc_write_folder_label, folderNumber)
        binding.nfcStatus.text = getString(R.string.nfc_write_status_waiting)
    }

    private fun setupRecycler() {
        binding.trackList.layoutManager = LinearLayoutManager(this)
        binding.trackList.adapter = trackAdapter
    }

    private fun setupWriteButton() {
        binding.writeButton.isEnabled = false
        binding.writeButton.text = getString(R.string.nfc_write_button_no_tag)
        binding.writeButton.setOnClickListener { writeTag() }
    }

    private fun loadTracks() {
        val folder = folderUri?.let { DocumentFile.fromTreeUri(this, it) }
        if (folder == null) {
            Toast.makeText(this, getString(R.string.nfc_write_missing_folder), Toast.LENGTH_LONG).show()
            finish()
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
            trackAdapter.submitList(files)
        }
    }

    private fun writeTag() {
        val tag = currentTag ?: run {
            binding.writeButton.isEnabled = false
            binding.writeButton.text = getString(R.string.nfc_write_button_no_tag)
            binding.nfcStatus.text = getString(R.string.nfc_write_status_waiting)
            return
        }

        val data = buildTonuinoData()
        val (description, result) = writeTonuino(tag, data)
        showWriteResult(description, result)
    }

    private fun showWriteResult(description: String, result: WriteResult) {
        val builder = MaterialAlertDialogBuilder(this)
        when (result) {
            is WriteResult.Success -> {
                builder.setTitle(R.string.nfc_write_result_success)
                builder.setMessage(getString(R.string.nfc_write_result_success_message))
            }

            is WriteResult.UnsupportedFormat -> {
                builder.setTitle(R.string.nfc_write_result_unsupported_title)
                builder.setMessage(
                    StringBuilder()
                        .append(getString(R.string.nfc_tag_type, description)).append("\n\n")
                        .append(getString(R.string.nfc_tag_technologies, techListOf(currentTag).joinToString(", ")))
                )
            }

            is WriteResult.AuthenticationFailure -> {
                builder.setTitle(R.string.nfc_write_result_failure_title)
                builder.setMessage(R.string.nfc_write_result_auth_failure)
            }

            is WriteResult.TagUnavailable -> {
                builder.setTitle(R.string.nfc_write_result_failure_title)
                builder.setMessage(R.string.nfc_write_result_unavailable)
                currentTag = null
                binding.writeButton.isEnabled = false
                binding.writeButton.text = getString(R.string.nfc_write_button_no_tag)
                binding.nfcStatus.text = getString(R.string.nfc_write_status_waiting)
            }

            is WriteResult.NfcATransceiveNotOk -> {
                builder.setTitle(R.string.nfc_write_result_failure_title)
                val notOk = result.response.toHex().trimEnd('0', ' ')
                builder.setMessage(
                    StringBuilder()
                        .append(getString(R.string.nfc_tag_type, description)).append("\n\n")
                        .append(getString(R.string.nfca_not_ok, notOk)).append("\n\n")
                        .append(getString(R.string.nfc_tag_technologies, techListOf(currentTag).joinToString(", ")))
                )
            }

            is WriteResult.UnknownError -> {
                builder.setTitle(R.string.nfc_write_result_unknown_title)
                builder.setMessage(
                    StringBuilder()
                        .append(getString(R.string.nfc_tag_type, description)).append("\n\n")
                        .append(getString(R.string.nfc_tag_technologies, techListOf(currentTag).joinToString(", ")))
                )
            }
        }

        builder.setPositiveButton(getString(R.string.button_ok), null)
        builder.show()
    }

    override fun onNfcTag(tag: Tag) {
        val tagId = tagIdAsString(tag)
        Log.i("$TAG.onNfcTag", "Tag $tagId")
        try {
            val tech = connectTo(tag)
            runCatching { currentTag?.close() }
            currentTag = tech
            val description = tech?.let { describeTagType(it) }
                .orEmpty()
                .ifBlank { getString(R.string.identify_unknown_type) }
            binding.writeButton.isEnabled = tech != null
            binding.writeButton.text = getString(R.string.nfc_write_button)
            binding.nfcStatus.text = getString(R.string.nfc_write_status_ready, tagId, description)
        } catch (ex: Exception) {
            currentTag = null
            binding.writeButton.isEnabled = false
            binding.writeButton.text = getString(R.string.nfc_write_button_no_tag)
            binding.nfcStatus.text = getString(R.string.nfc_write_status_waiting)
        }
    }

    private fun buildTonuinoData(): UByteArray {
        val buffer = UByteArray(9) { 0u }
        tonuinoCookie.forEachIndexed { index, value ->
            if (index < buffer.size) buffer[index] = value
        }
        buffer[4] = 2u.toUByte()
        buffer[5] = folderNumber.toUByte()
        buffer[6] = AUDIOBOOK_MULTIPLE_MODE
        buffer[7] = 0u
        buffer[8] = 0u
        return buffer
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

    companion object {
        const val EXTRA_FOLDER_URI = "extra_folder_uri"
        const val EXTRA_FOLDER_NUMBER = "extra_folder_number"
        private val TRACK_WHITELIST = Regex("^(?!000)\\d{3}\\.mp3$", RegexOption.IGNORE_CASE)
        private const val AUDIOBOOK_MULTIPLE_MODE: UByte = 5u
    }
}
