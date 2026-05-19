package no.nav.sokos.oppgjorsrapporter.entraid

import mu.KotlinLogging
import no.nav.sokos.oppgjorsrapporter.auth.EntraId
import no.nav.sokos.oppgjorsrapporter.config.PropertiesConfig
import no.nav.sokos.oppgjorsrapporter.config.TEAM_LOGS_MARKER
import no.nav.sokos.oppgjorsrapporter.rapport.RapportType

interface InternTilgangService {
    fun harTilgang(bruker: EntraId, rapportType: RapportType): Boolean

    fun rapportTyperBrukerHarTilgangTil(bruker: EntraId): Set<RapportType> {
        return RapportType.entries.filter { harTilgang(bruker, it) }.toSet()
    }
}

class EntraIdTilgangService(private val securityProperties: PropertiesConfig.SecurityProperties) : InternTilgangService {
    private val logger = KotlinLogging.logger {}

    override fun harTilgang(bruker: EntraId, rapportType: RapportType): Boolean {
        if (bruker.groups.contains(securityProperties.azureAd.adminGroup)) return true

        val gruppeUuid =
            when (rapportType) {
                RapportType.`ref-arbg` -> securityProperties.azureAd.refArbgGroup
                RapportType.`trekk-hend` -> securityProperties.azureAd.trekkHendGroup
                RapportType.`trekk-kred` -> securityProperties.azureAd.trekkKredGroup
            }
        val result = bruker.groups.contains(gruppeUuid)
        logger.debug(TEAM_LOGS_MARKER) {
            "Tilgangsjekk av AutentisertBruker bruker: $bruker for rapport type: $rapportType har tilgang: $result"
        }
        return result
    }
}

object LocalhostInternTilgangService : InternTilgangService {
    private val logger = KotlinLogging.logger {}

    override fun harTilgang(bruker: EntraId, rapportType: RapportType): Boolean {
        logger.info(TEAM_LOGS_MARKER) { "Ingen ENTRA_ID, har tilgang" }
        return true
    }
}
