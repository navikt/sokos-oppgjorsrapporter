@file:UseSerializers(InstantAsStringSerializer::class, LocalDateAsStringSerializer::class)

package no.nav.sokos.oppgjorsrapporter.rapport

import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.application
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.util.getValue
import java.time.Instant
import java.time.LocalDate
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.json.JsonContentPolymorphicSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import mu.KotlinLogging
import no.nav.sokos.oppgjorsrapporter.auth.AuthClient
import no.nav.sokos.oppgjorsrapporter.auth.AuthClientIdentityProvider
import no.nav.sokos.oppgjorsrapporter.auth.EntraId
import no.nav.sokos.oppgjorsrapporter.auth.TokenNotFoundException
import no.nav.sokos.oppgjorsrapporter.auth.autentisertBruker
import no.nav.sokos.oppgjorsrapporter.auth.hentJwtToken
import no.nav.sokos.oppgjorsrapporter.config.AuthenticationType
import no.nav.sokos.oppgjorsrapporter.config.PropertiesConfig
import no.nav.sokos.oppgjorsrapporter.config.TEAM_LOGS_MARKER
import no.nav.sokos.oppgjorsrapporter.entraid.InternTilgangService
import no.nav.sokos.oppgjorsrapporter.rapport.Api.RapportDTO
import no.nav.sokos.oppgjorsrapporter.rapport.varsel.Varsel
import no.nav.sokos.oppgjorsrapporter.rapport.varsel.VarselService
import no.nav.sokos.oppgjorsrapporter.serialization.InstantAsStringSerializer
import no.nav.sokos.oppgjorsrapporter.serialization.LocalDateAsStringSerializer
import no.nav.sokos.oppgjorsrapporter.tilgangsmaskin.TilgangsmaskinService
import no.nav.sokos.oppgjorsrapporter.tilgangsmaskin.UnexpectedResponseException
import no.nav.sokos.utils.Fnr
import no.nav.sokos.utils.OrgNr
import org.threeten.extra.LocalDateRange

object FrontendApi {
    private val logger = KotlinLogging.logger {}

    @Serializable(with = RapportSoek.Serializer::class)
    sealed interface RapportSoek {
        val fraDato: LocalDate
        val tilDato: LocalDate
        val rapportType: RapportType
        val inkluderArkiverte: Boolean

        fun datoRange(): LocalDateRange = LocalDateRange.ofClosed(fraDato, tilDato)

        class Serializer : JsonContentPolymorphicSerializer<RapportSoek>(RapportSoek::class) {
            override fun selectDeserializer(element: JsonElement): DeserializationStrategy<RapportSoek> {
                val jsonObject = element.jsonObject
                return when {
                    "fnr" in jsonObject -> RapportFnrSoek.serializer()
                    "underenhet" in jsonObject -> RapportUnderenhetSoek.serializer()
                    else -> error("Ukjent feil ved deserialisering")
                }
            }
        }
    }

    @Serializable
    data class RapportFnrSoek(
        val fnr: Fnr,
        override val fraDato: LocalDate,
        override val tilDato: LocalDate,
        override val rapportType: RapportType,
        override val inkluderArkiverte: Boolean = true,
    ) : RapportSoek

    @Serializable
    data class RapportUnderenhetSoek(
        val underenhet: OrgNr,
        override val fraDato: LocalDate,
        override val tilDato: LocalDate,
        override val rapportType: RapportType,
        override val inkluderArkiverte: Boolean = true,
    ) : RapportSoek

    @Serializable
    data class VarselOppgittDTO(val varselOpprettet: Instant, val varslingOppgitt: Instant, val rapport: RapportDTO) {
        constructor(pair: Pair<Varsel, Rapport>) : this(pair.first.opprettet, pair.first.oppgitt!!, RapportDTO(pair.second))
    }

    fun Route.rapportApi() {
        val varselService: VarselService by application.dependencies
        val rapportService: RapportService by application.dependencies
        val internTilgangService: InternTilgangService by application.dependencies
        val tilgangsmaskinService: TilgangsmaskinService by application.dependencies
        val authClient: AuthClient by application.dependencies
        val config: PropertiesConfig.Configuration by application.dependencies

        get("/api/rapport/frontend/oppgitt-varsling") { call.respond(varselService.finnOppgitte().map(::VarselOppgittDTO)) }

        get("/api/rapport/frontend/{id}/audit") {
            val id: Long by call.request.pathVariables
            val events = rapportService.hentAuditLog(RapportAuditKriterier(Rapport.Id(id), null, null))
            if (events.isNotEmpty()) {
                call.respond(events)
            } else {
                call.respond(HttpStatusCode.NotFound)
            }
        }

        get("/api/rapport/frontend/tilgang") {
            autentisertBruker().let { bruker ->
                when (bruker) {
                    is EntraId -> call.respond(internTilgangService.rapportTyperBrukerHarTilgangTil(bruker))
                    else -> {
                        logger.warn(TEAM_LOGS_MARKER) {
                            "En IKKE EntraId bruker: $bruker har ikke tilgang til /api/rapport/frontend/tilgang"
                        }
                        call.respond(HttpStatusCode.Forbidden)
                    }
                }
            }
        }
        post("/api/rapport/frontend/nevnt-sok") {
            val reqBody = call.receive<RapportSoek>()
            autentisertBruker().let { bruker ->
                when (bruker) {
                    is EntraId -> {
                        if (!internTilgangService.rapportTyperBrukerHarTilgangTil(bruker).contains(reqBody.rapportType)) {
                            logger.info { "Bruker $bruker har ikke tilgang til rapporttype ${reqBody.rapportType}" }
                            return@post call.respond(HttpStatusCode.Forbidden)
                        }
                        val rapporter =
                            when (reqBody) {
                                is RapportFnrSoek ->
                                    with(reqBody) {
                                        val manglerTilgang =
                                            try {
                                                val token = hentJwtToken(AuthenticationType.INTERNE_BRUKERE_AZUREAD_JWT)
                                                val onBehalfOfToken =
                                                    authClient.exchange(
                                                        AuthClientIdentityProvider.ENTRA_ID,
                                                        config.security.tilgangsmaskinenAudience,
                                                        token.encodedToken,
                                                    )
                                                val tilgang = tilgangsmaskinService.sjekkTilgang(onBehalfOfToken.accessToken, fnr)
                                                tilgang?.begrunnelse
                                            } catch (e: TokenNotFoundException) {
                                                e.message
                                            } catch (e: UnexpectedResponseException) {
                                                e.message
                                            }
                                        if (manglerTilgang != null) {
                                            return@post call.respond(HttpStatusCode.Forbidden, manglerTilgang)
                                        }

                                        rapportService.rapportSoek(fnr, datoRange(), inkluderArkiverte, rapportType)
                                    }
                                is RapportUnderenhetSoek ->
                                    with(reqBody) { rapportService.rapportSoek(underenhet, datoRange(), inkluderArkiverte, rapportType) }
                            }
                        call.respond(
                            rapporter.filter { internTilgangService.harTilgangTilRessurs(bruker, it.orgnr, it.type) }.map(::RapportDTO)
                        )
                    }
                    else -> {
                        logger.warn(TEAM_LOGS_MARKER) { "En IKKE EntraId bruker: $bruker har ikke tilgang til /api/rapport/frontend" }
                        call.respond(HttpStatusCode.Forbidden)
                    }
                }
            }
        }
    }
}
