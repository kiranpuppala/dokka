package org.jetbrains.dokka.templates

import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.dokka.base.templating.Command
import org.jetbrains.dokka.base.templating.parseJson
import org.jetbrains.dokka.plugability.DokkaContext
import org.jetbrains.dokka.plugability.plugin
import org.jetbrains.dokka.plugability.query
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.io.File
import java.nio.file.Files

class DirectiveBasedHtmlTemplateProcessingStrategy(private val context: DokkaContext): TemplateProcessingStrategy {

    private val directiveBasedCommandHandlers = context.plugin<TemplatingPlugin>().query { directiveBasedCommandHandlers }

    override suspend fun process(input: File, output: File): Boolean = coroutineScope {
        if (input.extension == "html") {
            launch {
                val document = withContext(IO) { Jsoup.parse(input, "UTF-8") }
                document.outputSettings().indentAmount(0).prettyPrint(false)
                document.select("dokka-template-command").forEach {
                    handleCommand(it, parseJson(it.attr("data")), input, output)
                }
                withContext(IO) { Files.write(output.toPath(), listOf(document.outerHtml())) }
            }
            true
        } else false
    }

    fun handleCommand(element: Element, command: Command, input: File, output: File) {
        val handlers = directiveBasedCommandHandlers.filter { it.canHandle(command) }
        if(handlers.isEmpty())
            context.logger.warn("Unknown templating command $command")
        else
            handlers.forEach { it.handleCommand(element, command, input, output) }

    }

    override suspend fun finish(output: File) {
    }
}
