package org.jetbrains.dokka.base

import org.jetbrains.dokka.DokkaConfiguration
import org.jetbrains.dokka.DokkaException
import org.jetbrains.dokka.Platform
import org.jetbrains.dokka.base.analysis.AnalysisEnvironment
import org.jetbrains.dokka.base.analysis.DokkaResolutionFacade
import org.jetbrains.dokka.model.DModule
import org.jetbrains.dokka.model.SourceSetCache
import org.jetbrains.dokka.model.SourceSetData
import org.jetbrains.dokka.pages.RootPageNode
import org.jetbrains.dokka.base.plugability.DokkaContext
import org.jetbrains.dokka.base.plugability.DokkaPlugin
import org.jetbrains.dokka.utilities.DokkaLogger
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageLocation
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.common.messages.MessageRenderer
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.utils.PathUtil
import java.io.File

/**
 * DokkaGenerator is the main entry point for generating documentation
 * [generate] method has been split into submethods for test reasons
 */
class DokkaGenerator(
    private val configuration: DokkaConfiguration,
    private val logger: DokkaLogger
) {
    fun generate() = timed {
        report("Setting up analysis environments")
        val sourceSetsCache = SourceSetCache()
        val sourceSets: Map<SourceSetData, EnvironmentAndFacade> = setUpAnalysis(configuration, sourceSetsCache)

        report("Initializing plugins")
        val context = initializePlugins(configuration, logger, sourceSets, sourceSetsCache)

        report("Creating documentation models")
        val modulesFromPlatforms = createDocumentationModels(sourceSets, context)

        report("Transforming documentation model before merging")
        val transformedDocumentationBeforeMerge = transformDocumentationModelBeforeMerge(modulesFromPlatforms, context)

        report("Merging documentation models")
        val documentationModel = mergeDocumentationModels(transformedDocumentationBeforeMerge, context)

        report("Transforming documentation model after merging")
        val transformedDocumentation = transformDocumentationModelAfterMerge(documentationModel, context)

        report("Creating pages")
        val pages = createPages(transformedDocumentation, context)

        report("Transforming pages")
        val transformedPages = transformPages(pages, context)

        report("Rendering")
        render(transformedPages, context)

        reportAfterRendering(context)
    }.dump("\n\n === TIME MEASUREMENT ===\n")

    fun generateAllModulesPage() = timed {
        val sourceSetsCache = SourceSetCache()
        val sourceSets = emptyMap<SourceSetData, EnvironmentAndFacade>()
        report("Initializing plugins")
        val context = initializePlugins(configuration, logger, sourceSets, sourceSetsCache)

        report("Creating all modules page")
        val pages = createAllModulePage(context)

        report("Transforming pages")
        val transformedPages = transformAllModulesPage(pages, context)

        report("Rendering")
        render(transformedPages, context)
    }.dump("\n\n === TIME MEASUREMENT ===\n")

    fun setUpAnalysis(
        configuration: DokkaConfiguration,
        sourceSetsCache: SourceSetCache
    ): Map<SourceSetData, EnvironmentAndFacade> =
        configuration.passesConfigurations.map {
            sourceSetsCache.getSourceSet(it) to createEnvironmentAndFacade(configuration, it)
        }.toMap()

    fun initializePlugins(
        configuration: DokkaConfiguration,
        logger: DokkaLogger,
        sourceSets: Map<SourceSetData, EnvironmentAndFacade>,
        sourceSetsCache: SourceSetCache,
        pluginOverrides: List<DokkaPlugin> = emptyList()
    ) = DokkaContext.create(configuration, logger, sourceSets, sourceSetsCache, pluginOverrides)

    fun createDocumentationModels(
        platforms: Map<SourceSetData, EnvironmentAndFacade>,
        context: DokkaContext
    ) = platforms.flatMap { (pdata, _) -> translateSources(pdata, context) }

    fun transformDocumentationModelBeforeMerge(
        modulesFromPlatforms: List<DModule>,
        context: DokkaContext
    ) = context[CoreExtensions.preMergeDocumentableTransformer].fold(modulesFromPlatforms) { acc, t -> t(acc) }

    fun mergeDocumentationModels(
        modulesFromPlatforms: List<DModule>,
        context: DokkaContext
    ) = context.single(CoreExtensions.documentableMerger).invoke(modulesFromPlatforms, context)

    fun transformDocumentationModelAfterMerge(
        documentationModel: DModule,
        context: DokkaContext
    ) = context[CoreExtensions.documentableTransformer].fold(documentationModel) { acc, t -> t(acc, context) }

    fun createPages(
        transformedDocumentation: DModule,
        context: DokkaContext
    ) = context.single(CoreExtensions.documentableToPageTranslator).invoke(transformedDocumentation)

    fun createAllModulePage(
        context: DokkaContext
    ) = context.single(CoreExtensions.allModulePageCreator).invoke()

    fun transformPages(
        pages: RootPageNode,
        context: DokkaContext
    ) = context[CoreExtensions.pageTransformer].fold(pages) { acc, t -> t(acc) }

    fun transformAllModulesPage(
        pages: RootPageNode,
        context: DokkaContext
    ) = context[CoreExtensions.allModulePageTransformer].fold(pages) { acc, t -> t(acc) }

    fun render(
        transformedPages: RootPageNode,
        context: DokkaContext
    ) {
        val renderer = context.single(CoreExtensions.renderer)
        renderer.render(transformedPages)
    }

    fun reportAfterRendering(context: DokkaContext) {
        context.unusedPoints.takeIf { it.isNotEmpty() }?.also {
            logger.warn("Unused extension points found: ${it.joinToString(", ")}")
        }

        logger.report()

        if (context.configuration.failOnWarning && (logger.warningsCount > 0 || logger.errorsCount > 0)) {
            throw DokkaException(
                "Failed with warningCount=${logger.warningsCount} and errorCount=${logger.errorsCount}"
            )
        }
    }

    private fun createEnvironmentAndFacade(
        configuration: DokkaConfiguration,
        pass: DokkaConfiguration.PassConfiguration
    ): EnvironmentAndFacade =
        AnalysisEnvironment(
            DokkaMessageCollector(logger),
            pass.analysisPlatform
        ).run {
            if (analysisPlatform == Platform.jvm) {
                addClasspath(PathUtil.getJdkClassesRootsFromCurrentJre())
            }
            pass.classpath.forEach { addClasspath(File(it)) }

            addSources(
                (pass.sourceRoots + configuration.passesConfigurations.filter { it.sourceSetID in pass.dependentSourceSets }
                    .flatMap { it.sourceRoots })
                    .map { it.path }
            )

            loadLanguageVersionSettings(pass.languageVersion, pass.apiVersion)

            val environment = createCoreEnvironment()
            val (facade, _) = createResolutionFacade(environment)
            EnvironmentAndFacade(environment, facade)
        }

    private fun translateSources(platformData: SourceSetData, context: DokkaContext) =
        context[CoreExtensions.sourceToDocumentableTranslator].map {
            it.invoke(platformData, context)
        }

    class DokkaMessageCollector(private val logger: DokkaLogger) : MessageCollector {
        override fun clear() {
            seenErrors = false
        }

        private var seenErrors = false

        override fun report(severity: CompilerMessageSeverity, message: String, location: CompilerMessageLocation?) {
            if (severity == CompilerMessageSeverity.ERROR) {
                seenErrors = true
            }
            logger.info(MessageRenderer.PLAIN_FULL_PATHS.render(severity, message, location))
        }

        override fun hasErrors() = seenErrors
    }
}

// It is not data class due to ill-defined equals
class EnvironmentAndFacade(val environment: KotlinCoreEnvironment, val facade: DokkaResolutionFacade) {
    operator fun component1() = environment
    operator fun component2() = facade
}

private class Timer(startTime: Long, private val logger: DokkaLogger?) {
    private val steps = mutableListOf("" to startTime)

    fun report(name: String) {
        logger?.progress(name)
        steps += (name to System.currentTimeMillis())
    }

    fun dump(prefix: String = "") {
        println(prefix)
        val namePad = steps.map { it.first.length }.max() ?: 0
        val timePad = steps.windowed(2).map { (p1, p2) -> p2.second - p1.second }.max()?.toString()?.length ?: 0
        steps.windowed(2).forEach { (p1, p2) ->
            if (p1.first.isNotBlank()) {
                println("${p1.first.padStart(namePad)}: ${(p2.second - p1.second).toString().padStart(timePad)}")
            }
        }
    }
}

private fun timed(logger: DokkaLogger? = null, block: Timer.() -> Unit): Timer =
    Timer(System.currentTimeMillis(), logger).apply {
        try {
            block()
        } finally {
            report("")
        }
    }