package org.jetbrains.dokka.allModulesPage.versioning

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.*
import kotlinx.coroutines.Dispatchers.IO
import org.jetbrains.dokka.allModulesPage.AllModulesPagePlugin
import org.jetbrains.dokka.allModulesPage.MultimoduleConfiguration
import org.jetbrains.dokka.allModulesPage.templates.TemplateProcessingStrategy
import org.jetbrains.dokka.plugability.*
import java.io.File
import java.nio.file.Path

interface VersioningHandler : () -> Unit {
    fun getVersions(): Map<String, Path>
    fun currentVersion(): Path?
}

class DefaultVersioningHandler(val context: DokkaContext) : VersioningHandler {

    private val mapper = ObjectMapper()

    private val processingStrategies: List<TemplateProcessingStrategy> = context.plugin<AllModulesPagePlugin>().query { templateProcessingStrategy }

    private var versions = mutableMapOf<String, Path>()

    private var configuration = configuration<AllModulesPagePlugin, MultimoduleConfiguration>(context)

    override fun getVersions() = versions

    override fun currentVersion() = configuration?.let { versionsConfiguration ->
        versions[versionsConfiguration.currentVersion]
    }

    override fun invoke() {
        versions = mutableMapOf()
        configuration?.let { versionsConfiguration ->
            val output = context.configuration.outputDir
            val version = versionsConfiguration.currentVersion
            versions[version] = output.toPath().relativize(output.toPath())
            versionsConfiguration.olderVersions?.let {
                handlePreviousVersions(it, output)
            }
            mapper.writeValue(output.resolve(VERSIONS_FILE), Version(version))
        }
    }

    private fun handlePreviousVersions(olderDokkaPath: File, output: File) {
        assert(olderDokkaPath.isDirectory) { "Supplied previous version $olderDokkaPath is not a directory!" }
        val children = olderDokkaPath.list().orEmpty().map { File(it) }
        val oldVersion = children.first { it.name == VERSIONS_FILE }.let { file ->
            mapper.readValue(file, Version::class.java)
        }
        val olderVersionsDir = output.resolve(OLDER_VERSIONS_DIR).apply { mkdir() }
        val previousVersionDir = olderVersionsDir.resolve(oldVersion.current).apply { mkdir() }
        versions[oldVersion.current] = output.toPath().relativize(previousVersionDir.toPath())
        runBlocking(Dispatchers.Default) {
            coroutineScope {
                children.forEach { file ->
                    launch {
                        when (file.name) {
                            OLDER_VERSIONS_DIR -> file.list().orEmpty().forEach {
                                val target = olderVersionsDir.resolve(it)
                                withContext(IO) { file.resolve(it).copyRecursively(target) }
                                versions[it] = output.toPath().relativize(target.toPath())
                            }
                            VERSIONS_FILE -> Unit
                            else ->
                                if (file.isDirectory) file.copyRecursively(previousVersionDir.resolve(file.name))
                                else processingStrategies.asSequence().first {
                                    it.process(file, previousVersionDir.resolve(file.name))
                                }
                        }
                    }
                }
            }
        }
    }

    private data class Version(val current: String)

    companion object {
        private const val OLDER_VERSIONS_DIR = "older"
        private const val VERSIONS_FILE = "version.json"
    }
}