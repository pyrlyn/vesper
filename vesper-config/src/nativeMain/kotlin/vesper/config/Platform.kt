package vesper.config

import kotlin.experimental.ExperimentalNativeApi

@OptIn(ExperimentalNativeApi::class)
internal actual val isMacOs: Boolean = Platform.osFamily == OsFamily.MACOSX
