package no.nav.sokos.oppgjorsrapporter.rapport.generator

import java.math.BigDecimal
import java.time.format.DateTimeFormatter
import no.nav.sokos.oppgjorsrapporter.mq.TrekkKredRapportBestilling
import no.nav.sokos.oppgjorsrapporter.rapport.generator.CsvGenerering.LINJESKIFT

private val dateFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")

private fun BigDecimal.formater() = toString().let { it.dropLast(3) + it.substring(it.length - 2) }

fun TrekkKredRapportBestilling.toCsvV1(): String {
    val headerLinje =
        "H;" +
            "${brukerData.brevinfo.brevdato.format(dateFormatter)};" +
            "${brukerData.mottaker.navn.fulltNavn};" +
            "${dato.format(dateFormatter)};" +
            "${brukerData.brevinfo.variableFelter.ur.orgnummer};" +
            " ;" +
            "${brukerData.brevinfo.variableFelter.ur.kontonummer};" +
            "${brukerData.brevinfo.variableFelter.ur.tssId};" +
            "${brukerData.brevinfo.variableFelter.ur.rapportFom.format(dateFormatter)};" +
            "${brukerData.brevinfo.variableFelter.ur.rapportTom.format(dateFormatter)};"
    val trekklinjer =
        with(brukerData.brevinfo.variableFelter.ur) {
                arkivRefList.flatMap { arkivRef ->
                    arkivRef.enhetList.flatMap { enhet ->
                        enhet.trekkLinjeList.map {
                            "E;" +
                                "${arkivRef.nr};" +
                                "${enhet.enhetnr};" +
                                "${enhet.navn};" +
                                "${it.saksreferanse};" +
                                "${it.arbeidgiverOrgnr};" +
                                "${it.fnr};" +
                                "${it.navn};" +
                                "${it.trekkFOM};" +
                                "${it.trekkTOM};" +
                                "${it.belop.formater()};" +
                                it.kidStatus
                        }
                    }
                }
            }
            .joinToString(separator = LINJESKIFT, postfix = LINJESKIFT)

    val sumEnheter =
        "D;${
            brukerData.brevinfo
                .variableFelter
                .ur
                .arkivRefList.flatMap { it.enhetList }
                .sumOf { enhet -> enhet.delsum.belop }.formater()
        } "

    val sumArkivref =
        "A;${
            brukerData.brevinfo
                .variableFelter
                .ur
                .arkivRefList.sumOf { it.delsumRef.belop }.formater()
        }"

    val sumKreditor = "T;${brukerData.brevinfo.variableFelter.ur.sumTotal.belop.formater()}"

    return "$headerLinje$LINJESKIFT$trekklinjer$sumEnheter$LINJESKIFT$sumArkivref$LINJESKIFT$sumKreditor"
}

fun TrekkKredRapportBestilling.toCsvV2(): String {
    val headerLinje =
        "H;" +
            "${brukerData.brevinfo.brevdato.format(dateFormatter)};" +
            "${brukerData.mottaker.navn.fulltNavn};" +
            "${dato.format(dateFormatter)};" +
            "${brukerData.brevinfo.variableFelter.ur.orgnummer};" +
            " ;" +
            "${brukerData.brevinfo.variableFelter.ur.kontonummer};" +
            "${brukerData.brevinfo.variableFelter.ur.tssId};" +
            "${brukerData.brevinfo.variableFelter.ur.rapportFom.format(dateFormatter)};" +
            "${brukerData.brevinfo.variableFelter.ur.rapportTom.format(dateFormatter)};"
    val trekklinjer =
        with(brukerData.brevinfo.variableFelter.ur) {
            val arkivrefLinjer =
                arkivRefList.joinToString(separator = LINJESKIFT, postfix = LINJESKIFT) { arkivRef ->
                    val enhetLinjer =
                        arkivRef.enhetList.joinToString(separator = LINJESKIFT, postfix = LINJESKIFT) { enhet ->
                            val trekklinjer =
                                enhet.trekkLinjeList.joinToString(separator = LINJESKIFT, postfix = LINJESKIFT) {
                                    "E;" +
                                        "${arkivRef.nr};" +
                                        "${enhet.enhetnr};" +
                                        "${enhet.navn};" +
                                        "${it.saksreferanse};" +
                                        "${it.arbeidgiverOrgnr};" +
                                        "${it.fnr};" +
                                        "${it.navn};" +
                                        "${it.trekkFOM};" +
                                        "${it.trekkTOM};" +
                                        "${it.belop.formater()};" +
                                        it.kidStatus
                                }
                            val sumlinjeEnhet = "D;${enhet.delsum.belop.formater()}"
                            "$trekklinjer$sumlinjeEnhet"
                        }
                    val sumlinjerArkivref = "A;${arkivRef.delsumRef.belop.formater()}"
                    "${enhetLinjer}${sumlinjerArkivref}"
                }
            val sumlinjeTotal = "T;${sumTotal.belop.formater()}$LINJESKIFT"
            arkivrefLinjer + sumlinjeTotal + LINJESKIFT
        }

    return "$headerLinje$LINJESKIFT$trekklinjer"
}
