package org.jetbrains.dokka.allModulesPage

import org.jetbrains.dokka.plugability.ConfigurableBlock
import java.io.File

data class MultimoduleConfiguration(
    var olderVersions: File? = defaultOlderVersions,
    var currentVersion: String = defaultVersion,
) : ConfigurableBlock {
    companion object {
        val defaultOlderVersions: File? = null
        const val defaultVersion = "latest"
    }
}