package com.goodwy.filemanager.ftp

import android.content.Context
import com.goodwy.commons.extensions.internalStoragePath
import com.goodwy.commons.extensions.sdCardPath
import com.goodwy.filemanager.extensions.config
import org.apache.ftpserver.ftplet.FileSystemFactory
import org.apache.ftpserver.ftplet.FileSystemView
import org.apache.ftpserver.ftplet.FtpFile
import org.apache.ftpserver.ftplet.User
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.util.Collections
import java.util.Locale

class VirtualFtpFileSystemFactory(
    private val context: Context
) : FileSystemFactory {
    override fun createFileSystemView(user: User): FileSystemView {
        val internalRoot = File(context.internalStoragePath)
        val sdCardPath = context.sdCardPath
        val sdCardRoot = sdCardPath
            .takeIf { it.isNotBlank() }
            ?.let { File(it) }
            ?.takeIf { it.exists() && it.canonicalPath != internalRoot.canonicalPath }

        val roots = listOf(
            VirtualFtpRoot(INTERNAL_STORAGE_NAME, internalRoot),
            VirtualFtpRoot(SD_CARD_NAME, sdCardRoot)
        )
        return VirtualFtpFileSystemView(context, roots)
    }

    companion object {
        const val INTERNAL_STORAGE_NAME = "Internal storage"
        const val SD_CARD_NAME = "SD card"
    }
}

private data class VirtualFtpRoot(
    val displayName: String,
    val physicalRoot: File?
)

private class VirtualFtpFileSystemView(
    private val context: Context,
    private val roots: List<VirtualFtpRoot>
) : FileSystemView {
    private var workingDirectory = "/"

    override fun getHomeDirectory(): FtpFile = getFile("/")

    override fun getWorkingDirectory(): FtpFile = getFile(workingDirectory)

    override fun changeWorkingDirectory(dir: String): Boolean {
        val file = getFile(dir)
        return if (file.doesExist() && file.isDirectory) {
            workingDirectory = file.absolutePath
            true
        } else {
            false
        }
    }

    override fun getFile(file: String): FtpFile {
        val virtualPath = normalizeVirtualPath(file, workingDirectory)
        if (virtualPath == "/") {
            return VirtualRootFtpFile(context, roots)
        }

        val segments = virtualPath.trim('/').split('/').filter { it.isNotEmpty() }
        val root = roots.firstOrNull { it.displayName == segments.firstOrNull() }
            ?: return MissingFtpFile(virtualPath)

        if (segments.size == 1) {
            return TopLevelRootFtpFile(context, root)
        }

        val physicalRoot = root.physicalRoot ?: return MissingFtpFile(virtualPath)
        val relativePath = segments.drop(1).joinToString(File.separator)
        val physicalFile = File(physicalRoot, relativePath)
        return if (isInsideRoot(physicalRoot, physicalFile)) {
            PhysicalFtpFile(context, root, physicalFile, virtualPath)
        } else {
            MissingFtpFile(virtualPath)
        }
    }

    override fun isRandomAccessible(): Boolean = true

    override fun dispose() = Unit
}

private class VirtualRootFtpFile(
    private val context: Context,
    private val roots: List<VirtualFtpRoot>
) : FtpFile {
    override fun getAbsolutePath() = "/"
    override fun getName() = "/"
    override fun isHidden() = false
    override fun isDirectory() = true
    override fun isFile() = false
    override fun doesExist() = true
    override fun isReadable() = true
    override fun isWritable() = false
    override fun isRemovable() = false
    override fun getOwnerName() = OWNER_NAME
    override fun getGroupName() = GROUP_NAME
    override fun getLinkCount() = roots.size
    override fun getLastModified() = System.currentTimeMillis()
    override fun setLastModified(time: Long) = false
    override fun getSize() = 0L
    override fun getPhysicalFile(): Any = "/"
    override fun mkdir() = false
    override fun delete() = false
    override fun move(destination: FtpFile?) = false

    override fun listFiles(): List<FtpFile> {
        return Collections.unmodifiableList(roots.map { TopLevelRootFtpFile(context, it) })
    }

    override fun createOutputStream(offset: Long): OutputStream {
        throw IOException("Cannot write to FTP root")
    }

    override fun createInputStream(offset: Long): InputStream {
        throw IOException("Cannot read FTP root as a file")
    }
}

