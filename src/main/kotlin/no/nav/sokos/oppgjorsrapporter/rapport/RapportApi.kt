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
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters.firstDayOfYear
import java.time.temporal.TemporalAdjusters.lastDayOfYear
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import mu.KotlinLogging
import no.nav.sokos.oppgjorsrapporter.auth.*
import no.nav.sokos.oppgjorsrapporter.config.TEAM_LOGS_MARKER
import no.nav.sokos.oppgjorsrapporter.metrics.Metrics
import no.nav.sokos.oppgjorsrapporter.pdp.PdpService
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
        val orgnr: String? = null,
        val aar: Int? = null,
        val fraDato: LocalDate? = null,
        val tilDato: LocalDate? = null,
        val rapportTyper: Set<RapportType> = RapportType.entries.toSet(),
        val inkluderArkiverte: Boolean = false,
    )
}

fun Route.rapportApi() {
    val clock: Clock by application.dependencies
    val rapportService: RapportService by application.dependencies
    val pdpService: PdpService by application.dependencies
    val metrics: Metrics by application.dependencies

    fun harTilgangTilRessurs(bruker: AutentisertBruker, rapportType: RapportType, orgNr: OrgNr): Boolean {
        logger.debug(TEAM_LOGS_MARKER) { "Skal sjekke om $bruker har tilgang til $rapportType for $orgNr" }
        when (bruker) {
            is Systembruker -> {
                if (!pdpService.harTilgang(bruker, setOf(orgNr), rapportType.altinnRessurs)) {
                    logger.info(TEAM_LOGS_MARKER) {
                        "Systembruker $bruker har forsøkt å aksessere rapport $rapportType for $orgNr, men PDP gir ikke tilgang"
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
        metrics.tellApiRequest(this)
        autentisertBruker().let { bruker ->
            val datoRange =
                with(reqBody) {
                    fraDato?.let { fraDato ->
                        if (aar != null) {
                            return@post call.respond(HttpStatusCode.BadRequest, "aar kan ikke kombineres med fraDato")
                        }
                        val til = tilDato ?: fraDato.with(lastDayOfYear())
                        if (fraDato.isAfter(til)) {
                            return@post call.respond(HttpStatusCode.BadRequest, "fraDato kan ikke være etter tilDato")
                        }
                        LocalDateRange.ofClosed(fraDato, til)
                    }
                        ?: aar?.let {
                            if (tilDato != null) {
                                return@post call.respond(HttpStatusCode.BadRequest, "aar kan ikke kombineres med tilDato")
                            }
                            heltAarDateRange(it)
                        }
                        ?: LocalDateRange.ofClosed(LocalDate.now(clock).with(firstDayOfYear()), LocalDate.now(clock))
                }
            val kriterier =
                when (bruker) {
                    is Systembruker -> {
                        // Hvis query-param orgnr er et brukeren ikke har tilgang til, vil PDP-sjekk lenger ned filtrere dette bort
                        val orgNr = reqBody.orgnr?.let { OrgNr(it) } ?: bruker.userOrg
                        InkluderOrgKriterier(setOf(orgNr), reqBody.rapportTyper, datoRange, reqBody.inkluderArkiverte)
                    }
                    is EntraId -> {
                        if (reqBody.orgnr == null) {
                            // TODO: Hvordan skal "egne ansatte" håndteres?
                            EkskluderOrgKriterier(emptySet(), reqBody.rapportTyper, datoRange, reqBody.inkluderArkiverte)
                        } else {
                            InkluderOrgKriterier(setOf(OrgNr(reqBody.orgnr)), reqBody.rapportTyper, datoRange, reqBody.inkluderArkiverte)
                        }
                    }
                // TODO: Finne orgnr brukeren har rettigheter til fra MinID-token, liste for alle dem
                }

            val rapporter = rapportService.listRapporter(kriterier)
            val rapportTyperMedTilgang =
                rapporter.map { it.orgNr to it.type }.toSet().filter { (orgnr, type) -> harTilgangTilRessurs(bruker, type, orgnr) }.toSet()
            val filtrerteRapporter =
                rapporter.filter { r ->
                    val key = r.orgNr to r.type
                    rapportTyperMedTilgang.contains(key)
                }
            call.respond(filtrerteRapporter)
        }
    }

    get<ApiPaths.Rapporter.Id> { rapport ->
        metrics.tellApiRequest(this)
        autentisertBruker().let { bruker ->
            val rapport = rapportService.finnRapport(Rapport.Id(rapport.id)) ?: return@get call.respond(HttpStatusCode.NotFound)
            if (!harTilgangTilRessurs(bruker, rapport.type, rapport.orgNr)) {
                return@get call.respond(HttpStatusCode.NotFound)
            }
            call.respond(rapport)
        }
    }

    get<ApiPaths.Rapporter.Id.Innhold> { innhold ->
        metrics.tellApiRequest(this)
        autentisertBruker().let { bruker ->
            val acceptItems = call.request.acceptItems()
            val format =
                VariantFormat.entries.find { f -> acceptItems.any { it.value == f.contentType } }
                    ?: return@get call.respond(HttpStatusCode.NotAcceptable)
            rapportService.hentInnhold(bruker, Rapport.Id(innhold.parent.id), format) { rapport, innhold ->
                if (harTilgangTilRessurs(bruker, rapport.type, rapport.orgNr)) {
                    call.respondBytes(ContentType.parse(format.contentType), HttpStatusCode.OK) { innhold }
                    rapport
                } else {
                    call.respond(HttpStatusCode.NotFound)
                    null
                }
            }
        }
    }

    put<ApiPaths.Rapporter.Id.Arkiver> { arkiver ->
        metrics.tellApiRequest(this)
        autentisertBruker().let { bruker ->
            rapportService.markerRapportArkivert(Rapport.Id(arkiver.parent.id), bruker) {
                if (harTilgangTilRessurs(bruker, it.type, it.orgNr)) {
                    call.respond(HttpStatusCode.NoContent)
                    arkiver.arkivert
                } else {
                    null
                }
            } ?: call.respond(HttpStatusCode.NotFound)
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
