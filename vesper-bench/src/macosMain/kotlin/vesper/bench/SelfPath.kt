package vesper.bench

import platform.Foundation.NSBundle

internal actual fun selfPath(): String? = NSBundle.mainBundle.executablePath
