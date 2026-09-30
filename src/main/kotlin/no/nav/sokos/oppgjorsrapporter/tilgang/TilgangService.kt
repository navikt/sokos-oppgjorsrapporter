package no.nav.sokos.oppgjorsrapporter.tilgang

import mu.KotlinLogging
import no.nav.sokos.oppgjorsrapporter.auth.AutentisertBruker
import no.nav.sokos.oppgjorsrapporter.auth.EntraId
import no.nav.sokos.oppgjorsrapporter.auth.Systembruker
import no.nav.sokos.oppgjorsrapporter.auth.TokenX
import no.nav.sokos.oppgjorsrapporter.config.TEAM_LOGS_MARKER
import no.nav.sokos.oppgjorsrapporter.pdp.PdpService
import no.nav.sokos.oppgjorsrapporter.rapport.RapportType
import no.nav.sokos.utils.OrgNr

class TilgangService(val pdpService: PdpService, val internTilgangService: InternTilgangService) {
    private val logger = KotlinLogging.logger {}

    suspend fun harTilgangTilRessurs(bruker: AutentisertBruker, rapportType: RapportType, orgnr: OrgNr): Boolean {
        logger.debug(TEAM_LOGS_MARKER) { "Skal sjekke om $bruker har tilgang til $rapportType for $orgnr" }
        when (bruker) {
            is Systembruker -> {
                if (!pdpService.harTilgang(bruker, setOf(orgnr), rapportType.altinnRessurs)) {
                    logger.info(TEAM_LOGS_MARKER) {
                        "Systembruker $bruker har forsøkt å aksessere rapport $rapportType for $orgnr, men PDP gir ikke tilgang"
                    }
                    return false
                }
            }

            is EntraId -> {
                if (!internTilgangService.harTilgangTilRessurs(bruker, orgnr, rapportType)) {
                    logger.info(TEAM_LOGS_MARKER) {
                        "Internbruker $bruker har forsøkt å aksessere rapport $rapportType for $orgnr, men hadde ikke riktig tilgang"
                    }
                    return false
                }
            }

            is TokenX -> {
                if (!pdpService.harTilgang(bruker, setOf(orgnr), rapportType.altinnRessurs)) {
                    logger.info(TEAM_LOGS_MARKER) {
                        "Personbruker $bruker har forsøkt å aksessere rapport $rapportType for $orgnr, men PDP gir ikke tilgang"
                    }
                    return false
                }
            }
        }
        return true
    }
}
