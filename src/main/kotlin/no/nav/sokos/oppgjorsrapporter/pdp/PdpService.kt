package no.nav.sokos.oppgjorsrapporter.pdp

import kotlinx.coroutines.runBlocking
import no.nav.helsearbeidsgiver.altinn.pdp.PdpClient
import no.nav.helsearbeidsgiver.utils.log.sikkerLogger
import no.nav.sokos.oppgjorsrapporter.auth.AuthClient
import no.nav.sokos.oppgjorsrapporter.auth.pdpTokenGetter
import no.nav.sokos.oppgjorsrapporter.config.PropertiesConfig

interface PdpService {
    fun harTilgang(systembruker: String, orgnumre: Set<String>, ressurs: String): Boolean
}

class AltinnPdpService(val securityProperties: PropertiesConfig.SecurityProperties, val authClient: AuthClient) : PdpService {
    val pdpClient =
        PdpClient(
            securityProperties.maskinportenProperties.altinn3BaseUrl.toURL().toString(),
            securityProperties.maskinportenProperties.subscriptionKey,
            authClient.pdpTokenGetter(securityProperties.maskinportenProperties.pdpScope),
        )

    override fun harTilgang(systembruker: String, orgnumre: Set<String>, ressurs: String): Boolean = runBlocking {
        sikkerLogger().info("PDP orgnr: $orgnumre, systembruker: $systembruker, ressurs: $ressurs")
        runCatching { pdpClient.systemHarRettighetForOrganisasjoner(systembrukerId = systembruker, orgnumre = orgnumre, ressurs = ressurs) }
            .getOrDefault(false) // TODO: håndter feil ved å svare status 500/502 tilbake til bruker
    }
}

object LocalhostPdpService : PdpService {
    override fun harTilgang(systembruker: String, orgnumre: Set<String>, ressurs: String): Boolean {
        sikkerLogger().info("Ingen PDP, har tilgang")
        return true
    }
}

// Benytter default ingen tilgang i prod inntil vi ønsker å eksponere APIet via http
object IngenTilgangPdpService : PdpService {
    override fun harTilgang(systembruker: String, orgnumre: Set<String>, ressurs: String): Boolean {
        sikkerLogger().info("Ingen PDP, ingen tilgang")
        return false
    }
}
