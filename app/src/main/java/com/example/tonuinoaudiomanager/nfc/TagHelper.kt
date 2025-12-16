package com.example.tonuinoaudiomanager.nfc

import android.content.res.Resources
import android.nfc.FormatException
import android.nfc.Tag
import android.nfc.TagLostException
import android.nfc.tech.MifareClassic
import android.nfc.tech.MifareUltralight
import android.nfc.tech.NfcA
import android.nfc.tech.TagTechnology
import android.util.Log
import com.example.tonuinoaudiomanager.R
import java.io.IOException
import kotlin.math.ceil

private const val TAG = "TagHelper"
private const val tonuinoSector = 1
private const val firstBlockNum: Byte = 8
private const val lastBlockNum: Byte = 11

private fun fallbackString(resId: Int, fallback: String): String {
    return runCatching { Resources.getSystem().getString(resId) }.getOrElse { fallback }
}

@ExperimentalUnsignedTypes
val tonuinoCookie = hexToBytes("1337b347").toList() // TODO add to expert settings

@ExperimentalUnsignedTypes
private val factoryKey =
    hexToBytes("FFFFFFFFFFFF") // factory preset, same as MifareClassic.KEY_DEFAULT

fun tagIdAsString(tag: TagTechnology) = tagIdAsString(tag.tag)

fun tagIdAsString(tag: Tag): String = tag.id.toHex(":")

fun ByteArray.toHex(separator: String = " "): String =
    joinToString(separator) { eachByte -> "%02x".format(eachByte).uppercase() }

fun describeTagType(tag: Tag): String {
    return try {
        describeTagType(getTagTechnology(tag))
    } catch (ex: Exception) {
        Log.w(TAG, "describeTagType failed with $ex")
        fallbackString(R.string.identify_unsupported_type, "Unsupported NFC tag")
    }
}

fun getTagTechnology(tag: Tag): TagTechnology {
    return when {
        tag.techList.contains(MifareClassic::class.java.name) ->
            MifareClassic.get(tag)
        tag.techList.contains(MifareUltralight::class.java.name) ->
            MifareUltralight.get(tag)
        tag.techList.contains(NfcA::class.java.name) ->
            NfcA.get(tag)
        else ->
            throw FormatException("Can only handle MifareClassic, MifareUltralight and NfcA")
    }
}

fun describeTagType(tag: TagTechnology): String {
    return when (tag) {
        is MifareClassic ->
            when (tag.type) {
                MifareClassic.TYPE_CLASSIC -> "Mifare Classic"
                MifareClassic.TYPE_PLUS -> "Mifare Plus"
                MifareClassic.TYPE_PRO -> "Mifare Pro"
                else -> "Mifare Classic (${
                    fallbackString(R.string.identify_unknown_type, "Unknown")
                })"
            }
        is MifareUltralight ->
            when (tag.type) {
                MifareUltralight.TYPE_ULTRALIGHT -> "Mifare Ultralight"
                MifareUltralight.TYPE_ULTRALIGHT_C -> "Mifare Ultralight C"
                else -> "Mifare Ultralight (${
                    fallbackString(R.string.identify_unknown_type, "Unknown")
                })"
            }
        is NfcA ->
            "NfcA (SAK: ${tag.sak.toString().padStart(2, '0')}, ATQA: ${tag.atqa.toHex()})"
        else ->
            fallbackString(R.string.identify_unsupported_type, "Unsupported NFC tag")
    }
}

fun connectTo(tag: Tag): TagTechnology? {
    return getTagTechnology(tag).apply { connect() }
}

data class WriteResultData(val description: String, val result: WriteResult)

// ADT as shown on https://medium.com/sharenowtech/kotlin-adt-74472319962a
sealed class WriteResult {
    object Success : WriteResult()
    object UnsupportedFormat : WriteResult()
    object AuthenticationFailure : WriteResult()
    object TagUnavailable : WriteResult()
    data class NfcATransceiveNotOk(val response: ByteArray) : WriteResult()
    object UnknownError : WriteResult()
}

@ExperimentalUnsignedTypes
fun writeTonuino(tag: TagTechnology, data: UByteArray): WriteResultData {
    var description: String = ""
    val result: WriteResult = try {
        description = describeTagType(tag)
        when (tag) {
            is MifareClassic -> writeTag(tag, data)
            is MifareUltralight -> writeTag(tag, data)
            is NfcA -> writeTag(tag, data)
            else -> WriteResult.UnsupportedFormat
        }
    } catch (ex: TagLostException) {
        WriteResult.TagUnavailable
    } catch (ex: FormatException) {
        WriteResult.UnsupportedFormat
    } catch (ex: Exception) {
        WriteResult.UnknownError
    }

    return WriteResultData(description, result)
}

