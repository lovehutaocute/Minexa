package com.mineserve.mobile.ui.vm

import android.content.Context
import com.mineserve.mobile.runtime.NativeLibraryBundler
import java.io.File

internal class RuntimeEnvironmentDelegate(
    private val app: Context
) {
    val ipv6CompatReady: Boolean
        get() = File(
            app.applicationInfo.nativeLibraryDir,
            NativeLibraryBundler.IPV6_FIX_LIB
        ).isFile
}
