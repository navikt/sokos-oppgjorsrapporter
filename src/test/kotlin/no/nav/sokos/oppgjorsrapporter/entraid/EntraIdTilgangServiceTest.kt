package no.nav.sokos.oppgjorsrapporter.entraid

import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import java.util.*
import no.nav.sokos.oppgjorsrapporter.TestUtil.EntraIdGroup
import no.nav.sokos.oppgjorsrapporter.TestUtil.Orgnrs.IKKE_NAV_ORGNR
import no.nav.sokos.oppgjorsrapporter.TestUtil.Orgnrs.NAV_ORGNR
import no.nav.sokos.oppgjorsrapporter.auth.EntraId
import no.nav.sokos.oppgjorsrapporter.config.PropertiesConfig
import no.nav.sokos.oppgjorsrapporter.rapport.RapportType
import no.nav.sokos.utils.OrgNr
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

class EntraIdTilgangServiceTest {
    private val azureAdProperties =
        mockk<PropertiesConfig.AzureAdProperties> {
            every { adminGroup } returns EntraIdGroup.ADMIN
            every { refArbgGroup } returns EntraIdGroup.REF_ARBG
            every { trekkHendGroup } returns EntraIdGroup.TREKK_HEND
            every { trekkKredGroup } returns EntraIdGroup.TREKK_KRED
        }
    private val applicationProperties = mockk<PropertiesConfig.ApplicationProperties> { every { navOrgs } returns listOf(NAV_ORGNR) }

    private val entraIdTilgangService = EntraIdTilgangService(azureAdProperties, applicationProperties)

    @ParameterizedTest
    @MethodSource("tilgangTestCases")
    fun `intern bruker har kun tilgang til resursser bruker har tilgang til`(
        gruppe: UUID,
        rapportType: RapportType,
        orgnr: OrgNr,
        forventetTilgang: Boolean,
    ) {
        entraIdTilgangService.harTilgangTilRessurs(EntraId("user", listOf(gruppe)), orgnr, rapportType) shouldBe forventetTilgang
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
                // OrgNr er en @JvmInline value class, så i bytecoden erstattes den med den underliggende typen String.
                // JUnit leser test-metoden via reflection på bytecoden og forventer derfor String som parameter — ikke OrgNr.
                // Når factory-metoden sender et OrgNr-objekt, blir det en type mismatch. Ved å sende .raw sender vi String direkte, som
                // matcher det JUnit forventer i test-metoden.
                Arguments.of(EntraIdGroup.ADMIN, RapportType.`ref-arbg`, NAV_ORGNR.raw, true),
                Arguments.of(EntraIdGroup.ADMIN, RapportType.`ref-arbg`, IKKE_NAV_ORGNR.raw, true),
                Arguments.of(EntraIdGroup.ADMIN, RapportType.`trekk-hend`, NAV_ORGNR.raw, true),
                Arguments.of(EntraIdGroup.ADMIN, RapportType.`trekk-hend`, IKKE_NAV_ORGNR.raw, true),
                Arguments.of(EntraIdGroup.ADMIN, RapportType.`trekk-kred`, NAV_ORGNR.raw, true),
                Arguments.of(EntraIdGroup.ADMIN, RapportType.`trekk-kred`, IKKE_NAV_ORGNR.raw, true),
                Arguments.of(EntraIdGroup.REF_ARBG, RapportType.`ref-arbg`, NAV_ORGNR.raw, false),
                Arguments.of(EntraIdGroup.REF_ARBG, RapportType.`ref-arbg`, IKKE_NAV_ORGNR.raw, true),
                Arguments.of(EntraIdGroup.REF_ARBG, RapportType.`trekk-hend`, NAV_ORGNR.raw, false),
                Arguments.of(EntraIdGroup.REF_ARBG, RapportType.`trekk-hend`, IKKE_NAV_ORGNR.raw, false),
                Arguments.of(EntraIdGroup.REF_ARBG, RapportType.`trekk-kred`, NAV_ORGNR.raw, false),
                Arguments.of(EntraIdGroup.REF_ARBG, RapportType.`trekk-kred`, IKKE_NAV_ORGNR.raw, false),
                Arguments.of(EntraIdGroup.TREKK_HEND, RapportType.`ref-arbg`, NAV_ORGNR.raw, false),
                Arguments.of(EntraIdGroup.TREKK_HEND, RapportType.`ref-arbg`, IKKE_NAV_ORGNR.raw, false),
                Arguments.of(EntraIdGroup.TREKK_HEND, RapportType.`trekk-hend`, NAV_ORGNR.raw, false),
                Arguments.of(EntraIdGroup.TREKK_HEND, RapportType.`trekk-hend`, IKKE_NAV_ORGNR.raw, true),
                Arguments.of(EntraIdGroup.TREKK_HEND, RapportType.`trekk-kred`, NAV_ORGNR.raw, false),
                Arguments.of(EntraIdGroup.TREKK_HEND, RapportType.`trekk-kred`, IKKE_NAV_ORGNR.raw, false),
                Arguments.of(EntraIdGroup.TREKK_KRED, RapportType.`ref-arbg`, NAV_ORGNR.raw, false),
                Arguments.of(EntraIdGroup.TREKK_KRED, RapportType.`ref-arbg`, IKKE_NAV_ORGNR.raw, false),
                Arguments.of(EntraIdGroup.TREKK_KRED, RapportType.`trekk-hend`, NAV_ORGNR.raw, false),
                Arguments.of(EntraIdGroup.TREKK_KRED, RapportType.`trekk-hend`, IKKE_NAV_ORGNR.raw, false),
                Arguments.of(EntraIdGroup.TREKK_KRED, RapportType.`trekk-kred`, NAV_ORGNR.raw, false),
                Arguments.of(EntraIdGroup.TREKK_KRED, RapportType.`trekk-kred`, IKKE_NAV_ORGNR.raw, true),
                Arguments.of(EntraIdGroup.RANDOM_GROUP, RapportType.`trekk-kred`, NAV_ORGNR.raw, false),
                Arguments.of(EntraIdGroup.RANDOM_GROUP, RapportType.`trekk-kred`, IKKE_NAV_ORGNR.raw, false),
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
}
