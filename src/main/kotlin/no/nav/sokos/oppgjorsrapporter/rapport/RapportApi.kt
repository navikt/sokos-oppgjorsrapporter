@file:UseSerializers(LocalDateAsStringSerializer::class)

package no.nav.sokos.oppgjorsrapporter.rapport

import io.ktor.http.*
import io.ktor.resources.*
import io.ktor.server.plugins.di.*
import io.ktor.server.request.*
import io.ktor.server.resources.*
import io.ktor.server.resources.put
import io.ktor.server.response.*
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.application
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters.firstDayOfYear
import java.time.temporal.TemporalAdjusters.lastDayOfYear
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import mu.KotlinLogging
import no.nav.sokos.oppgjorsrapporter.auth.*
import no.nav.sokos.oppgjorsrapporter.config.TEAM_LOGS_MARKER
import no.nav.sokos.oppgjorsrapporter.pdp.PdpService
import no.nav.sokos.oppgjorsrapporter.serialization.InstantAsStringSerializer
import no.nav.sokos.oppgjorsrapporter.serialization.LocalDateAsStringSerializer
import no.nav.sokos.oppgjorsrapporter.util.heltAarDateRange
import org.threeten.extra.LocalDateRange

private val logger = KotlinLogging.logger {}

@Resource(path = "/api")
class ApiPaths {
    @Resource("rapport/v1")
    class Rapporter(val parent: ApiPaths = ApiPaths()) {
        @Resource("{id}")
        class Id(val parent: Rapporter = Rapporter(), val id: Long) {
            @Resource("innhold") class Innhold(val parent: Id)

            @Resource("arkiver") class Arkiver(var parent: Id, val arkivert: Boolean = true)
        }
    }
}

object Api {
    @Serializable
    data class RapportListeRequest(
        val orgnr: OrgNr? = null,
        val bankkonto: Bankkonto? = null,
        val etterId: Rapport.Id? = null,
        val aar: Int? = null,
        val fraDato: LocalDate? = null,
        val tilDato: LocalDate? = null,
        val rapportTyper: Set<RapportType> = RapportType.entries.toSet(),
        val inkluderArkiverte: Boolean = false,
    ) {
        fun datoRange(clock: Clock): LocalDateRange =
            fraDato?.let { fraDato ->
                if (aar != null) {
                    throw IllegalArgumentException("aar kan ikke kombineres med fraDato")
                }
                if (etterId != null) {
                    throw IllegalArgumentException("etterId kan ikke kombineres med fraDato")
                }
                val til = tilDato ?: fraDato.with(lastDayOfYear())
                if (fraDato.isAfter(til)) {
                    throw IllegalArgumentException("fraDato kan ikke være etter tilDato")
                }
                LocalDateRange.ofClosed(fraDato, til)
            }
                ?: aar?.let {
                    if (tilDato != null) {
                        throw IllegalArgumentException("aar kan ikke kombineres med tilDato")
                    }
                    if (etterId != null) {
                        throw IllegalArgumentException("aar kan ikke kombineres med etterId")
                    }
                    heltAarDateRange(it)
                }
                ?: tilDato?.let { throw IllegalArgumentException("tilDato kan ikke angis uten fraDato") }
                ?: LocalDateRange.ofClosed(LocalDate.now(clock).with(firstDayOfYear()), LocalDate.now(clock))
    }

    @Serializable
    data class RapportDTO(
        val id: Rapport.Id,
        val orgnr: OrgNr,
        val type: RapportType,
        val datoValutert: LocalDate,
        @Serializable(with = InstantAsStringSerializer::class) val opprettet: Instant,
        val arkivert: Boolean,
    ) {
        constructor(
            rapport: Rapport
        ) : this(rapport.id, rapport.orgnr, rapport.type, rapport.datoValutert, rapport.opprettet, rapport.erArkivert)
    }
}

