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
import io.ktor.server.routing.put
import mu.KotlinLogging
import no.nav.sokos.oppgjorsrapporter.auth.AutentisertBruker
import no.nav.sokos.oppgjorsrapporter.auth.EntraId
import no.nav.sokos.oppgjorsrapporter.auth.Systembruker
import no.nav.sokos.oppgjorsrapporter.auth.getBruker
import no.nav.sokos.oppgjorsrapporter.auth.tokenValidationContext
import no.nav.sokos.oppgjorsrapporter.config.TEAM_LOGS_MARKER
import no.nav.sokos.oppgjorsrapporter.metrics.tellApiRequest
import no.nav.sokos.oppgjorsrapporter.pdp.PdpService

private val logger = KotlinLogging.logger {}

@Resource(path = "/api")
class ApiPaths {
    @Resource("rapport/v1")
    class Rapporter(val parent: ApiPaths = ApiPaths(), val inkluderArkiverte: Boolean = false, val orgnr: String? = null) {
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
        // TODO: Listen med tilgjengelige rapporter kan bli lang; trenger vi å lage noe slags paging?  La klient angi hvilken tidsperiode de
        // er interesserte i?
        tellApiRequest()
        autentisertBruker().let { bruker ->
            if (rapporter.orgnr != null) {
                val orgNr = OrgNr(rapporter.orgnr)
                val rapporter = rapportService.listRapporterForOrg(orgNr)
                val rapportTyperMedTilgang = rapporter.map { it.type }.toSet().filter { harTilgangTilRessurs(bruker, it, orgNr) }
                val filtrerteRapporter = rapporter.filter { rapportTyperMedTilgang.contains(it.type) }
                call.respond(filtrerteRapporter)
            } else {
                // TODO: Finne orgnr brukeren har rettigheter til fra MinID-token, liste for alle dem
                call.respond(HttpStatusCode.BadRequest, "Mangler orgnr")
            }
        }
    }

    get<ApiPaths.Rapporter.Id> { rapport ->
        tellApiRequest()
        autentisertBruker().let { bruker ->
            val rapport = rapportService.finnRapport(Rapport.Id(rapport.id)) ?: return@get call.respond(HttpStatusCode.NotFound)
            if (!harTilgangTilRessurs(bruker, rapport.type, rapport.orgNr)) {
                return@get call.respond(HttpStatusCode.NotFound)
            }
            call.respond(rapport)
        }
    }

    get<ApiPaths.Rapporter.Id.Innhold> { innhold ->
        tellApiRequest()
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
        tellApiRequest()
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
