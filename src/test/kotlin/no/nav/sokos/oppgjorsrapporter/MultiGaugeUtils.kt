package no.nav.sokos.oppgjorsrapporter

import io.micrometer.core.instrument.Tags
import mu.KLogger
import mu.KotlinLogging

object MultiGaugeUtils {
    fun Iterable<Pair<Tags, Long>>.asMap(): Map<Map<String, String>, Long> =
        this.map { (tags, v) -> tags.map { tag -> tag.key to tag.value }.toMap() to v }
            .toMap()
            .also {
                val logger: KLogger = KotlinLogging.logger {}
                logger.debug { "tags-as-map: ${it.toList().sortedBy { it.second }.joinToString("\n")}" }
            }

    fun Iterable<Pair<Tags, Long>>.extract(vararg wantedTags: Pair<String, String>): List<Long> =
        asMap().filter { (tags, _) -> wantedTags.all { w -> tags.any { t -> t.key == w.first && t.value == w.second } } }.values.toList()
}