fun Route.rapportApi() {
    val clock: Clock by application.dependencies
    val rapportService: RapportService by application.dependencies
    val pdpService: PdpService by application.dependencies

    suspend fun harTilgangTilRessurs(bruker: AutentisertBruker, rapportType: RapportType, orgnr: OrgNr): Boolean {
        logger.debug(TEAM_LOGS_MARKER) { "Skal sjekke om $bruker har tilgang til $rapportType for $orgnr" }
        when (bruker) {
            is Systembruker -> {
                if (!pdpService.harTilgang(bruker, setOf(orgnr), rapportType.altinnRessurs)) {
                    logger.info(TEAM_LOGS_MARKER) {
                        "Systembruker $bruker har forsøkt å aksessere rapport $rapportType for $orgnr, men PDP gir ikke tilgang"
                    }
                    return false
                }
            }

            is EntraId -> {
                // Enn så lenge har vi bare en gruppe definert i `azure:`-delen av nais-specen, så ytterligere autorisasjons-sjekker
                // er ikke nødvendig
            }
        }
        return true
    }

    post<ApiPaths.Rapporter, Api.RapportListeRequest> { _, reqBody ->
        // TODO: Listen med tilgjengelige rapporter kan bli lang; trenger vi å lage noe slags paging?
        autentisertBruker().let { bruker ->
            val datoRange =
                try {
                    reqBody.datoRange(clock)
                } catch (e: IllegalArgumentException) {
                    return@post call.respond(HttpStatusCode.BadRequest, e.message ?: "Ukjent feil")
                }
            if (reqBody.bankkonto != null) {
                if (bruker is Systembruker) {
                    return@post call.respond(HttpStatusCode.BadRequest, "søk på bankkonto tillates ikke for systembrukere")
                }
                if (reqBody.orgnr != null) {
                    return@post call.respond(HttpStatusCode.BadRequest, "bankkonto kan ikke kombineres med orgnr")
                }
            }
            val orgnr = reqBody.orgnr ?: (bruker as? Systembruker)?.userOrg
            // TODO: Hvordan skal "egne ansatte" håndteres?  Både her (for å ikke hente ut unødvendig mye data fra databasen) og i
            //       harTilgangTilRessurs() (for å begrense søk med eksplisitt orgnr)?
            val kriterier =
                if (reqBody.etterId != null) {
                    if (orgnr == null) {
                        return@post call.respond(HttpStatusCode.BadRequest, "etterId krever at orgnr er angitt")
                    }
                    // Her må orgnr være angitt, enten eksplisitt eller implisitt.  Siden kombinasjonen orgnr og bankkonto skal være
                    // ugyldig, legger vi ikke inn støtte for filtrering på bankkonto i EtterIdKriterier
                    EtterIdKriterier(
                        orgnr = orgnr,
                        etterId = reqBody.etterId,
                        rapportTyper = reqBody.rapportTyper,
                        inkluderArkiverte = reqBody.inkluderArkiverte,
                    )
                } else if (orgnr != null) {
                    // Enten eksplisitt reqBody.orgnr eller implisitt orgnr fra Systembruker-autentisering
                    // Siden vi her vet at orgnr er angitt, får heller ikke InkluderOrgKriterier støtte for filtering på bankkonto
                    InkluderOrgKriterier(
                        inkluderte = setOf(orgnr),
                        rapportTyper = reqBody.rapportTyper,
                        periode = datoRange,
                        inkluderArkiverte = reqBody.inkluderArkiverte,
                    )
                } else {
                    // Må være EntraId-bruker uten reqBody.orgnr
                    EkskluderOrgKriterier(
                        ekskluderte = emptySet(),
                        bankkonto = reqBody.bankkonto,
                        rapportTyper = reqBody.rapportTyper,
                        periode = datoRange,
                        inkluderArkiverte = reqBody.inkluderArkiverte,
                    )
                }
            // TODO: Håndtere MinID-autentisering: Bruke token med arbeidsgiver-altinn-tilganger for å finne virksomhetene brukeren har
            //       rettigheter, liste for alle dem

            val rapporter = rapportService.listRapporter(kriterier)
            val rapportTyperMedTilgang =
                rapporter.map { it.orgnr to it.type }.toSet().filter { (orgnr, type) -> harTilgangTilRessurs(bruker, type, orgnr) }.toSet()
            val filtrerteRapporter =
                rapporter.filter { r ->
                    val key = r.orgnr to r.type
                    rapportTyperMedTilgang.contains(key)
                }
            call.respond(filtrerteRapporter.map(Api::RapportDTO))
        }
    }

    get<ApiPaths.Rapporter.Id> { rapport ->
        autentisertBruker().let { bruker ->
            val rapport = rapportService.finnRapport(Rapport.Id(rapport.id)) ?: return@get call.respond(HttpStatusCode.NotFound)
            if (!harTilgangTilRessurs(bruker, rapport.type, rapport.orgnr)) {
                return@get call.respond(HttpStatusCode.NotFound)
            }
            call.respond(Api.RapportDTO(rapport))
        }
    }

    get<ApiPaths.Rapporter.Id.Innhold> { innhold ->
        autentisertBruker().let { bruker ->
            val acceptItems = call.request.acceptItems()
            val format =
                VariantFormat.entries.find { f -> acceptItems.any { it.value == f.contentType } }
                    ?: return@get call.respond(HttpStatusCode.NotAcceptable)
            rapportService.hentInnhold(
                bruker = bruker,
                rapportId = Rapport.Id(innhold.parent.id),
                format = format,
                harTilgang = { harTilgangTilRessurs(bruker, it.type, it.orgnr) },
                process = { variant, innhold ->
                    call.response.header(
                        HttpHeaders.ContentDisposition,
                        ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, variant.filnavn).toString(),
                    )
                    call.respondBytes(ContentType.parse(format.contentType), HttpStatusCode.OK) { innhold.toByteArray() }
                },
            ) ?: call.respond(HttpStatusCode.NotFound)
        }
    }

    put<ApiPaths.Rapporter.Id.Arkiver> { arkiver ->
        autentisertBruker().let { bruker ->
            rapportService
                .markerRapportArkivert(
                    bruker = bruker,
                    id = Rapport.Id(arkiver.parent.id),
                    harTilgang = { harTilgangTilRessurs(bruker, it.type, it.orgnr) },
                    skalArkiveres = arkiver.arkivert,
                )
                ?.let { call.respond(HttpStatusCode.NoContent) } ?: call.respond(HttpStatusCode.NotFound)
        }
    }
}

private suspend fun RoutingContext.autentisertBruker(): AutentisertBruker =
    tokenValidationContext().let { ctx ->
        ctx.getBruker().getOrElse {
            call.respond(HttpStatusCode.Unauthorized)
            // "Hemmeligheter som access tokens skal aldri logges", i følge [etterlevelseskrav
            // K267.1](https://etterlevelse.ansatt.nav.no/krav/267/1) - men for å kunne debugge hvorfor vi ikke klarer
            // å finne ut av hvilken bruker det er snakk om her, selv om den har kommet gjennom authenticate(...)-oppsettet i
            // routingConfig, må vi nesten logge *claimene* i de tokenene som er validert.
            // Kun token-claims (men uten signatur etc.) skal ikke være nok til at noen med logg-tilgang kan late som om de er den
            // aktuelle brukeren.
            val claims = ctx.issuers.associateWith { ctx.getClaims(it) }
            logger.warn(TEAM_LOGS_MARKER) { "Bruker skal være autentisert, men klarer ikke å finne AutentisertBruker for $claims" }
            throw IllegalStateException("Klarer ikke å finne AutentisertBruker")
        }
    }
