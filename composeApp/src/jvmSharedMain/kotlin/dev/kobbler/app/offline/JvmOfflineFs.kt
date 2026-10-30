package dev.kobbler.app.offline

import java.io.File
import java.io.RandomAccessFile

class JvmOfflineFs(private val root: File) : OfflineFs {
    private fun file(path: String) = File(root, path)

    override fun read(path: String): ByteArray? = file(path).takeIf { it.isFile }?.readBytes()

    override fun write(path: String, bytes: ByteArray) {
        val target = file(path).also { it.parentFile?.mkdirs() }
        val partial = File(target.parentFile, "${target.name}.part")
        partial.writeBytes(bytes)
        if (!partial.renameTo(target)) {
            target.delete()
            partial.renameTo(target)
        }
    }

    override fun open(path: String): RandomFile {
        val raf = RandomAccessFile(file(path).also { it.parentFile?.mkdirs() }, "rw")
        return object : RandomFile {
            override fun write(offset: Long, bytes: ByteArray, length: Int) = synchronized(raf) {
                raf.seek(offset)
                raf.write(bytes, 0, length)
            }

            override fun read(offset: Long, into: ByteArray, length: Int): Int = synchronized(raf) {
                raf.seek(offset)
                raf.read(into, 0, length)
            }

            override fun close() = raf.close()
        }
    }

    override fun delete(path: String) {
        file(path).deleteRecursively()
    }

    override fun absolute(path: String): String = file(path).absolutePath

    override fun usedBytes(): Long = root.walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    override fun freeBytes(): Long = root.also { it.mkdirs() }.usableSpace
}

actual fun platformOfflineFs(): OfflineFs? = offlineRootDir()?.let { JvmOfflineFs(File(it)) }
