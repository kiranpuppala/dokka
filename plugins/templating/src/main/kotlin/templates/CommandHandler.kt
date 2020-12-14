package org.jetbrains.dokka.templates

import org.jetbrains.dokka.base.templating.Command
import org.jsoup.nodes.Element
import java.io.File

abstract class CommandHandler() {
    abstract fun handleCommand(element: Element, command: Command, input: File, output: File)
    abstract fun canHandle(command: Command): Boolean
    open suspend fun finish(output: File) {}
}