private class TopLevelRootFtpFile(
    private val context: Context,
    private val root: VirtualFtpRoot
) : FtpFile {
    override fun getAbsolutePath() = "/${root.displayName}"
    override fun getName() = root.displayName
    override fun isHidden() = false
    override fun isDirectory() = true
    override fun isFile() = false
    override fun doesExist() = true
    override fun isReadable() = true
    override fun isWritable() = root.physicalRoot?.canWrite() == true
    override fun isRemovable() = false
    override fun getOwnerName() = OWNER_NAME
    override fun getGroupName() = GROUP_NAME
    override fun getLinkCount() = 1
    override fun getLastModified() = root.physicalRoot?.lastModified() ?: System.currentTimeMillis()
    override fun setLastModified(time: Long) = false
    override fun getSize() = 0L
    override fun getPhysicalFile(): Any = root.physicalRoot ?: root.displayName
    override fun mkdir() = false
    override fun delete() = false
    override fun move(destination: FtpFile?) = false

    override fun listFiles(): List<FtpFile> {
        val physicalRoot = root.physicalRoot ?: return emptyList()
        val children = physicalRoot.listFiles()?.toList().orEmpty()
        val showHidden = context.config.shouldShowHidden()
        return Collections.unmodifiableList(children
            .asSequence()
            .filter { showHidden || !it.isHiddenByName() }
            .sortedBy { it.name.lowercase(Locale.getDefault()) }
            .map { PhysicalFtpFile(context, root, it, "/${root.displayName}/${it.name}") }
            .toList())
    }

    override fun createOutputStream(offset: Long): OutputStream {
        throw IOException("Cannot write to virtual storage root")
    }

    override fun createInputStream(offset: Long): InputStream {
        throw IOException("Cannot read virtual storage root as a file")
    }
}

private class PhysicalFtpFile(
    private val context: Context,
    private val root: VirtualFtpRoot,
    private val file: File,
    private val virtualPath: String
) : FtpFile {
    override fun getAbsolutePath() = virtualPath
    override fun getName() = file.name
    override fun isHidden() = file.isHiddenByName()
    override fun isDirectory() = doesExist() && file.isDirectory
    override fun isFile() = doesExist() && file.isFile
    override fun doesExist() = file.exists() && canExposeFile()
    override fun isReadable() = file.canRead() && canExposeFile()
    override fun isWritable() = canExposeFile() && (file.canWrite() || parentCanWrite())
    override fun isRemovable() = canExposeFile() && file.exists() && file.canWrite()
    override fun getOwnerName() = OWNER_NAME
    override fun getGroupName() = GROUP_NAME
    override fun getLinkCount() = 1
    override fun getLastModified() = if (file.exists()) file.lastModified() else 0L
    override fun setLastModified(time: Long) = canExposeFile() && file.setLastModified(time)
    override fun getSize() = if (file.exists() && file.isFile) file.length() else 0L
    override fun getPhysicalFile(): Any = file
    override fun mkdir() = canExposeFile() && file.mkdirs()

    override fun delete(): Boolean {
        if (!canExposeFile() || !file.exists()) return false
        return if (file.isDirectory) file.deleteRecursively() else file.delete()
    }

    override fun move(destination: FtpFile?): Boolean {
        val destinationFile = (destination as? PhysicalFtpFile)?.file ?: return false
        if (!canExposeFile() || !destination.canExposeFile()) return false
        destinationFile.parentFile?.mkdirs()
        return file.renameTo(destinationFile)
    }

    override fun listFiles(): List<FtpFile>? {
        if (!doesExist() || !file.isDirectory) return null
        val showHidden = context.config.shouldShowHidden()
        val children = file.listFiles()?.toList().orEmpty()
        return Collections.unmodifiableList(children
            .asSequence()
            .filter { showHidden || !it.isHiddenByName() }
            .sortedBy { it.name.lowercase(Locale.getDefault()) }
            .map { PhysicalFtpFile(context, root, it, "$virtualPath/${it.name}") }
            .toList())
    }

    override fun createOutputStream(offset: Long): OutputStream {
        if (!canExposeFile()) throw IOException("Hidden files are not visible")
        file.parentFile?.mkdirs()
        return if (offset <= 0L) {
            FileOutputStream(file, false)
        } else {
            RandomAccessFileOutputStream(file, offset)
        }
    }

    override fun createInputStream(offset: Long): InputStream {
        if (!doesExist() || !file.isFile) throw IOException("File does not exist")
        return FileInputStream(file).apply {
            if (offset > 0L) skip(offset)
        }
    }

    private fun canExposeFile(): Boolean {
        val physicalRoot = root.physicalRoot ?: return false
        if (!isInsideRoot(physicalRoot, file)) return false
        return context.config.shouldShowHidden() || !file.hasHiddenPathComponent(physicalRoot)
    }

    private fun parentCanWrite(): Boolean {
        return file.parentFile?.canWrite() == true
    }
}

