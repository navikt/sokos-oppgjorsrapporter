package no.nav.sokos.oppgjorsrapporter.pdp

import mu.KotlinLogging
import no.nav.helsearbeidsgiver.altinn.pdp.PdpClient
import no.nav.sokos.oppgjorsrapporter.auth.AuthClient
import no.nav.sokos.oppgjorsrapporter.auth.Systembruker
import no.nav.sokos.oppgjorsrapporter.auth.pdpTokenGetter
import no.nav.sokos.oppgjorsrapporter.config.PropertiesConfig
import no.nav.sokos.oppgjorsrapporter.config.TEAM_LOGS_MARKER
import no.nav.sokos.oppgjorsrapporter.metrics.Metrics
import no.nav.sokos.oppgjorsrapporter.rapport.OrgNr

interface PdpService {
    suspend fun harTilgang(systembruker: Systembruker, orgnumre: Set<OrgNr>, ressurs: String): Boolean
}

class AltinnPdpService(securityProperties: PropertiesConfig.SecurityProperties, authClient: AuthClient, private val metrics: Metrics) :
    PdpService {
    private val logger = KotlinLogging.logger {}
    val pdpClient =
        PdpClient(
            securityProperties.maskinportenProperties.altinn3BaseUrl.toURL().toString(),
            securityProperties.maskinportenProperties.subscriptionKey,
            authClient.pdpTokenGetter(securityProperties.maskinportenProperties.pdpScope),
        )

    override suspend fun harTilgang(systembruker: Systembruker, orgnumre: Set<OrgNr>, ressurs: String): Boolean {
        logger.info(TEAM_LOGS_MARKER) { "PDP orgnr: $orgnumre, systembruker: $systembruker, ressurs: $ressurs" }
        val orgnummerSet = orgnumre.map { it.raw }.toSet()
        val decisionCount = orgnummerSet.size
        metrics.tellPdpKall(decisionCount)
        return runCatching {
                metrics.coRecord({ result ->
                    val countTag = decisionCount.let { if (it >= 10) "10+" else it.toString() }
                    metrics.pdpKallTimer.withTags("decisioncount", countTag, "feilet", result.isFailure.toString())
                }) {
                    pdpClient.systemHarRettighetForOrganisasjoner(
                        systembrukerId = systembruker.userId,
                        orgnumre = orgnummerSet,
                        ressurs = ressurs,
                    )
                }
            }
            .getOrDefault(false) // TODO: håndter feil ved å svare status 500/502 tilbake til bruker
    }
}

object LocalhostPdpService : PdpService {
    private val logger = KotlinLogging.logger {}

    override suspend fun harTilgang(systembruker: Systembruker, orgnumre: Set<OrgNr>, ressurs: String): Boolean {
        logger.info(TEAM_LOGS_MARKER) { "Ingen PDP, har tilgang" }
        return true
    }
}

// Benytter default ingen tilgang i prod inntil vi ønsker å eksponere APIet via http
object IngenTilgangPdpService : PdpService {
    private val logger = KotlinLogging.logger {}

    override suspend fun harTilgang(systembruker: Systembruker, orgnumre: Set<OrgNr>, ressurs: String): Boolean {
        logger.info(TEAM_LOGS_MARKER) { "Ingen PDP, ingen tilgang" }
        return false
    }
}
