package no.nav.sokos.oppgjorsrapporter.entraid

import mu.KotlinLogging
import no.nav.sokos.oppgjorsrapporter.auth.EntraId
import no.nav.sokos.oppgjorsrapporter.config.PropertiesConfig.ApplicationProperties
import no.nav.sokos.oppgjorsrapporter.config.PropertiesConfig.AzureAdProperties
import no.nav.sokos.oppgjorsrapporter.config.TEAM_LOGS_MARKER
import no.nav.sokos.oppgjorsrapporter.rapport.RapportType
import no.nav.sokos.utils.OrgNr

interface InternTilgangService {

    fun harTilgangTilRessurs(bruker: EntraId, orgnr: OrgNr, rapportType: RapportType): Boolean

    fun rapportTyperBrukerHarTilgangTil(bruker: EntraId): Set<RapportType>
}

class EntraIdTilgangService(private val azureAd: AzureAdProperties, private val applicationProperties: ApplicationProperties) :
    InternTilgangService {
    private val logger = KotlinLogging.logger {}

    override fun harTilgangTilRessurs(bruker: EntraId, orgnr: OrgNr, rapportType: RapportType): Boolean {
        if (!applicationProperties.navOrgs.contains(orgnr)) return harTilgang(bruker, rapportType)
        return bruker.groups.contains(azureAd.adminGroup)
    }

    override fun rapportTyperBrukerHarTilgangTil(bruker: EntraId): Set<RapportType> {
        return RapportType.entries.filter { harTilgang(bruker, it) }.toSet()
    }

    private fun harTilgang(bruker: EntraId, rapportType: RapportType): Boolean {
        if (bruker.groups.contains(azureAd.adminGroup)) return true

        val gruppeUuid =
            when (rapportType) {
                RapportType.`ref-arbg` -> azureAd.refArbgGroup
                RapportType.`trekk-hend` -> azureAd.trekkHendGroup
                RapportType.`trekk-kred` -> azureAd.trekkKredGroup
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

    override fun harTilgangTilRessurs(bruker: EntraId, orgnr: OrgNr, rapportType: RapportType): Boolean {
        logger.info(TEAM_LOGS_MARKER) { "Ingen ENTRA_ID, har tilgang" }
        return true
    }

    override fun rapportTyperBrukerHarTilgangTil(bruker: EntraId): Set<RapportType> {
        logger.info(TEAM_LOGS_MARKER) { "Ingen ENTRA_ID, har tilgang" }
        return RapportType.entries.toSet()
    }
}
