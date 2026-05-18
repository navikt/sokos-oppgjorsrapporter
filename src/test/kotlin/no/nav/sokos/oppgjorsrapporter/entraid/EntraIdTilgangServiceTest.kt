package no.nav.sokos.oppgjorsrapporter.entraid

import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import no.nav.sokos.oppgjorsrapporter.auth.EntraId
import no.nav.sokos.oppgjorsrapporter.auth.Systembruker
import no.nav.sokos.oppgjorsrapporter.auth.TokenX
import no.nav.sokos.oppgjorsrapporter.config.PropertiesConfig
import no.nav.sokos.oppgjorsrapporter.rapport.RapportType
import no.nav.sokos.utils.OrgNr
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

class EntraIdTilgangServiceTest {
    private val props =
        mockk<PropertiesConfig.SecurityProperties> {
            every { azureAd.adminGroupUuid } returns "admin-uuid"
            every { azureAd.refArbgGroupUuid } returns "ref-arbg-uuid"
            every { azureAd.trekHendGroupUuid } returns "trekk-hend-uuid"
            every { azureAd.trekkKredGroupUuid } returns "trekk-kred-uuid"
        }

    private val entraIdTilgangService = EntraIdTilgangService(props)

    @ParameterizedTest
    @MethodSource("tilgangTestCases")
    fun `intern bruker har kun tilgang til rapporttyper som gruppen gir rett til`(
        gruppe: String,
        rapportType: RapportType,
        forventetTilgang: Boolean,
    ) {
        entraIdTilgangService.harTilgang(EntraId("user", listOf(gruppe)), rapportType) shouldBe forventetTilgang
    }

    @ParameterizedTest
    @MethodSource("rapportTyperBrukerHarTilgangTilCases")
    fun `rapportTyperBrukerHarTilgangTil returnerer rapporttyper som svarer til gruppen intern bruker hører til`(
        grupper: List<String>,
        rapportTyperBrukerHarTilgangTilCases: Set<RapportType>,
    ) {
        entraIdTilgangService.rapportTyperBrukerHarTilgangTil(EntraId("user", grupper)) shouldBe rapportTyperBrukerHarTilgangTilCases
    }

    companion object {
        @JvmStatic
        fun tilgangTestCases() =
            listOf(
                Arguments.of("admin-uuid", RapportType.`ref-arbg`, true),
                Arguments.of("admin-uuid", RapportType.`trekk-hend`, true),
                Arguments.of("admin-uuid", RapportType.`trekk-kred`, true),
                Arguments.of("ref-arbg-uuid", RapportType.`ref-arbg`, true),
                Arguments.of("ref-arbg-uuid", RapportType.`trekk-hend`, false),
                Arguments.of("ref-arbg-uuid", RapportType.`trekk-kred`, false),
                Arguments.of("trekk-hend-uuid", RapportType.`ref-arbg`, false),
                Arguments.of("trekk-hend-uuid", RapportType.`trekk-hend`, true),
                Arguments.of("trekk-hend-uuid", RapportType.`trekk-kred`, false),
                Arguments.of("trekk-kred-uuid", RapportType.`ref-arbg`, false),
                Arguments.of("trekk-kred-uuid", RapportType.`trekk-hend`, false),
                Arguments.of("trekk-kred-uuid", RapportType.`trekk-kred`, true),
                Arguments.of("ukjent-uuid", RapportType.`trekk-kred`, false),
            )

        @JvmStatic
        fun rapportTyperBrukerHarTilgangTilCases() =
            listOf(
                Arguments.of(listOf("admin-uuid"), setOf(RapportType.`ref-arbg`, RapportType.`trekk-hend`, RapportType.`trekk-kred`)),
                Arguments.of(listOf("ref-arbg-uuid"), setOf(RapportType.`ref-arbg`)),
                Arguments.of(listOf("trekk-hend-uuid"), setOf(RapportType.`trekk-hend`)),
                Arguments.of(listOf("trekk-kred-uuid"), setOf(RapportType.`trekk-kred`)),
                Arguments.of(listOf("trekk-hend-uuid", "trekk-kred-uuid"), setOf(RapportType.`trekk-hend`, RapportType.`trekk-kred`)),
            )
    }

    @Test
    fun `systembruker har ikke tilgang via intern tilgangssjekk`() {
        val harSystemBrukerTilgang = entraIdTilgangService.harTilgang(Systembruker("id", OrgNr("orgnr"), "system"), RapportType.`ref-arbg`)
        harSystemBrukerTilgang shouldBe false

        val harTokenXBrukerTilgang = entraIdTilgangService.harTilgang(TokenX("id", "acr"), RapportType.`ref-arbg`)
        harTokenXBrukerTilgang shouldBe false
    }
}
