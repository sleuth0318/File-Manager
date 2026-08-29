package com.goodwy.filemanager.extensions

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.goodwy.commons.activities.BaseSimpleActivity
import com.goodwy.commons.dialogs.NewAppDialog
import com.goodwy.commons.extensions.getFilenameFromPath
import com.goodwy.commons.extensions.getMimeTypeFromUri
import com.goodwy.commons.extensions.getParentPath
import com.goodwy.commons.extensions.isNewApp
import com.goodwy.commons.extensions.launchActivityIntent
import com.goodwy.commons.extensions.openPathIntent
import com.goodwy.commons.extensions.renameFile
import com.goodwy.commons.extensions.setAsIntent
import com.goodwy.commons.extensions.showErrorToast
import com.goodwy.commons.extensions.toast
import com.goodwy.commons.extensions.sharePathsIntent
import com.goodwy.commons.helpers.LICENSE_AUTOFITTEXTVIEW
import com.goodwy.commons.helpers.LICENSE_GESTURE_VIEWS
import com.goodwy.commons.helpers.LICENSE_GLIDE
import com.goodwy.commons.helpers.LICENSE_PATTERN
import com.goodwy.commons.helpers.LICENSE_REPRINT
import com.goodwy.commons.helpers.LICENSE_ZIP4J
import com.goodwy.commons.helpers.ensureBackgroundThread
import com.goodwy.commons.models.FAQItem
import com.goodwy.filemanager.BuildConfig
import com.goodwy.filemanager.R
import com.goodwy.filemanager.activities.SimpleActivity
import com.goodwy.filemanager.helpers.OPEN_AS_AUDIO
import com.goodwy.filemanager.helpers.OPEN_AS_DEFAULT
import com.goodwy.filemanager.helpers.OPEN_AS_IMAGE
import com.goodwy.filemanager.helpers.OPEN_AS_TEXT
import com.goodwy.filemanager.helpers.OPEN_AS_VIDEO
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipFile

fun Activity.sharePaths(paths: ArrayList<String>) {
    sharePathsIntent(paths, BuildConfig.APPLICATION_ID)
}

fun Activity.tryOpenPathIntent(path: String, forceChooser: Boolean, openAsType: Int = OPEN_AS_DEFAULT, finishActivity: Boolean = false) {
    when {
        !forceChooser && (path.endsWith(".xapk", true) || path.endsWith(".apks", true)) -> installXapk(path)
        !forceChooser && path.endsWith(".apk", true) -> {
            val uri = FileProvider.getUriForFile(
                this, "${BuildConfig.APPLICATION_ID}.provider", File(path)
            )

            Intent().apply {
                action = Intent.ACTION_VIEW
                setDataAndType(uri, getMimeTypeFromUri(uri))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                launchActivityIntent(this)
            }
        }
        else -> {
            openPath(path, forceChooser, openAsType)

            if (finishActivity) {
                finish()
            }
        }
    }
}

