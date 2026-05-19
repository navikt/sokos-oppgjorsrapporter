package no.nav.sokos.oppgjorsrapporter.entraid

import mu.KotlinLogging
import no.nav.sokos.oppgjorsrapporter.auth.AutentisertBruker
import no.nav.sokos.oppgjorsrapporter.auth.EntraId
import no.nav.sokos.oppgjorsrapporter.config.PropertiesConfig
import no.nav.sokos.oppgjorsrapporter.config.TEAM_LOGS_MARKER
import no.nav.sokos.oppgjorsrapporter.rapport.RapportType

interface InternTilgangService {
    fun harTilgang(bruker: AutentisertBruker, rapportType: RapportType): Boolean

    fun rapportTyperBrukerHarTilgangTil(bruker: AutentisertBruker): Set<RapportType>
}

class EntraIdTilgangService(private val securityProperties: PropertiesConfig.SecurityProperties) : InternTilgangService {
    private val logger = KotlinLogging.logger {}

    override fun harTilgang(bruker: AutentisertBruker, rapportType: RapportType): Boolean {
        if (bruker !is EntraId) {
            logger.warn(TEAM_LOGS_MARKER) { "Tilgangsjekk av en IKKE EntraId bruker: $bruker for rapport type: $rapportType" }
            return false
        }
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

    override fun rapportTyperBrukerHarTilgangTil(bruker: AutentisertBruker): Set<RapportType> {
        return RapportType.entries.filter { harTilgang(bruker, it) }.toSet()
    }
}

object LocalhostInternTilgangService : InternTilgangService {
    private val logger = KotlinLogging.logger {}

    override fun harTilgang(bruker: AutentisertBruker, rapportType: RapportType): Boolean {
        if (bruker !is EntraId) {
            logger.warn(TEAM_LOGS_MARKER) { "Tilgangsjekk av en IKKE EntraId bruker: $bruker for rapport type: $rapportType" }
            return false
        }

        logger.info(TEAM_LOGS_MARKER) { "Ingen ENTRA_ID, har tilgang" }
        return true
    }

    override fun rapportTyperBrukerHarTilgangTil(bruker: AutentisertBruker): Set<RapportType> {
        if (bruker !is EntraId) {
            logger.warn(TEAM_LOGS_MARKER) { "Tilgangsjekk av en IKKE EntraId bruker: $bruker" }
            return emptySet()
        }

        logger.info(TEAM_LOGS_MARKER) { "Ingen ENTRA_ID, har tilgang til alle rapport typer" }
        return RapportType.entries.toSet()
    }
}
