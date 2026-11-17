package app.vesper.platform

import app.vesper.app.SyncStore
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit
import javax.swing.JFileChooser

/** Folder sync on the local file system; the folder is usually one iCloud Drive, Dropbox or a network share keeps in step. */
internal class FolderSync : SyncStore {
    override fun pickFolder(): String? = when (HostOs.current) {
        HostOs.MacOS -> {
            // The native panel picks folders when this property is set while it opens.
            System.setProperty("apple.awt.fileDialogForDirectories", "true")
            try {
                val dialog = FileDialog(null as Frame?, "Choose a folder to sync through", FileDialog.LOAD)
                dialog.directory = Paths.home.path
                dialog.isVisible = true
                val dir = dialog.directory
                val name = dialog.file
                if (dir == null || name == null) null else File(dir, name).takeIf { it.isDirectory }?.path
            } finally {
                System.setProperty("apple.awt.fileDialogForDirectories", "false")
            }
        }
        else -> {
            val chooser = JFileChooser(Paths.home).apply {
                dialogTitle = "Choose a folder to sync through"
                fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            }
            if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile?.takeIf { it.isDirectory }?.path else null
        }
    }

    override fun read(path: String): String? = runCatching { File(path).takeIf { it.isFile }?.readText() }.getOrNull()

    override fun write(path: String, content: String): Boolean = runCatching {
        val file = File(path)
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, ".${file.name}.tmp")
        tmp.writeText(content)
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        true
    }.getOrDefault(false)

    override fun list(dir: String): List<String> = File(dir).list()?.toList() ?: emptyList()

    override fun delete(path: String): Boolean = runCatching { File(path).delete() }.getOrDefault(false)

    override fun modified(path: String): Long? = File(path).takeIf { it.isFile }?.lastModified()

    override val deviceName: String by lazy {
        val mac = if (HostOs.current == HostOs.MacOS) computerName() else null
        mac ?: runCatching { InetAddress.getLocalHost().hostName.substringBefore('.') }.getOrNull()?.takeIf { it.isNotBlank() } ?: "This computer"
    }

    /** The name set in System Settings › General › Sharing. */
    private fun computerName(): String? = runCatching {
        val p = ProcessBuilder("/usr/sbin/scutil", "--get", "ComputerName").redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val out = p.inputStream.bufferedReader().readText().trim()
        if (p.waitFor(3, TimeUnit.SECONDS) && p.exitValue() == 0) out.takeIf { it.isNotEmpty() } else null
    }.getOrNull()
}