private fun Activity.installXapk(path: String) {
    if (this !is BaseSimpleActivity) {
        openPath(path, false)
        return
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
            data = Uri.parse("package:$packageName")
            launchActivityIntent(this)
        }
        return
    }

    ensureBackgroundThread {
        val extractedApks = extractApksFromXapk(path)
        if (extractedApks.isEmpty()) {
            toast(R.string.xapk_no_apks_found)
            return@ensureBackgroundThread
        }

        try {
            val packageInstaller = packageManager.packageInstaller
            val sessionId = packageInstaller.createSession(
                PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            )

            packageInstaller.openSession(sessionId).use { session ->
                extractedApks.forEach { apkFile ->
                    FileInputStream(apkFile).use { inputStream ->
                        session.openWrite(apkFile.name, 0, apkFile.length()).use { outputStream ->
                            inputStream.copyTo(outputStream)
                            session.fsync(outputStream)
                        }
                    }
                }

                val callbackIntent = Intent(ACTION_XAPK_INSTALL_COMMIT).setPackage(packageName)
                val callback = object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) {
                        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
                        when (status) {
                            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                                val confirmationIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                                } else {
                                    @Suppress("DEPRECATION")
                                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                                }
                                confirmationIntent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                confirmationIntent?.let { startActivity(it) }
                            }
                            PackageInstaller.STATUS_SUCCESS -> {
                                cleanupXapkCache()
                                try {
                                    unregisterReceiver(this)
                                } catch (_: Exception) {
                                }
                            }
                            else -> {
                                cleanupXapkCache()
                                toast(intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: getString(R.string.xapk_install_failed))
                                try {
                                    unregisterReceiver(this)
                                } catch (_: Exception) {
                                }
                            }
                        }
                    }
                }

                ContextCompat.registerReceiver(this, callback, android.content.IntentFilter(ACTION_XAPK_INSTALL_COMMIT), ContextCompat.RECEIVER_NOT_EXPORTED)
                val pendingIntent = PendingIntent.getBroadcast(
                    this,
                    sessionId,
                    callbackIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                )
                session.commit(pendingIntent.intentSender)
            }
        } catch (exception: Exception) {
            cleanupXapkCache()
            showErrorToast(exception)
        }
    }
}

private fun Activity.extractApksFromXapk(path: String): List<File> {
    val destination = File(cacheDir, "xapk_install").apply {
        deleteRecursively()
        mkdirs()
    }
    val apkFiles = ArrayList<File>()

    ZipFile(File(path)).use { zipFile ->
        val entries = zipFile.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            val name = entry.name.substringAfterLast('/')
            if (!entry.isDirectory && name.endsWith(".apk", true) && entry.size != 0L) {
                val apkFile = File(destination, name)
                zipFile.getInputStream(entry).use { inputStream ->
                    FileOutputStream(apkFile).use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
                apkFiles.add(apkFile)
            }
        }
    }

    return apkFiles.sortedWith(compareBy<File> { file ->
        when {
            file.name.equals("base.apk", true) -> 0
            !file.name.startsWith("config.", true) && !file.name.startsWith("split_config.", true) -> 1
            else -> 2
        }
    }.thenBy { it.name.lowercase() })
}

private fun Activity.cleanupXapkCache() {
    File(cacheDir, "xapk_install").deleteRecursively()
}

private const val ACTION_XAPK_INSTALL_COMMIT = "com.goodwy.filemanager.action.XAPK_INSTALL_COMMIT"

fun Activity.openPath(path: String, forceChooser: Boolean, openAsType: Int = OPEN_AS_DEFAULT) {
    openPathIntent(path, forceChooser, BuildConfig.APPLICATION_ID, getMimeType(openAsType))
}

private fun getMimeType(type: Int) = when (type) {
    OPEN_AS_DEFAULT -> ""
    OPEN_AS_TEXT -> "text/*"
    OPEN_AS_IMAGE -> "image/*"
    OPEN_AS_AUDIO -> "audio/*"
    OPEN_AS_VIDEO -> "video/*"
    else -> "*/*"
}

fun Activity.setAs(path: String) {
    setAsIntent(path, BuildConfig.APPLICATION_ID)
}

fun BaseSimpleActivity.toggleItemVisibility(oldPath: String, hide: Boolean, callback: ((newPath: String) -> Unit)? = null) {
    val path = oldPath.getParentPath()
    var filename = oldPath.getFilenameFromPath()
    if ((hide && filename.startsWith('.')) || (!hide && !filename.startsWith('.'))) {
        callback?.invoke(oldPath)
        return
    }

    filename = if (hide) {
        ".${filename.trimStart('.')}"
    } else {
        filename.substring(1, filename.length)
    }

    val newPath = "$path/$filename"
    if (oldPath != newPath) {
        renameFile(oldPath, newPath, false) { success, useAndroid30Way ->
            callback?.invoke(newPath)
        }
    }
}

