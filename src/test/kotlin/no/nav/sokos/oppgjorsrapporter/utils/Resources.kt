package no.nav.sokos.oppgjorsrapporter.utils

import kotlin.io.readText
import org.apache.commons.io.input.XmlStreamReader

fun xmlResourceAsString(name: String): String =
    XmlStreamReader.builder().apply { inputStream = javaClass.classLoader.getResourceAsStream(name)!! }.get().readText()