private class MissingFtpFile(
    private val virtualPath: String
) : FtpFile {
    override fun getAbsolutePath() = virtualPath
    override fun getName() = virtualPath.trimEnd('/').substringAfterLast('/').ifEmpty { "/" }
    override fun isHidden() = getName().startsWith('.')
    override fun isDirectory() = false
    override fun isFile() = false
    override fun doesExist() = false
    override fun isReadable() = false
    override fun isWritable() = false
    override fun isRemovable() = false
    override fun getOwnerName() = OWNER_NAME
    override fun getGroupName() = GROUP_NAME
    override fun getLinkCount() = 0
    override fun getLastModified() = 0L
    override fun setLastModified(time: Long) = false
    override fun getSize() = 0L
    override fun getPhysicalFile(): Any = virtualPath
    override fun mkdir() = false
    override fun delete() = false
    override fun move(destination: FtpFile?) = false
    override fun listFiles(): List<FtpFile>? = null
    override fun createOutputStream(offset: Long): OutputStream {
        throw IOException("File does not exist")
    }
    override fun createInputStream(offset: Long): InputStream {
        throw IOException("File does not exist")
    }
}

private class RandomAccessFileOutputStream(
    file: File,
    offset: Long
) : OutputStream() {
    private val randomAccessFile = RandomAccessFile(file, "rw").apply { seek(offset) }

    override fun write(oneByte: Int) {
        randomAccessFile.write(oneByte)
    }

    override fun write(buffer: ByteArray, offset: Int, count: Int) {
        randomAccessFile.write(buffer, offset, count)
    }

    override fun close() {
        randomAccessFile.close()
    }
}

private fun normalizeVirtualPath(inputPath: String, workingDirectory: String): String {
    val combinedPath = if (inputPath.startsWith('/')) {
        inputPath
    } else {
        "$workingDirectory/$inputPath"
    }

    val parts = ArrayDeque<String>()
    combinedPath.split('/').forEach { part ->
        when {
            part.isEmpty() || part == "." -> Unit
            part == ".." -> if (parts.isNotEmpty()) parts.removeLast()
            else -> parts.addLast(part)
        }
    }

    return "/" + parts.joinToString("/")
}

private fun isInsideRoot(root: File, file: File): Boolean {
    return try {
        val rootPath = root.canonicalPath.trimEnd(File.separatorChar)
        val filePath = file.canonicalPath
        filePath == rootPath || filePath.startsWith(rootPath + File.separator)
    } catch (_: IOException) {
        false
    }
}

private fun File.isHiddenByName(): Boolean = name.startsWith('.') || isHidden

private fun File.hasHiddenPathComponent(root: File): Boolean {
    return try {
        val rootPath = root.canonicalPath.trimEnd(File.separatorChar)
        val filePath = canonicalPath
        val relativePath = filePath.removePrefix(rootPath).trimStart(File.separatorChar)
        relativePath.split(File.separatorChar).any { it.startsWith('.') }
    } catch (_: IOException) {
        isHiddenByName()
    }
}

private const val OWNER_NAME = "owner"
private const val GROUP_NAME = "group"