@ExperimentalUnsignedTypes
fun writeTag(tag: MifareClassic, data: UByteArray): WriteResult {
    val result: WriteResult
    try {
        if (!tag.isConnected) tag.connect()
    } catch (ex: IOException) {
        // is e.g. thrown if the NFC tag was removed
        return WriteResult.TagUnavailable
    }

    val key = factoryKey.asByteArray() // TODO allow configuration
    result = if (tag.authenticateSectorWithKeyB(tonuinoSector, key)) {
        val blockIndex = tag.sectorToBlock(tonuinoSector)
        // NOTE: This could truncates data, if we have more than 16 Byte (= MifareClassic.BLOCK_SIZE)
        val block = toFixedLengthBuffer(data, MifareClassic.BLOCK_SIZE)
        tag.writeBlock(blockIndex, block)
        Log.i(
            TAG, "Wrote ${byteArrayToHex(data)} to tag ${
                tagIdAsString(
                    tag.tag
                )
            }"
        )
        WriteResult.Success
    } else {
        WriteResult.AuthenticationFailure
    }

    tag.close()

    return result
}

@ExperimentalUnsignedTypes
fun writeTag(tag: MifareUltralight, data: UByteArray): WriteResult {
    try {
        if (!tag.isConnected) tag.connect()
    } catch (ex: IOException) {
        // is e.g. thrown if the NFC tag was removed
        return WriteResult.TagUnavailable
    }

    val len = data.size
    Log.i(TAG, "data byte size $len")

    val pagesNeeded = ceil(data.size.toDouble() / MifareUltralight.PAGE_SIZE).toInt()

    val block = toFixedLengthBuffer(data, MifareUltralight.PAGE_SIZE * pagesNeeded)
    var current = 0
    for (index in 0 until pagesNeeded) {
        val next = current + MifareUltralight.PAGE_SIZE
        val part = block.slice(current until next).toByteArray()
        tag.writePage(8 + index, part)
        Log.i(
            TAG,
            "Wrote ${byteArrayToHex(part.toUByteArray())} to tag ${tagIdAsString(tag.tag)}"
        )
        current = next
    }

    return WriteResult.Success
}


@ExperimentalUnsignedTypes
fun writeTag(tag: NfcA, data: UByteArray): WriteResult {
    try {
        if (!tag.isConnected) tag.connect()
    } catch (ex: IOException) {
        // is e.g. thrown if the NFC tag was removed
        return WriteResult.TagUnavailable
    }

    // The MFRC522 lib that TonUINO uses detects the tag type using the SAK ID with `PICC_GetType` (Proximity inductive coupling card)
    // See https://github.com/miguelbalboa/rfid/blob/eda2e385668163062250526c0e19033247d196a8/src/MFRC522.cpp#L1321
    // More information on the standards, different vendors and how to guess the tag type using SAK and ATQA values is on
    // https://nfc-tools.github.io/resources/standards/iso14443A/
    return when (tag.sak.toInt()) {
        0 ->
            writeMifareUltralight(tag, data)

        8, 9, 10, 11, 18 ->
            // should be writable as Mifare Classic according to
            // https://nfc-tools.github.io/resources/standards/iso14443A/ and https://github.com/miguelbalboa/rfid/blob/eda2e385668163062250526c0e19033247d196a8/src/MFRC522.cpp#L1321
            // writeMifareClassic(tag, data) // WIP: DOES NOT WORK YET!
            WriteResult.UnsupportedFormat

        else ->
            WriteResult.UnsupportedFormat
    }
}

fun writeMifareUltralight(tag: NfcA, data: UByteArray): WriteResult {
    val len = data.size
    var pageNum = firstBlockNum
    val pagesize = MifareUltralight.PAGE_SIZE
    val pagesNeeded = ceil(data.size.toDouble() / pagesize).toInt()

    Log.i(TAG, "data byte size $len")

    var current = 0
    val block = toFixedLengthBuffer(data, pagesize * pagesNeeded)
    for (index in 0 until pagesNeeded) {
        val next = current + pagesize
        val data = byteArrayOf(0xA2.toByte() /* WRITE */, pageNum) + block.slice(current until next)
            .toByteArray()
        Log.i(TAG, "Will transceive(${data.toHex()})")
        val result = tag.transceive(data)
        current = next
        Log.i(TAG, "transceive(${data.toHex()}) returned ${result.toHex()}")
        if (result.size != 1 || result[0] != 0x0A.toByte()) {
            Log.e(TAG, "transceive did not return `ACK (0A)`. Got `${result.toHex()}` instead.")
            tag.close()
            return WriteResult.NfcATransceiveNotOk(result)
        }
        pageNum++
    }

    tag.close()
    return WriteResult.Success
}

@ExperimentalUnsignedTypes
fun toFixedLengthBuffer(bytes: UByteArray, size: Int): ByteArray {
    val block = UByteArray(size) { 0u }
    bytes.forEachIndexed { index, value -> block[index] = value }
    return block.toByteArray()
}

fun techListOf(tag: TagTechnology?) = techListOf(tag?.tag)

fun techListOf(tag: Tag?): List<String> {
    // shorten fully qualified class names, e.g. android.nfc.tech.MifareClassic -> MifareClassic
    return tag?.techList?.map { str -> str.drop(str.lastIndexOf('.') + 1) } ?: listOf()
}
