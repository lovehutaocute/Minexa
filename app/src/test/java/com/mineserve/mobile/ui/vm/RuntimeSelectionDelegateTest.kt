package com.mineserve.mobile.ui.vm

import com.mineserve.mobile.data.JavaRuntime
import com.mineserve.mobile.data.JavaVendor
import com.mineserve.mobile.data.JavaVersion
import com.mineserve.mobile.data.McConfig
import com.mineserve.mobile.data.PhpVersion
import com.mineserve.mobile.data.ProotDistro
import com.mineserve.mobile.data.RuntimeKind
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Test

class RuntimeSelectionDelegateTest {
    @Test
    fun applyRuntimeSelectionUpdatesTheWholeRuntimeInOneConfigWrite() {
        val config = MutableStateFlow(McConfig())
        var writes = 0
        val delegate = RuntimeSelectionDelegate(
            config = config,
            updateConfig = { transform ->
                writes++
                config.value = transform(config.value)
            },
            formatMessage = { _, _ -> "" }
        )

        delegate.applyRuntimeSelection(
            kind = RuntimeKind.Php,
            javaRuntime = JavaRuntime(JavaVendor.Fcl, JavaVersion.Java21),
            phpVersion = PhpVersion.Default,
            prootDistro = ProotDistro.Focal
        )

        assertEquals(1, writes)
        assertEquals(RuntimeKind.Php, config.value.runtimeKind)
        assertEquals(JavaVendor.Fcl, config.value.selectedJavaVendor)
        assertEquals(JavaVersion.Java21, config.value.selectedJavaVersion)
        assertEquals(PhpVersion.Default, config.value.selectedPhpVersion)
        assertEquals(ProotDistro.Focal, config.value.prootDistro)
    }

    @Test
    fun javaRuntimeSelectionRejectsVersionsUnavailableInCurrentDistro() {
        val config = MutableStateFlow(McConfig(prootDistro = ProotDistro.Jammy))
        val delegate = RuntimeSelectionDelegate(
            config = config,
            updateConfig = { transform -> config.value = transform(config.value) },
            formatMessage = { _, _ -> "" }
        )

        delegate.setJavaRuntime(JavaRuntime(JavaVendor.Proot, JavaVersion.Java8))

        assertEquals(JavaVersion.Java11, config.value.selectedJavaVersion)
    }
}
