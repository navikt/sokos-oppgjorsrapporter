package no.nav.sokos.oppgjorsrapporter.rapport.generator

import java.math.BigDecimal
import java.time.format.DateTimeFormatter
import no.nav.sokos.oppgjorsrapporter.mq.TrekkKredRapportBestilling
import no.nav.sokos.oppgjorsrapporter.rapport.generator.CsvGenerering.LINJESKIFT

fun TrekkKredRapportBestilling.toCsv_V1(): String {
    val dateFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
    fun formaterBigDecimal(bd: BigDecimal) = bd.toString().let { it.dropLast(3) + it.substring(it.length - 2) }

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
                                "${formaterBigDecimal(it.belop)};" +
                                it.kidStatus
                        }
                    }
                }
            }
            .joinToString(separator = LINJESKIFT, postfix = LINJESKIFT)

    val sumEnheter =
        "D;${
            formaterBigDecimal(
                brukerData.brevinfo
                    .variableFelter
                    .ur
                    .arkivRefList.flatMap { it.enhetList }
                    .sumOf { enhet -> enhet.delsum.belop })
        } "

    val sumArkivref =
        "A;${
            formaterBigDecimal(
                brukerData.brevinfo
                    .variableFelter
                    .ur
                    .arkivRefList.sumOf { it.delsumRef.belop })
        }"

    val sumKreditor = "T;${formaterBigDecimal(brukerData.brevinfo.variableFelter.ur.sumTotal.belop)}"

    return "$headerLinje$LINJESKIFT$trekklinjer$sumEnheter$LINJESKIFT$sumArkivref$LINJESKIFT$sumKreditor"
}

fun TrekkKredRapportBestilling.toCsv_V2(): String {
    val dateFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
    fun formaterBigDecimal(bd: BigDecimal) = bd.toString().let { it.dropLast(3) + it.substring(it.length - 2) }

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
                arkivRefList
                    .map { arkivRef ->
                        val enhetLinjer =
                            arkivRef.enhetList
                                .map { enhet ->
                                    val trekklinjer =
                                        enhet.trekkLinjeList
                                            .map {
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
                                                    "${formaterBigDecimal(it.belop)};" +
                                                    it.kidStatus
                                            }
                                            .joinToString(separator = LINJESKIFT, postfix = LINJESKIFT)
                                    val sumlinjeEnhet = "D;${formaterBigDecimal(enhet.delsum.belop)}"
                                    "$trekklinjer$sumlinjeEnhet"
                                }
                                .joinToString(separator = LINJESKIFT, postfix = LINJESKIFT)
                        val sumlinjerArkivref = "A;${formaterBigDecimal(arkivRef.delsumRef.belop)}"
                        "${enhetLinjer}${sumlinjerArkivref}"
                    }
                    .joinToString(separator = LINJESKIFT, postfix = LINJESKIFT)
            val sumlinjeTotal = "T;${formaterBigDecimal(sumTotal.belop)}$LINJESKIFT"
            arkivrefLinjer + sumlinjeTotal + LINJESKIFT
        }

    return "$headerLinje$LINJESKIFT$trekklinjer"
}
