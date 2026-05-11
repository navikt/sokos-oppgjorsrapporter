package no.nav.sokos.oppgjorsrapporter.rapport.generator

import java.time.LocalDate
import kotlinx.serialization.Serializable
import no.nav.sokos.oppgjorsrapporter.mq.TrekkHendBestilling
import no.nav.sokos.oppgjorsrapporter.rapport.generator.CsvGenerering.LINJESKIFT
import no.nav.sokos.oppgjorsrapporter.util.tilNorskFormat

fun TrekkHendBestilling.toCsv(): String {
    val headerLinje =
        listOf(
                "H",
                typeMottaker.name,
                dato.tilNorskFormat(),
                orgNr.raw,
                orgNavn ?: "",
                orgAdresse?.adresselinje1 ?: "",
                orgAdresse?.adresselinje2 ?: "",
                orgAdresse?.adresselinje3 ?: "",
                orgAdresse?.postnr ?: "",
                orgAdresse?.poststed ?: "",
                orgAdresse?.land ?: "",
            )
            .let { kolonner: List<String> -> kolonner.joinToString(";") }

    val trekklinjer =
        with(trekkhendelse) {
            when (typeMottaker) {
                TrekkHendBestilling.TypeMottaker.kreditor -> {
                    kreditorMelding!!.map { melding ->
                        listOf(
                            "T",
                            melding.namsmannsOrgNr.raw,
                            melding.namsmannsNavn,
                            melding.kreditorsKidRef,
                            melding.debitorsFnr.raw,
                            melding.debitorsNavn,
                            melding.typeHendelse,
                            "",
                        )
                    }
                }

                TrekkHendBestilling.TypeMottaker.namsmann -> {
                    namsmannMelding!!.map { melding ->
                        listOf(
                            "T",
                            melding.kreditorsOrgNr.raw,
                            melding.kreditorsNavn,
                            melding.kreditorsKidRef,
                            melding.debitorsFnr.raw,
                            melding.debitorsNavn,
                            melding.typeHendelse,
                            "",
                        )
                    }
                }
            }.map { kolonner: List<String> -> kolonner.joinToString(";") }
        }
    return listOf(headerLinje, *trekklinjer.toTypedArray()).joinToString(LINJESKIFT, postfix = LINJESKIFT)
}

fun TrekkHendBestilling.mapTilTrekkHendPdfPayload(): TrekkHendPdfPayload =
    TrekkHendPdfPayload(
        dato = dato,
        mottaker =
            TrekkHendPdfPayload.Mottaker(
                type = typeMottaker,
                orgnr = OrgNummer(orgNr),
                navn = orgNavn,
                adresse =
                    orgAdresse?.let { adr ->
                        with(adr) {
                            listOfNotNull(
                                    adresselinje1,
                                    adresselinje2,
                                    adresselinje3,
                                    adresselinje4,
                                    listOfNotNull(postnr, poststed).filter { it.isNotBlank() }.map { it.trim() }.joinToString(" "),
                                    land,
                                )
                                .filter { it.isNotBlank() }
                                .map { it.trim() }
                                .joinToString(", ")
                                .takeIf { it.isNotBlank() }
                        }
                    },
            ),
        trekkHendelser =
            when (typeMottaker) {
                TrekkHendBestilling.TypeMottaker.namsmann ->
                    trekkhendelse.namsmannMelding!!.map { m ->
                        TrekkHendPdfPayload.Melding(
                            Fodselsnummer(m.debitorsFnr),
                            m.debitorsNavn,
                            null, // Brukes kun for TypeMottaker.kreditor
                            m.kreditorsKidRef,
                            m.typeHendelse,
                        )
                    }

                TrekkHendBestilling.TypeMottaker.kreditor ->
                    trekkhendelse.kreditorMelding!!.map { h ->
                        TrekkHendPdfPayload.Melding(
                            Fodselsnummer(h.debitorsFnr),
                            h.debitorsNavn,
                            OrgNummer(h.namsmannsOrgNr),
                            h.kreditorsKidRef,
                            h.typeHendelse,
                        )
                    }
            },
    )

@Serializable
data class TrekkHendPdfPayload(
    @Serializable(with = LocalDateSomNorskDatoSerializer::class) val dato: LocalDate,
    val mottaker: Mottaker,
    val trekkHendelser: List<Melding>,
) {
    @Serializable
    data class Mottaker(val type: TrekkHendBestilling.TypeMottaker, val orgnr: OrgNummer, val navn: String?, val adresse: String?)

    @Serializable
    data class Melding(val fnr: Fodselsnummer, val navn: String, val namsmannOrgnr: OrgNummer?, val kid: String, val hendelse: String)
}
