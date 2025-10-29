package no.nav.sokos.oppgjorsrapporter.pdp

import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import no.nav.helsearbeidsgiver.altinn.pdp.PdpClient
import no.nav.sokos.oppgjorsrapporter.auth.AuthClient
import no.nav.sokos.oppgjorsrapporter.auth.Systembruker
import no.nav.sokos.oppgjorsrapporter.auth.pdpTokenGetter
import no.nav.sokos.oppgjorsrapporter.config.PropertiesConfig
import no.nav.sokos.oppgjorsrapporter.config.TEAM_LOGS_MARKER
import no.nav.sokos.oppgjorsrapporter.rapport.OrgNr

interface PdpService {
    fun harTilgang(systembruker: Systembruker, orgnumre: Set<OrgNr>, ressurs: String): Boolean
}

class AltinnPdpService(val securityProperties: PropertiesConfig.SecurityProperties, val authClient: AuthClient) : PdpService {
    private val logger = KotlinLogging.logger {}
    val pdpClient =
        PdpClient(
            securityProperties.maskinportenProperties.altinn3BaseUrl.toURL().toString(),
            securityProperties.maskinportenProperties.subscriptionKey,
            authClient.pdpTokenGetter(securityProperties.maskinportenProperties.pdpScope),
        )

    override fun harTilgang(systembruker: Systembruker, orgnumre: Set<OrgNr>, ressurs: String): Boolean = runBlocking {
        logger.info(TEAM_LOGS_MARKER) { "PDP orgnr: $orgnumre, systembruker: $systembruker, ressurs: $ressurs" }
        runCatching {
                pdpClient.systemHarRettighetForOrganisasjoner(
                    systembrukerId = systembruker.userId,
                    orgnumre = orgnumre.map { it.raw }.toSet(),
                    ressurs = ressurs,
                )
            }
            .getOrDefault(false) // TODO: håndter feil ved å svare status 500/502 tilbake til bruker
    }
}

object LocalhostPdpService : PdpService {
    private val logger = KotlinLogging.logger {}

    override fun harTilgang(systembruker: Systembruker, orgnumre: Set<OrgNr>, ressurs: String): Boolean {
        logger.info(TEAM_LOGS_MARKER) { "Ingen PDP, har tilgang" }
        return true
    }
}

// Benytter default ingen tilgang i prod inntil vi ønsker å eksponere APIet via http
object IngenTilgangPdpService : PdpService {
    private val logger = KotlinLogging.logger {}

    override fun harTilgang(systembruker: Systembruker, orgnumre: Set<OrgNr>, ressurs: String): Boolean {
        logger.info(TEAM_LOGS_MARKER) { "Ingen PDP, ingen tilgang" }
        return false
    }
}
