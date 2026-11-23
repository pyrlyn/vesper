package vesper.ucd

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/** Writes vesper-unicode's generated sources, or with [check] fails when the committed ones are stale. */
@DisableCachingByDefault(because = "Writes or compares committed sources; the downloads are cached by the task itself")
public abstract class UcdTask : DefaultTask() {
    @get:Input
    public abstract val check: Property<Boolean>

    /** Where the pinned data files are kept between runs. */
    @get:Internal
    public abstract val cacheDir: DirectoryProperty

    /** The vesper-unicode module the outputs belong to. */
    @get:Internal
    public abstract val moduleDir: DirectoryProperty

    @TaskAction
    public fun run() {
        val cache = cacheDir.get().asFile.resolve(UNICODE_VERSION)
        Ucd.fetch(cache)
        val module = moduleDir.get().asFile
        val stale =
            Ucd.render(cache).filter { output ->
                val dest = module.resolve(output.path)
                if (check.get()) {
                    !dest.exists() || dest.readText() != output.text
                } else {
                    dest.parentFile.mkdirs()
                    dest.writeText(output.text)
                    false
                }
            }
        if (stale.isNotEmpty()) {
            throw GradleException("stale generated files ${stale.map { it.path }}; run ./gradlew :vesper-unicode:generateUcd")
        }
    }
}
