package no.nav.sokos.oppgjorsrapporter.rapport.generator

import java.math.BigDecimal
import java.time.format.DateTimeFormatter
import no.nav.sokos.oppgjorsrapporter.mq.TrekkKredRapportBestilling
import no.nav.sokos.oppgjorsrapporter.rapport.generator.CsvGenerering.LINJESKIFT

private val dateFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")

private fun BigDecimal.formater() = toString().let { it.dropLast(3) + it.substring(it.length - 2) }

fun TrekkKredRapportBestilling.toCsv(): String {
    val csvRader = buildList {
        add(
            listOf(
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
        )

        with(brukerData.brevinfo.variableFelter.ur) {
            arkivRefList.forEach { arkivRef ->
                arkivRef.enhetList.forEach { enhet ->
                    enhet.trekkLinjeList.forEach { trekkLinje ->
                        add(
                            csvRad(
                                "E",
                                arkivRef.nr,
                                enhet.enhetnr,
                                enhet.navn,
                                trekkLinje.saksreferanse,
                                trekkLinje.arbeidgiverOrgnr.raw,
                                trekkLinje.fnr.raw,
                                trekkLinje.navn,
                                trekkLinje.trekkFOM.format(dateFormatter),
                                trekkLinje.trekkTOM.format(dateFormatter),
                                trekkLinje.belop.formater(),
                                trekkLinje.kidStatus,
                            )
                        )
                    }
                    add(csvRad("D", enhet.delsum.belop.formater()))
                }
                add(csvRad("A", arkivRef.delsumRef.belop.formater()))
            }
            add(csvRad("T", sumTotal.belop.formater()))
        }
    }
    return csvRader.joinToString(LINJESKIFT, postfix = LINJESKIFT)
}

private fun csvRad(vararg kolonner: String): String = kolonner.joinToString(";")
