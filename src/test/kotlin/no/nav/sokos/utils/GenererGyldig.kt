package no.nav.sokos.utils

import java.time.LocalDate
import java.time.Year
import java.time.format.DateTimeFormatter
import kotlin.random.Random

private fun <T> genererMedKontrollsiffer(ctor: (String) -> T, genererBase: () -> List<Int>, vararg sifferVekter: List<Int>): T {
    while (true) {
        val base = genererBase()
        val sifre =
            sifferVekter.fold(base) { acc, v ->
                val k = mod11(acc, v)
                acc + k
            }
        if (10 !in sifre) {
            try {
                return ctor(sifre.joinToString(""))
            } catch (_: Exception) {
                //
            }
        }
    }
}

private fun Random.genDigitList(length: Int): () -> List<Int> = { List(length) { nextInt(10) } }

fun Bankkonto.Companion.genererGyldig(random: Random = Random.Default): Bankkonto.Validert =
    genererMedKontrollsiffer(Bankkonto::Validert, random.genDigitList(10), Bankkonto.Validert.sifferVekter)

enum class TestPerson {
    NAV,
    TEST_NORGE,
}

private fun List<Int>.justerForTestPerson(t: TestPerson?): List<Int> =
    if (t == null) {
        this
    } else {
        take(2) +
            (get(2) +
                when (t) {
                    TestPerson.NAV -> 4
                    TestPerson.TEST_NORGE -> 8
                }) +
            drop(3)
    }

private val foedselsdatoFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("ddMMyy")

fun Fnr.Companion.genererGyldig(forTestPerson: TestPerson? = null, random: Random = Random.Default): Fnr.Validert {
    val baseGen: () -> List<Int> = {
        val dato =
            LocalDate.ofYearDay(random.nextInt(1900, Year.now().value), random.nextInt(1, 366)).format(foedselsdatoFormatter).map {
                it.digitToInt()
            }

        val individSiffer = List(3) { random.nextInt(10) }

        dato.justerForTestPerson(forTestPerson).plus(individSiffer)
    }

    return genererMedKontrollsiffer(Fnr::Validert, baseGen, Fnr.Validert.sifferVekter1, Fnr.Validert.sifferVekter2)
}

fun OrgNr.Companion.genererGyldig(random: Random = Random.Default): OrgNr.Validert =
    genererMedKontrollsiffer(OrgNr::Validert, random.genDigitList(8), OrgNr.Validert.sifferVekter)
