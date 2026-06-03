package no.nav.sokos.oppgjorsrapporter.tilgangsmaskin

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import io.mockk.mockk
import java.net.URI
import kotlinx.coroutines.test.runTest
import no.nav.sokos.oppgjorsrapporter.metrics.Metrics
import no.nav.sokos.oppgjorsrapporter.tilgangsmaskin.kontrakter.AvvisningskodeDTO
import no.nav.sokos.utils.Fnr
import no.nav.sokos.utils.genererGyldig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class TilgangsmaskinServiceTest {
    private val metrics = Metrics(mockk(relaxed = true))

    private fun mockClient(status: HttpStatusCode, body: String) =
        HttpClient(MockEngine { respond(ByteReadChannel(body), status, headersOf(HttpHeaders.ContentType, "application/json")) }) {
            install(ContentNegotiation) { json(TilgangsmaskinHttpClientSetup.jsonConfig) }
        }

    @Test
    fun `sjekkTilgang returnerer null når tilgangsmaskin svarer 204`() = runTest {
        val service = TilgangsmaskinService(URI("http://dummy-tilgangsmaskin-url"), mockClient(HttpStatusCode.NoContent, ""), metrics)
        assertThat(service.sjekkTilgang("oboToken", Fnr.genererGyldig().somUvalidert())).isNull()
    }

    @Test
    fun `sjekkTilgang returnerer PersonDetailResponseDTO når tilgangsmaskin svarer 403`() {
        val fnr = Fnr.genererGyldig().somUvalidert()
        runTest {
            val body =
                """{"title":"AVVIST_STRENGT_FORTROLIG_ADRESSE","begrunnelse":"Du har ikke tilgang til brukere med strengt fortrolig adresse","traceId":"444290be30ed4fdd9a849654bad9dc1b","brukerIdent":"${fnr.raw}","navIdent":"navIdent","kanOverstyres":false}"""
            val service = TilgangsmaskinService(URI("http://dummy-tilgangsmaskin-url"), mockClient(HttpStatusCode.Forbidden, body), metrics)

            val result = service.sjekkTilgang("oboToken", fnr)
            assertThat(result?.title).isEqualTo(AvvisningskodeDTO.AVVIST_STRENGT_FORTROLIG_ADRESSE)
            assertThat(result?.begrunnelse).isEqualTo("Du har ikke tilgang til brukere med strengt fortrolig adresse")
        }
    }

    @Test
    fun `sjekkTilgang kaster UnexpectedResponseException ved uventet svar fra tilgangsmaskinen med statuskode 500`() = runTest {
        val service =
            TilgangsmaskinService(URI("http://dummy-tilgangsmaskin-url"), mockClient(HttpStatusCode.InternalServerError, "{}"), metrics)

        assertThrows<UnexpectedResponseException> {
            val _ = service.sjekkTilgang("oboToken", Fnr.genererGyldig().somUvalidert())
        }
    }

    @Test
    fun `sjekkTilgang kaster UnexpectedResponseException ved uventet svar fra tilgangsmaskinen med statuskode 404`() = runTest {
        val service = TilgangsmaskinService(URI("http://dummy-tilgangsmaskin-url"), mockClient(HttpStatusCode.NotFound, "{}"), metrics)

        assertThrows<UnexpectedResponseException> {
            val _ = service.sjekkTilgang("oboToken", Fnr.genererGyldig().somUvalidert())
        }
    }
}
