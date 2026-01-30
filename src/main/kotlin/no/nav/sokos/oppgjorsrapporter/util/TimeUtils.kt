package no.nav.sokos.oppgjorsrapporter.util

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters.firstDayOfYear
import java.time.temporal.TemporalAdjusters.lastDayOfYear
import org.threeten.extra.LocalDateRange

fun heltAarDateRange(aar: Int): LocalDateRange =
    LocalDate.ofYearDay(aar, 1).let { dato -> LocalDateRange.ofClosed(dato.with(firstDayOfYear()), dato.with(lastDayOfYear())) }

private val norskDatoFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")

fun LocalDate.tilNorskFormat(): String = format(norskDatoFormatter)

fun String.fraNorskFormat(): LocalDate = LocalDate.parse(this, norskDatoFormatter)
