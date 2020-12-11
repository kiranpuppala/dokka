package content.annotations

import org.jetbrains.dokka.base.testApi.testRunner.BaseAbstractTest
import org.jetbrains.dokka.links.DRI
import org.jetbrains.dokka.model.AnnotationScope
import org.jetbrains.dokka.model.Annotations
import org.jetbrains.dokka.model.StringValue
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class JvmNameTest : BaseAbstractTest() {
    private val testConfiguration = dokkaConfiguration {
        sourceSets {
            sourceSet {
                sourceRoots = listOf("src/")
                analysisPlatform = "jvm"
                classpath += jvmStdlibPath!!
            }
        }
    }

    @Test
    fun `jvm name should be included in functions extra`(){
        testInline(
            """
            |/src/main/kotlin/test/source.kt
            |@file:JvmName("CustomJvmName")
            |package test
            |
            |fun function(abc: String): String {
            |    return "Hello, " + abc
            |}
        """.trimIndent(), testConfiguration) {
            documentablesCreationStage = { modules ->
                val expectedAnnotation = Annotations.Annotation(
                    dri = DRI("kotlin.jvm", "JvmName"),
                    params = mapOf("name" to StringValue("CustomJvmName")),
                    scope = AnnotationScope.FILE,
                    mustBeDocumented = true
                )
                val function = modules.flatMap { it.packages }.first().functions.first()
                assertEquals(emptyMap(), function.extra[Annotations]?.directAnnotations)
                assertEquals(listOf(expectedAnnotation), function.extra[Annotations]?.fileLevelAnnotations?.entries?.first()?.value)
            }
        }
    }
}