fun SimpleActivity.launchAbout() {
    val licenses = LICENSE_GLIDE or LICENSE_PATTERN or LICENSE_REPRINT or LICENSE_GESTURE_VIEWS or LICENSE_AUTOFITTEXTVIEW or LICENSE_ZIP4J

    val faqItems = arrayListOf(
        FAQItem(R.string.faq_3_title_commons, R.string.faq_3_text_commons),
        FAQItem(R.string.faq_9_title_commons, R.string.faq_9_text_commons)
    )

    if (!resources.getBoolean(R.bool.hide_google_relations)) {
        faqItems.add(FAQItem(R.string.faq_2_title_commons, R.string.faq_2_text_commons_g))
        faqItems.add(FAQItem(R.string.faq_6_title_commons, R.string.faq_6_text_commons_g))
        faqItems.add(FAQItem(R.string.faq_7_title_commons, R.string.faq_7_text_commons))
        faqItems.add(FAQItem(R.string.faq_10_title_commons, R.string.faq_10_text_commons))
    }

    val productIdX1 = BuildConfig.PRODUCT_ID_X1
    val productIdX2 = BuildConfig.PRODUCT_ID_X2
    val productIdX3 = BuildConfig.PRODUCT_ID_X3
    val subscriptionIdX1 = BuildConfig.SUBSCRIPTION_ID_X1
    val subscriptionIdX2 = BuildConfig.SUBSCRIPTION_ID_X2
    val subscriptionIdX3 = BuildConfig.SUBSCRIPTION_ID_X3
    val subscriptionYearIdX1 = BuildConfig.SUBSCRIPTION_YEAR_ID_X1
    val subscriptionYearIdX2 = BuildConfig.SUBSCRIPTION_YEAR_ID_X2
    val subscriptionYearIdX3 = BuildConfig.SUBSCRIPTION_YEAR_ID_X3

    val flavorName = BuildConfig.FLAVOR
    val storeDisplayName = when (flavorName) {
        "gplay" -> "Google Play"
        "foss" -> "FOSS"
        "rustore" -> "RuStore"
        else -> "Huawei"
    }
    val versionName = BuildConfig.VERSION_NAME
    val fullVersionText = "$versionName ($storeDisplayName)"

    startAboutActivity(
        appNameId = R.string.app_name_g,
        licenseMask = licenses,
        versionName = fullVersionText,
        flavorName = BuildConfig.FLAVOR,
        faqItems = faqItems,
        showFAQBeforeMail = true,
        productIdList = arrayListOf(productIdX1, productIdX2, productIdX3),
        productIdListRu = arrayListOf(productIdX1, productIdX2, productIdX3),
        subscriptionIdList = arrayListOf(subscriptionIdX1, subscriptionIdX2, subscriptionIdX3),
        subscriptionIdListRu = arrayListOf(subscriptionIdX1, subscriptionIdX2, subscriptionIdX3),
        subscriptionYearIdList = arrayListOf(subscriptionYearIdX1, subscriptionYearIdX2, subscriptionYearIdX3),
        subscriptionYearIdListRu = arrayListOf(subscriptionYearIdX1, subscriptionYearIdX2, subscriptionYearIdX3),
    )
}

fun Activity.newAppRecommendation() {
    if (resources.getBoolean(com.goodwy.commons.R.bool.is_foss)) {
        if (!isNewApp()) {
            if ((0..config.newAppRecommendationDialogCount).random() == 2) {
                val packageName = "reganamelif.ywdoog.ved".reversed()
                NewAppDialog(
                    activity = this,
                    packageName = packageName,
                    title = getString(com.goodwy.strings.R.string.notification_of_new_application),
                    text = "Alright Files",
                    drawable = AppCompatResources.getDrawable(this, com.goodwy.commons.R.drawable.ic_files_new),
                    showSubtitle = true
                ) {
                }
            }
        }
    }
}
