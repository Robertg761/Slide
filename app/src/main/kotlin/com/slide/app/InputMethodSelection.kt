package com.slide.app

/** Android stores the selected input method as a flattened package/class component. */
internal fun inputMethodBelongsToPackage(inputMethodId: String?, packageName: String): Boolean =
    inputMethodId?.startsWith("$packageName/") == true
