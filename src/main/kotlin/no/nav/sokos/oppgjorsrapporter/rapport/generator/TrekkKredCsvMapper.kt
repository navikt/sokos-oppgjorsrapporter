package no.nav.sokos.oppgjorsrapporter.rapport.generator

import java.math.BigDecimal
import java.time.format.DateTimeFormatter
import no.nav.sokos.oppgjorsrapporter.mq.TrekkKredRapportBestilling
import no.nav.sokos.oppgjorsrapporter.rapport.generator.CsvGenerering.LINJESKIFT

private val dateFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")

private fun BigDecimal.formater() = toString().let { it.dropLast(3) + it.substring(it.length - 2) }

fun TrekkKredRapportBestilling.toCsv(): String {
    val headerLinje =
        listOf<String>(
                "H",
                brukerData.brevinfo.brevdato.format(dateFormatter),
                brukerData.mottaker.navn.fulltNavn,
                dato.format(dateFormatter),
                brukerData.brevinfo.variableFelter.ur.orgnummer.raw,
                " ",
                brukerData.brevinfo.variableFelter.ur.kontonummer?.raw ?: "",
                brukerData.brevinfo.variableFelter.ur.tssId,
                brukerData.brevinfo.variableFelter.ur.rapportFom.format(dateFormatter),
                brukerData.brevinfo.variableFelter.ur.rapportTom.format(dateFormatter),
            )
            .joinToString(";")

    val trekklinjer =
        with(brukerData.brevinfo.variableFelter.ur) {
            arkivRefList.flatMap { arkivRef ->
                val sumArkivref = listOf<String>("A", arkivRef.delsumRef.belop.formater()).joinToString(";")

                arkivRef.enhetList.flatMap { enhet ->
                    val sumEnhet = listOf<String>("D", enhet.delsum.belop.formater()).joinToString(";")

                    enhet.trekkLinjeList.map {
                        listOf<String>(
                                "E",
                                arkivRef.nr,
                                enhet.enhetnr,
                                enhet.navn,
                                it.saksreferanse,
                                it.arbeidgiverOrgnr.raw,
                                it.fnr.raw,
                                it.navn,
                                it.trekkFOM.format(dateFormatter),
                                it.trekkTOM.format(dateFormatter),
                                it.belop.formater(),
                                it.kidStatus,
                            )
                            .joinToString(";")
                    } + sumEnhet
                } + sumArkivref
            }
        }

    val sumKreditor = listOf<String>("T", brukerData.brevinfo.variableFelter.ur.sumTotal.belop.formater()).joinToString(";")

    return listOf(headerLinje, *trekklinjer.toTypedArray(), sumKreditor).joinToString(LINJESKIFT, postfix = LINJESKIFT)
}
