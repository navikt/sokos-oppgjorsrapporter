package no.nav.sokos.oppgjorsrapporter.util

import java.time.LocalDate
import java.time.temporal.TemporalAdjusters.firstDayOfYear
import java.time.temporal.TemporalAdjusters.lastDayOfYear
import org.threeten.extra.LocalDateRange

fun heltAarDateRange(aar: Int): LocalDateRange =
    LocalDate.ofYearDay(aar, 1).let { dato -> LocalDateRange.ofClosed(dato.with(firstDayOfYear()), dato.with(lastDayOfYear())) }
