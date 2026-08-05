@file:UseSerializers(LocalDateAsStringSerializer::class, InstantAsStringSerializer::class, BigDecimalSerializer::class)
@file:OptIn(ExperimentalSerializationApi::class)

package no.nav.sokos.oppgjorsrapporter.mq

import java.math.BigDecimal
import java.time.LocalDate
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.json.Json
import no.nav.sokos.oppgjorsrapporter.rapport.UlagretRapport
import no.nav.sokos.oppgjorsrapporter.rapport.generator.Belop
import no.nav.sokos.oppgjorsrapporter.serialization.BigDecimalSerializer
import no.nav.sokos.oppgjorsrapporter.serialization.InstantAsStringSerializer
import no.nav.sokos.oppgjorsrapporter.serialization.LocalDateAsStringSerializer
import no.nav.sokos.utils.Bankkonto
import no.nav.sokos.utils.Fnr
import no.nav.sokos.utils.OrgNr

@Serializable
data class RefusjonsRapportBestilling(val header: Header, val datarec: List<Data>) : RapportBestillingMsg() {
    override fun valideringsFeil(): List<String> =
        listOfNotNull(
            // alle orgnr skal være gyldige
            buildSet {
                    add(header.orgnr)
                    addAll(datarec.map { it.bedriftsnummer })
                }
                .filterNot { it.erGyldig() }
                .takeIf { it.isNotEmpty() }
                ?.map { it.raw }
                ?.let { "Ikke gyldig orgnr: ${it.sorted().joinToString()}" },
            // alle fnr skal være gyldige
            datarec
                .map { it.fnr }
                .toSet()
                // Korrigeringsposteringer kan bli lagt på mottakende orgnr (prefikset med nok 0-er til å se ut som et fnr) i stedet for en
                // person; i slike tilfeller sendes rapport-mottaker pr. brev en forklaring på hvordan ting egentlig henger sammen.
                // For valideringen betyr dette at vi ikke skal forsøke å Fnr-validere posteringer der "fnr" egentlig er mottakers orgnr.
                .filterNot { it.raw.removePrefix("00") == header.orgnr.raw }
                .filterNot { it.erGyldig() }
                .takeIf { it.isNotEmpty() }
                ?.map { it.raw }
                ?.let { "Ikke gyldig fnr: ${it.sorted().joinToString()}" },
            // bankkonto skal kun inneholde siffer, i riktig antall
            header.bankkonto
                .takeUnless {
                    // Tillat kontonummer 0; brukes av UR-Z hvis Oppdrag ikke lenger kan sende et gyldig kontonr, typisk ved omposteringer
                    // (som kan komme lang tid etter utbetalingen)
                    it.raw.matches("^0+$".toRegex()) ||
                        // For andre kontonr: sjekk at kontrollsiffer stemmer
                        it.erGyldig()
                }
                ?.let { "Ikke gyldig bankkonto: ${it.raw}" },
            // valutert dato kan ikke være for gammel (grense valgt på måfå) eller i fremtiden
            if (header.valutert.isAfter(LocalDate.now())) {
                "Fremtidig dato for valutering: ${header.valutert}"
            } else if (header.valutert.isBefore(LocalDate.ofEpochDay(0))) {
                "For gammel dato for valutering: ${header.valutert}"
            } else {
                null
            },
            // Alle beløp skal være angitt med nøyaktig 2 desimaler
            buildSet {
                    add(header.sumBelop)
                    addAll(datarec.map { it.belop })
                }
                .filterNot { Belop(it).verdi == it }
                .takeIf { it.isNotEmpty() }
                ?.let { "Ikke gyldig beløp: ${it.joinToString()}" },
            // Summen av alle posterings-beløpene skal være likt sum-beløpet
            datarec
                .sumOf { it.belop }
                .takeUnless { it == header.sumBelop }
                ?.let { "header.sumBelop (${header.sumBelop}) stemmer ikke med summen av posteringer: $it" },
        )

    override fun nevntInfo(): List<UlagretRapport.NevntInfo> =
        listOf(UlagretRapport.NevntVersjon(1)) +
            datarec.flatMap { listOf(UlagretRapport.NevntFnr(it.fnr), UlagretRapport.NevntUnderenhet(it.bedriftsnummer)) }.distinct()

    companion object {
        val json = Json { explicitNulls = false }

        fun decode(dokument: String): RefusjonsRapportBestilling = json.decodeFromString<RefusjonsRapportBestilling>(dokument)
    }
}

@Serializable
data class Header(
    val orgnr: OrgNr,
    val bankkonto: Bankkonto,
    val sumBelop: BigDecimal,
    val valutert: LocalDate,
    // Antall elementer i `datarec: List<Data>`.  Trengs egentlig ikke av vår applikasjon, men JSON-genereringen på sender-siden har ikke
    // noen enkel måte å utelate den på.
    val linjer: Long,
)

@Serializable
data class Data(
    val navenhet: Int,
    val bedriftsnummer: OrgNr,
    val kode: String,
    val tekst: String,
    val fnr: Fnr,
    val navn: String,
    val belop: BigDecimal,
    val fraDato: LocalDate?,
    val tilDato: LocalDate?,
    val maxDato: LocalDate?,
)
