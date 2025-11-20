package no.nav.sokos.oppgjorsrapporter.rapport

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.resources.Resource
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.request.acceptItems
import io.ktor.server.resources.get
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.application
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters.firstDayOfYear
import java.time.temporal.TemporalAdjusters.lastDayOfYear
import kotlinx.serialization.Serializable
import mu.KotlinLogging
import no.nav.helsearbeidsgiver.utils.json.serializer.LocalDateSerializer
import no.nav.sokos.oppgjorsrapporter.auth.AutentisertBruker
import no.nav.sokos.oppgjorsrapporter.auth.EntraId
import no.nav.sokos.oppgjorsrapporter.auth.Systembruker
import no.nav.sokos.oppgjorsrapporter.auth.getBruker
import no.nav.sokos.oppgjorsrapporter.auth.tokenValidationContext
import no.nav.sokos.oppgjorsrapporter.config.TEAM_LOGS_MARKER
import no.nav.sokos.oppgjorsrapporter.metrics.Metrics
import no.nav.sokos.oppgjorsrapporter.pdp.PdpService
import no.nav.sokos.oppgjorsrapporter.util.heltAarDateRange
import org.threeten.extra.LocalDateRange

private val logger = KotlinLogging.logger {}

@Resource(path = "/api")
class ApiPaths {
    @Resource("rapport/v1")
    class Rapporter(
        val parent: ApiPaths = ApiPaths(),
        val orgnr: String? = null,
        val aar: Int? = null,
        @Serializable(with = LocalDateSerializer::class) val fraDato: LocalDate? = null,
        @Serializable(with = LocalDateSerializer::class) val tilDato: LocalDate? = null,
        val rapportType: List<RapportType> =
            emptyList(), // TODO: Får ikke OpenApiValidationFilter til å virke med array-typet spec for denne.
        val inkluderArkiverte: Boolean = false,
    ) {
        @Resource("{id}")
        class Id(val parent: Rapporter = Rapporter(), val id: Long) {
            @Resource("innhold") class Innhold(val parent: Id)

            @Resource("arkiver") class Arkiver(var parent: Id, val arkivert: Boolean? = true)
        }
    }
}

fun Route.rapportApi() {
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

    get<ApiPaths.Rapporter> { rapporter ->
        // TODO: Listen med tilgjengelige rapporter kan bli lang; trenger vi å lage noe slags paging?
        metrics.tellApiRequest(this)
        autentisertBruker().let { bruker ->
            val rapportTyper = rapporter.rapportType.ifEmpty { RapportType.entries }.toSet()
            val datoRange =
                rapporter.fraDato?.let { fraDato -> LocalDateRange.ofClosed(fraDato, rapporter.tilDato ?: fraDato.with(lastDayOfYear())) }
                    ?: rapporter.aar?.let { heltAarDateRange(it) }
                    ?: LocalDateRange.ofClosed(LocalDate.now().with(firstDayOfYear()), LocalDate.now())
            val kriterier =
                when (bruker) {
                    is Systembruker -> {
                        // Hvis query-param orgnr er et brukeren ikke har tilgang til, vil PDP-sjekk lenger ned filtrere dette bort
                        val orgNr = rapporter.orgnr?.let { OrgNr(it) } ?: bruker.userOrg
                        InkluderOrgKriterier(setOf(orgNr), rapportTyper, datoRange, rapporter.inkluderArkiverte)
                    }
                    is EntraId -> {
                        // TODO: Hvordan skal "egne ansatte" håndteres?
                        EkskluderOrgKriterier(emptySet(), rapportTyper, datoRange, rapporter.inkluderArkiverte)
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
        autentisertBruker().let { _ ->
            call.respondText("sett arkivert: $arkiver")
            TODO()
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
