package serialization

import java.math.BigDecimal
import java.time.LocalDate
import kotlinx.serialization.json.Json
import net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson
import no.nav.sokos.oppgjorsrapporter.mq.Data
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class RefusjonsRapportBestillingSerializationTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `deserialize nullable LocalDate values properly`() {
        val input =
            """
                {
                    "navenhet": 1,
                    "bedriftsnummer": 123456789,
                    "kode": "A",
                    "tekst": "Test",
                    "fnr": "12345678901",
                    "navn": "Test Person",
                    "belop": 100.0,
                    "dk": "DK",
                    "fraDato": "2024-01-01",
                    "tilDato": "2024-12-31",
                    "maxDato": "  "
                }
            """
                .trimIndent()

        val data = json.decodeFromString<Data>(input)
        assertNull(data.maxDato)
        assertThat(data.fraDato).isEqualTo("2024-01-01")
        assertThat(data.tilDato).isEqualTo("2024-12-31")
    }

    @Test
    fun `serialize null maxdato as null`() {
        val data =
            Data(
                navenhet = 1,
                bedriftsnummer = 123456789,
                kode = "A",
                tekst = "Test",
                fnr = "12345678901",
                navn = "Test Person",
                belop = BigDecimal("100.0"),
                fraDato = LocalDate.parse("2024-01-01"),
                tilDato = LocalDate.parse("2024-12-31"),
                maxDato = null,
            )
        val output = json.encodeToString(data)
        // Should not contain a non-empty string for maxdato
        assertThatJson(output)
            .isEqualTo(
                """
                {
                    "navenhet": 1,
                    "bedriftsnummer": 123456789,
                    "kode": "A",
                    "tekst": "Test",
                    "fnr": "12345678901",
                    "navn": "Test Person",
                    "belop": 100.0,
                    "fraDato": "2024-01-01",
                    "tilDato": "2024-12-31",
                    "maxDato": null
                }
                """
                    .trimIndent()
            )
    }
}
