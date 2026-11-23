package vesper.unicode

/**
 * The Unicode Character Database version the tables come from.
 *
 * Only the latest version ships today. Older versions are added as further entries with their own
 * generated tables, so an application can keep the widths its users' other tools still assume.
 */
public enum class UnicodeVersion(
    public val major: Int,
    public val minor: Int,
    public val update: Int,
) {
    /** Unicode 18.0.0. */
    V18(UNICODE_MAJOR, UNICODE_MINOR, UNICODE_UPDATE),
    ;

    public companion object {
        /** The newest version this module knows. */
        public val LATEST: UnicodeVersion = V18
    }
}
