package vesper.config

/**
 * Why the config file could not be used. The message always starts with the file, because the
 * host shows it to whoever is editing that file.
 */
public sealed class ConfigException(
    public val path: String,
    detail: String,
    cause: Throwable? = null,
) : Exception("$path: $detail", cause) {
    /** The file exists but could not be read. */
    public class Io(
        path: String,
        detail: String,
        cause: Throwable? = null,
    ) : ConfigException(path, detail, cause)

    /** The file is larger than [MAX_FILE_BYTES]. */
    public class TooLarge(
        path: String,
    ) : ConfigException(path, "larger than $MAX_FILE_BYTES bytes")

    /** The text is not UTF-8 TOML of the shape `docs/config.schema.json` describes. */
    public class Invalid(
        path: String,
        detail: String,
    ) : ConfigException(path, detail)
}
