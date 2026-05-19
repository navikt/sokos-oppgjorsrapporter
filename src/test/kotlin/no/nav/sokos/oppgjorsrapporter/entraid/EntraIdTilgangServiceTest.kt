package no.nav.sokos.oppgjorsrapporter.entraid

import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import java.util.UUID
import no.nav.sokos.oppgjorsrapporter.TestUtil.EntraIdGroup
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
            every { azureAd.adminGroup } returns EntraIdGroup.ADMIN
            every { azureAd.refArbgGroup } returns EntraIdGroup.REF_ARBG
            every { azureAd.trekkHendGroup } returns EntraIdGroup.TREKK_HEND
            every { azureAd.trekkKredGroup } returns EntraIdGroup.TREKK_KRED
        }

    private val entraIdTilgangService = EntraIdTilgangService(props)

    @ParameterizedTest
    @MethodSource("tilgangTestCases")
    fun `intern bruker har kun tilgang til rapporttyper som gruppen gir rett til`(
        gruppe: UUID,
        rapportType: RapportType,
        forventetTilgang: Boolean,
    ) {
        entraIdTilgangService.harTilgang(EntraId("user", listOf(gruppe)), rapportType) shouldBe forventetTilgang
    }

    @ParameterizedTest
    @MethodSource("rapportTyperBrukerHarTilgangTilCases")
    fun `rapportTyperBrukerHarTilgangTil returnerer rapporttyper som svarer til gruppen intern bruker hører til`(
        grupper: List<UUID>,
        rapportTyperBrukerHarTilgangTilCases: Set<RapportType>,
    ) {
        entraIdTilgangService.rapportTyperBrukerHarTilgangTil(EntraId("user", grupper)) shouldBe rapportTyperBrukerHarTilgangTilCases
    }

    companion object {
        @JvmStatic
        fun tilgangTestCases() =
            listOf(
                Arguments.of(EntraIdGroup.ADMIN, RapportType.`ref-arbg`, true),
                Arguments.of(EntraIdGroup.ADMIN, RapportType.`trekk-hend`, true),
                Arguments.of(EntraIdGroup.ADMIN, RapportType.`trekk-kred`, true),
                Arguments.of(EntraIdGroup.REF_ARBG, RapportType.`ref-arbg`, true),
                Arguments.of(EntraIdGroup.REF_ARBG, RapportType.`trekk-hend`, false),
                Arguments.of(EntraIdGroup.REF_ARBG, RapportType.`trekk-kred`, false),
                Arguments.of(EntraIdGroup.TREKK_HEND, RapportType.`ref-arbg`, false),
                Arguments.of(EntraIdGroup.TREKK_HEND, RapportType.`trekk-hend`, true),
                Arguments.of(EntraIdGroup.TREKK_HEND, RapportType.`trekk-kred`, false),
                Arguments.of(EntraIdGroup.TREKK_KRED, RapportType.`ref-arbg`, false),
                Arguments.of(EntraIdGroup.TREKK_KRED, RapportType.`trekk-hend`, false),
                Arguments.of(EntraIdGroup.TREKK_KRED, RapportType.`trekk-kred`, true),
                Arguments.of(EntraIdGroup.RANDOM_GROUP, RapportType.`trekk-kred`, false),
            )

        @JvmStatic
        fun rapportTyperBrukerHarTilgangTilCases() =
            listOf(
                Arguments.of(listOf(EntraIdGroup.ADMIN), setOf(RapportType.`ref-arbg`, RapportType.`trekk-hend`, RapportType.`trekk-kred`)),
                Arguments.of(listOf(EntraIdGroup.REF_ARBG), setOf(RapportType.`ref-arbg`)),
                Arguments.of(listOf(EntraIdGroup.TREKK_HEND), setOf(RapportType.`trekk-hend`)),
                Arguments.of(listOf(EntraIdGroup.TREKK_KRED), setOf(RapportType.`trekk-kred`)),
                Arguments.of(
                    listOf(EntraIdGroup.TREKK_HEND, EntraIdGroup.TREKK_KRED),
                    setOf(RapportType.`trekk-hend`, RapportType.`trekk-kred`),
                ),
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
