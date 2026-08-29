package com.goodwy.filemanager.dialogs

import android.app.Activity
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.ContentValues
import android.media.MediaScannerConnection
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.widget.LinearLayout
import android.widget.RelativeLayout
import androidx.appcompat.app.AlertDialog
import androidx.exifinterface.media.ExifInterface
import com.goodwy.commons.activities.BaseSimpleActivity
import com.goodwy.commons.dialogs.BasePropertiesDialog
import com.goodwy.commons.dialogs.ConfirmationDialog
import com.goodwy.commons.extensions.*
import com.goodwy.commons.helpers.*
import com.goodwy.commons.models.FileDirItem
import com.goodwy.commons.views.MyTextView
import com.goodwy.filemanager.R
import com.goodwy.filemanager.databinding.DialogEditLastModifiedBinding
import java.io.File
import java.io.InputStream
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import androidx.core.net.toUri

class PropertiesDialog : BasePropertiesDialog {
    private var mCountHiddenItems = false

    constructor(activity: Activity, path: String, countHiddenItems: Boolean = false) : super(activity) {
        if (!activity.getDoesFilePathExist(path) && !path.startsWith("content://")) {
            activity.toast(String.format(activity.getString(com.goodwy.commons.R.string.source_file_doesnt_exist), path))
            return
        }

        mCountHiddenItems = countHiddenItems
        addProperties(path)

        val builder = activity.getAlertDialogBuilder()
            .setPositiveButton(com.goodwy.commons.R.string.ok, null)

        if (!path.startsWith("content://") && path.canModifyEXIF() && activity.isPathOnInternalStorage(path)) {
            if ((isRPlus() && Environment.isExternalStorageManager()) || (!isRPlus() && activity.hasPermission(PERMISSION_WRITE_STORAGE))) {
                builder.setNeutralButton(com.goodwy.commons.R.string.remove_exif, null)
            }
        }

        builder.apply {
            mActivity.setupDialogStuff(mDialogView.root, this, com.goodwy.commons.R.string.properties) { alertDialog ->
                alertDialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                    removeEXIFFromPath(path)
                }
            }
        }
    }

    private fun addProperties(path: String) {
        val fileDirItem = FileDirItem(path, path.getFilenameFromPath(), mActivity.getIsPathDirectory(path))
        addProperty(com.goodwy.commons.R.string.name, fileDirItem.name)
        addProperty(com.goodwy.commons.R.string.path, fileDirItem.getParentPath())
        addProperty(com.goodwy.commons.R.string.size, "…", R.id.properties_size)

        ensureBackgroundThread {
            val fileCount = fileDirItem.getProperFileCount(mActivity, mCountHiddenItems)
            val size = fileDirItem.getProperSize(mActivity, mCountHiddenItems).formatSize()

            val directChildrenCount = if (fileDirItem.isDirectory) {
                fileDirItem.getDirectChildrenCount(mActivity, mCountHiddenItems).toString()
            } else {
                0
            }

            this.mActivity.runOnUiThread {
                (mDialogView.propertiesHolder.findViewById<RelativeLayout>(R.id.properties_size).findViewById<MyTextView>(R.id.property_value)).text = size

                if (fileDirItem.isDirectory) {
                    (mDialogView.propertiesHolder.findViewById<RelativeLayout>(R.id.properties_file_count).findViewById<MyTextView>(R.id.property_value)).text = fileCount.toString()
                    (mDialogView.propertiesHolder.findViewById<RelativeLayout>(R.id.properties_direct_children_count).findViewById<MyTextView>(R.id.property_value)).text = directChildrenCount.toString()
                }
            }

            if (!fileDirItem.isDirectory) {
                val projection = arrayOf(MediaStore.Images.Media.DATE_MODIFIED)
                val uri = MediaStore.Files.getContentUri("external")
                val selection = "${MediaStore.MediaColumns.DATA} = ?"
                val selectionArgs = arrayOf(path)
                val cursor = mActivity.contentResolver.query(uri, projection, selection, selectionArgs, null)
                cursor?.use {
                    if (cursor.moveToFirst()) {
                        val dateModified = cursor.getLongValue(MediaStore.Images.Media.DATE_MODIFIED) * 1000L
                        updateLastModified(path, dateModified)
                    } else {
                        updateLastModified(path, fileDirItem.getLastModified(mActivity))
                    }
                }

                val exif = if (mActivity.isPathOnOTG(fileDirItem.path)) {
                    ExifInterface((mActivity as BaseSimpleActivity).getFileInputStreamSync(fileDirItem.path)!!)
                } else if (fileDirItem.path.startsWith("content://")) {
                    try {
                        ExifInterface(mActivity.contentResolver.openInputStream(fileDirItem.path.toUri())!!)
                    } catch (_: Exception) {
                        return@ensureBackgroundThread
                    }
                } else if (mActivity.isRestrictedSAFOnlyRoot(path)) {
                    try {
                        ExifInterface(mActivity.contentResolver.openInputStream(mActivity.getAndroidSAFUri(path))!!)
                    } catch (_: Exception) {
                        return@ensureBackgroundThread
                    }
                } else {
                    try {
                        ExifInterface(fileDirItem.path)
                    } catch (_: Exception) {
                        return@ensureBackgroundThread
                    }
                }

                val latLon = FloatArray(2)
                if (exif.getLatLong(latLon)) {
                    mActivity.runOnUiThread {
                        addProperty(com.goodwy.commons.R.string.gps_coordinates, "${latLon[0]}, ${latLon[1]}")
                    }
                }

                val altitude = exif.getAltitude(0.0)
                if (altitude != 0.0) {
                    mActivity.runOnUiThread {
                        addProperty(com.goodwy.commons.R.string.altitude, "${altitude}m")
                    }
                }
            }
        }

        when {
            fileDirItem.isDirectory -> {
                addProperty(com.goodwy.commons.R.string.direct_children_count, "…", R.id.properties_direct_children_count)
                addProperty(com.goodwy.commons.R.string.files_count, "…", R.id.properties_file_count)
            }

            fileDirItem.path.isImageSlow() -> {
                fileDirItem.getResolution(mActivity)?.let { addProperty(com.goodwy.commons.R.string.resolution, it.formatAsResolution()) }
            }

            fileDirItem.path.isAudioSlow() -> {
                fileDirItem.getDuration(mActivity)?.let { addProperty(com.goodwy.commons.R.string.duration, it) }
                fileDirItem.getTitle(mActivity)?.let { addProperty(com.goodwy.commons.R.string.song_title, it) }
                fileDirItem.getArtist(mActivity)?.let { addProperty(com.goodwy.commons.R.string.artist, it) }
                fileDirItem.getAlbum(mActivity)?.let { addProperty(com.goodwy.commons.R.string.album, it) }
            }

            fileDirItem.path.isVideoSlow() -> {
                fileDirItem.getDuration(mActivity)?.let { addProperty(com.goodwy.commons.R.string.duration, it) }
                fileDirItem.getResolution(mActivity)?.let { addProperty(com.goodwy.commons.R.string.resolution, it.formatAsResolution()) }
                fileDirItem.getArtist(mActivity)?.let { addProperty(com.goodwy.commons.R.string.artist, it) }
                fileDirItem.getAlbum(mActivity)?.let { addProperty(com.goodwy.commons.R.string.album, it) }
            }
        }

        if (fileDirItem.isDirectory) {
            val timestamp = fileDirItem.getLastModified(mActivity)
            addProperty(com.goodwy.commons.R.string.last_modified, timestamp.formatDate(mActivity), R.id.properties_last_modified)
            setupLastModifiedEditor(path, timestamp)
        } else {
            addProperty(com.goodwy.commons.R.string.last_modified, "…", R.id.properties_last_modified)
            try {
                addExifProperties(path, mActivity)
            } catch (e: Exception) {
                mActivity.showErrorToast(e)
                return
            }

            val prefix = mActivity.appPrefix()
            if (mActivity.baseConfig.appId.removeSuffix(".debug") == prefix + "goodwy.filemanager") {
                calculateAndDisplayHash(path, com.goodwy.commons.R.string.md5, R.id.properties_md5, { inputStream -> inputStream.md5() }, { file -> file.md5() })
                calculateAndDisplayHash(path, com.goodwy.commons.R.string.sha1, R.id.properties_sha1, { inputStream -> inputStream.sha1() }, { file -> file.sha1() })
                calculateAndDisplayHash(path, com.goodwy.commons.R.string.sha256, R.id.properties_sha256, { inputStream -> inputStream.sha256() }, { file -> file.sha256() })
            }
        }
    }

    private fun calculateAndDisplayHash(path: String, labelRes: Int, propertyId: Int, hashInputStream: (InputStream) -> String?, hashFile: (File) -> String?) {
        addProperty(labelRes, "…", propertyId)
        ensureBackgroundThread {
            val digest = if (mActivity.isRestrictedSAFOnlyRoot(path)) {
                mActivity.contentResolver.openInputStream(mActivity.getAndroidSAFUri(path))?.let { hashInputStream(it) }
            } else {
                hashFile(File(path))
            }

            mActivity.runOnUiThread {
                if (digest != null) {
                    (mDialogView.propertiesHolder.findViewById<RelativeLayout>(propertyId).findViewById<MyTextView>(R.id.property_value)).text = digest
                } else {
                    mDialogView.propertiesHolder.findViewById<RelativeLayout>(propertyId).beGone()
                }
            }
        }
    }

    private fun updateLastModified(path: String, timestamp: Long) {
        mActivity.runOnUiThread {
            val row = mDialogView.root.findViewById<RelativeLayout>(R.id.properties_last_modified)
            row.findViewById<MyTextView>(R.id.property_value).text = timestamp.formatDate(mActivity)
            setupLastModifiedEditor(path, timestamp)
        }
    }

    private fun setupLastModifiedEditor(path: String, timestamp: Long) {
        if (path.startsWith("content://")) {
            return
        }

        val row = mDialogView.root.findViewById<RelativeLayout>(R.id.properties_last_modified) ?: return
        val value = row.findViewById<MyTextView>(R.id.property_value) ?: return
        value.setTextColor(mActivity.getProperPrimaryColor())
        row.setOnClickListener {
            showEditLastModifiedDialog(path, timestamp)
        }
    }

    private fun showEditLastModifiedDialog(path: String, timestamp: Long) {
        val binding = DialogEditLastModifiedBinding.inflate(mInflater)
        val formatter = SimpleDateFormat(LAST_MODIFIED_FORMAT, Locale.getDefault()).apply { isLenient = false }
        val calendar = Calendar.getInstance().apply { timeInMillis = timestamp }

        fun updateInput() {
            binding.editLastModifiedValue.setText(formatter.format(calendar.time))
            binding.editLastModifiedValue.setSelection(binding.editLastModifiedValue.text?.length ?: 0)
        }

        updateInput()
        binding.editLastModifiedDatePicker.setTextColor(mActivity.getProperPrimaryColor())
        binding.editLastModifiedTimePicker.setTextColor(mActivity.getProperPrimaryColor())

        binding.editLastModifiedDatePicker.setOnClickListener {
            DatePickerDialog(mActivity, { _, year, month, dayOfMonth ->
                calendar.set(Calendar.YEAR, year)
                calendar.set(Calendar.MONTH, month)
                calendar.set(Calendar.DAY_OF_MONTH, dayOfMonth)
                updateInput()
            }, calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH)).show()
        }

        binding.editLastModifiedTimePicker.setOnClickListener {
            TimePickerDialog(mActivity, { _, hourOfDay, minute ->
                calendar.set(Calendar.HOUR_OF_DAY, hourOfDay)
                calendar.set(Calendar.MINUTE, minute)
                updateInput()
            }, calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE), true).show()
        }

        mActivity.getAlertDialogBuilder()
            .setPositiveButton(R.string.apply, null)
            .setNegativeButton(com.goodwy.commons.R.string.cancel, null)
            .apply {
                (mActivity as BaseSimpleActivity).setupDialogStuff(binding.root, this, com.goodwy.commons.R.string.last_modified) { alertDialog ->
                    alertDialog.showKeyboard(binding.editLastModifiedValue)
                    alertDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val input = binding.editLastModifiedValue.text.toString()
                        val position = ParsePosition(0)
                        val parsedDate = formatter.parse(input, position)
                        if (parsedDate == null || position.index != input.length) {
                            mActivity.toast(R.string.invalid_date_time)
                            return@setOnClickListener
                        }

                        val success = File(path).setLastModified(parsedDate.time)
                        if (success) {
                            refreshLastModifiedGlobally(path, parsedDate.time)
                            updateLastModified(path, parsedDate.time)
                            mActivity.toast(R.string.last_modified_updated)
                            alertDialog.dismiss()
                        } else {
                            mActivity.toast(R.string.last_modified_update_failed)
                        }
                    }
                }
            }
    }

    private fun refreshLastModifiedGlobally(path: String, timestamp: Long) {
        try {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DATE_MODIFIED, timestamp / 1000L)
            }
            mActivity.contentResolver.update(
                MediaStore.Files.getContentUri("external"),
                values,
                "${MediaStore.MediaColumns.DATA} = ?",
                arrayOf(path)
            )
        } catch (_: Exception) {
        }

        try {
            MediaScannerConnection.scanFile(mActivity, arrayOf(path), null, null)
        } catch (_: Exception) {
        }
    }

    constructor(activity: Activity, paths: List<String>, countHiddenItems: Boolean = false) : super(activity) {
        mCountHiddenItems = countHiddenItems

        val fileDirItems = ArrayList<FileDirItem>(paths.size)
        paths.forEach {
            val fileDirItem = FileDirItem(it, it.getFilenameFromPath(), activity.getIsPathDirectory(it))
            fileDirItems.add(fileDirItem)
        }

        val isSameParent = isSameParent(fileDirItems)

        addProperty(com.goodwy.commons.R.string.items_selected, paths.size.toString())
        if (isSameParent) {
            addProperty(com.goodwy.commons.R.string.path, fileDirItems[0].getParentPath())
        }

        addProperty(com.goodwy.commons.R.string.size, "…", R.id.properties_size)
        addProperty(com.goodwy.commons.R.string.files_count, "…", R.id.properties_file_count)

        ensureBackgroundThread {
            val fileCount = fileDirItems.sumByInt { it.getProperFileCount(activity, countHiddenItems) }
            val size = fileDirItems.sumByLong { it.getProperSize(activity, countHiddenItems) }.formatSize()
            activity.runOnUiThread {
                (mDialogView.propertiesHolder.findViewById<RelativeLayout>(R.id.properties_size).findViewById<MyTextView>(R.id.property_value)).text = size
                (mDialogView.propertiesHolder.findViewById<RelativeLayout>(R.id.properties_file_count).findViewById<MyTextView>(R.id.property_value)).text = fileCount.toString()
            }
        }

        val builder = activity.getAlertDialogBuilder()
            .setPositiveButton(com.goodwy.commons.R.string.ok, null)

        if (!paths.any { it.startsWith("content://") } && paths.any { it.canModifyEXIF() } && paths.any { activity.isPathOnInternalStorage(it) }) {
            if ((isRPlus() && Environment.isExternalStorageManager()) || (!isRPlus() && activity.hasPermission(PERMISSION_WRITE_STORAGE))) {
                builder.setNeutralButton(com.goodwy.commons.R.string.remove_exif, null)
            }
        }

        builder.apply {
            mActivity.setupDialogStuff(mDialogView.root, this, com.goodwy.commons.R.string.properties) { alertDialog ->
                alertDialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                    removeEXIFFromPaths(paths)
                }
            }
        }
    }

    private fun addExifProperties(path: String, activity: Activity) {
        val exif = if (activity.isPathOnOTG(path)) {
            ExifInterface((activity as BaseSimpleActivity).getFileInputStreamSync(path)!!)
        } else if (path.startsWith("content://")) {
            try {
                ExifInterface(activity.contentResolver.openInputStream(path.toUri())!!)
            } catch (_: Exception) {
                return
            }
        } else if (activity.isRestrictedSAFOnlyRoot(path)) {
            try {
                ExifInterface(activity.contentResolver.openInputStream(activity.getAndroidSAFUri(path))!!)
            } catch (_: Exception) {
                return
            }
        } else {
            ExifInterface(path)
        }

        val dateTaken = exif.getExifDateTaken(activity)
        if (dateTaken.isNotEmpty()) {
            addProperty(com.goodwy.commons.R.string.date_taken, dateTaken)
        }

        val cameraModel = exif.getExifCameraModel()
        if (cameraModel.isNotEmpty()) {
            addProperty(com.goodwy.commons.R.string.camera, cameraModel)
        }

        val exifString = exif.getExifProperties()
        if (exifString.isNotEmpty()) {
            addProperty(com.goodwy.commons.R.string.exif, exifString)
        }
    }

    private fun removeEXIFFromPath(path: String) {
        ConfirmationDialog(mActivity, "", com.goodwy.commons.R.string.remove_exif_confirmation) {
            try {
                ExifInterface(path).removeValues()
                mActivity.toast(com.goodwy.commons.R.string.exif_removed)
                mPropertyView.findViewById<LinearLayout>(R.id.properties_holder).removeAllViews()
                addProperties(path)
            } catch (e: Exception) {
                mActivity.showErrorToast(e)
            }
        }
    }

    private fun removeEXIFFromPaths(paths: List<String>) {
        ConfirmationDialog(mActivity, "", com.goodwy.commons.R.string.remove_exif_confirmation) {
            try {
                paths.filter { mActivity.isPathOnInternalStorage(it) && it.canModifyEXIF() }.forEach {
                    ExifInterface(it).removeValues()
                }
                mActivity.toast(com.goodwy.commons.R.string.exif_removed)
            } catch (e: Exception) {
                mActivity.showErrorToast(e)
            }
        }
    }

    private fun isSameParent(fileDirItems: List<FileDirItem>): Boolean {
        var parent = fileDirItems[0].getParentPath()
        for (file in fileDirItems) {
            val curParent = file.getParentPath()
            if (curParent != parent) {
                return false
            }

            parent = curParent
        }
        return true
    }

    companion object {
        private const val LAST_MODIFIED_FORMAT = "dd/MM/yyyy HH:mm:ss"
    }
}
