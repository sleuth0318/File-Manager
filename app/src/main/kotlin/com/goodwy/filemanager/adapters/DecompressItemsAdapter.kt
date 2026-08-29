package com.goodwy.filemanager.adapters

import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.util.TypedValue
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.bumptech.glide.request.RequestOptions
import com.goodwy.commons.adapters.MyRecyclerViewAdapter
import com.goodwy.commons.extensions.getColoredDrawableWithColor
import com.goodwy.commons.extensions.getTextSize
import com.goodwy.commons.extensions.getTimeFormat
import com.goodwy.commons.helpers.getFilePlaceholderDrawables
import com.goodwy.commons.views.MyRecyclerView
import com.goodwy.filemanager.R
import com.goodwy.filemanager.activities.SimpleActivity
import com.goodwy.filemanager.databinding.ItemDecompressionListFileDirBinding
import com.goodwy.filemanager.extensions.config
import com.goodwy.filemanager.models.ListItem
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile
import java.util.Locale

class DecompressItemsAdapter(activity: SimpleActivity, var listItems: MutableList<ListItem>, recyclerView: MyRecyclerView, itemClick: (Any) -> Unit) :
    MyRecyclerViewAdapter(activity, recyclerView, itemClick) {

    private lateinit var fileDrawable: Drawable
    private lateinit var folderDrawable: Drawable
    private var fileDrawables = HashMap<String, Drawable>()
    private var fontSize = 0f
    private var smallerFontSize = 0f
    private var dateFormat = ""
    private var timeFormat = ""

    init {
        initDrawables()
        fontSize = activity.getTextSize()
        smallerFontSize = fontSize * 0.8f
        dateFormat = activity.config.dateFormat
        timeFormat = activity.getTimeFormat()
    }

    override fun getActionMenuId() = 0

    override fun prepareActionMode(menu: Menu) {}

    override fun actionItemPressed(id: Int) {}

    override fun getSelectableItemCount() = 0

    override fun getIsItemSelectable(position: Int) = false

    override fun getItemSelectionKey(position: Int) = 0

    override fun getItemKeyPosition(key: Int) = 0

    override fun onActionModeCreated() {}

    override fun onActionModeDestroyed() {}

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        return createViewHolder(ItemDecompressionListFileDirBinding.inflate(layoutInflater, parent, false).root)
    }

    override fun onBindViewHolder(holder: MyRecyclerViewAdapter.ViewHolder, position: Int) {
        val fileDirItem = listItems[position]
        holder.bindView(fileDirItem, true, false) { itemView, layoutPosition ->
            setupView(itemView, fileDirItem)
        }
        bindViewHolder(holder)
    }

    override fun getItemCount() = listItems.size

    override fun onViewRecycled(holder: ViewHolder) {
        super.onViewRecycled(holder)
        if (!activity.isDestroyed && !activity.isFinishing) {
            ItemDecompressionListFileDirBinding.bind(holder.itemView).apply {
                if (itemIcon != null) {
                    Glide.with(activity).clear(itemIcon)
                }
            }
        }
    }

    private fun setupView(view: View, listItem: ListItem) {
        ItemDecompressionListFileDirBinding.bind(view).apply {
            val fileName = listItem.name
            itemName.text = fileName
            itemName.setTextColor(textColor)
            itemName.setTextSize(TypedValue.COMPLEX_UNIT_PX, fontSize)

            if (listItem.isDirectory) {
                itemIcon.setImageDrawable(folderDrawable)
            } else {
                val drawable = fileDrawables.getOrElse(fileName.substringAfterLast(".").lowercase(Locale.getDefault()), { fileDrawable })
                val options = RequestOptions()
                    .signature(listItem.getKey())
                    .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                    .error(drawable)
                    .centerCrop()

                val itemToLoad = getImagePathToLoad(listItem.path)
                if (!activity.isDestroyed) {
                    Glide.with(activity)
                        .load(itemToLoad)
                        .transition(DrawableTransitionOptions.withCrossFade())
                        .apply(options)
                        .into(itemIcon)
                }
            }
        }
    }

    private fun getImagePathToLoad(path: String): Any {
        return when {
            path.endsWith(".apk", true) -> getApkIcon(path) ?: path
            path.endsWith(".xapk", true) || path.endsWith(".apks", true) -> getSplitApkArchiveIcon(path) ?: path
            else -> path
        }
    }

    private fun getApkIcon(path: String): Drawable? {
        val packageInfo = activity.packageManager.getPackageArchiveInfo(path, PackageManager.GET_ACTIVITIES)
        val appInfo = packageInfo?.applicationInfo ?: return null
        appInfo.sourceDir = path
        appInfo.publicSourceDir = path
        return appInfo.loadIcon(activity.packageManager)
    }

    private fun getSplitApkArchiveIcon(path: String): Drawable? {
        return try {
            val cacheFolder = File(activity.cacheDir, "split_apk_icons").apply { mkdirs() }
            val tempApk = File(cacheFolder, "${path.hashCode()}_${File(path).lastModified()}.apk")
            if (!tempApk.exists()) {
                ZipFile(File(path)).use { zipFile ->
                    val entry = zipFile.entries().asSequence()
                        .filter { !it.isDirectory && it.name.endsWith(".apk", true) }
                        .sortedBy { entry ->
                            val name = entry.name.substringAfterLast('/')
                            when {
                                name.equals("base.apk", true) -> 0
                                !name.startsWith("config.", true) && !name.startsWith("split_config.", true) -> 1
                                else -> 2
                            }
                        }
                        .firstOrNull() ?: return null

                    zipFile.getInputStream(entry).use { inputStream ->
                        FileOutputStream(tempApk).use { outputStream -> inputStream.copyTo(outputStream) }
                    }
                }
            }
            getApkIcon(tempApk.absolutePath)
        } catch (_: Exception) {
            null
        }
    }

    @SuppressLint("UseCompatLoadingForDrawables")
    private fun initDrawables() {
        folderDrawable = resources.getColoredDrawableWithColor(R.drawable.ic_folder_vector, textColor)
        folderDrawable.alpha = 180
        fileDrawable = resources.getDrawable(R.drawable.ic_file_generic)
        fileDrawables = getFilePlaceholderDrawables(activity)
    }
}
