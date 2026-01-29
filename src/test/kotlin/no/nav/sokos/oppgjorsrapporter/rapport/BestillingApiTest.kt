package no.nav.sokos.oppgjorsrapporter.rapport

import io.kotest.extensions.testcontainers.toDataSource
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.restassured.RestAssured
import io.restassured.specification.RequestSpecification
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.random.Random
import no.nav.helsearbeidsgiver.utils.test.wrapper.TestPerson
import no.nav.helsearbeidsgiver.utils.test.wrapper.genererGyldig
import no.nav.helsearbeidsgiver.utils.wrapper.Fnr
import no.nav.helsearbeidsgiver.utils.wrapper.Orgnr
import no.nav.sokos.oppgjorsrapporter.TestUtil
import no.nav.sokos.oppgjorsrapporter.mq.Data
import no.nav.sokos.oppgjorsrapporter.mq.Header
import no.nav.sokos.oppgjorsrapporter.mq.RefusjonsRapportBestilling
import no.nav.sokos.oppgjorsrapporter.rapport.generator.Belop
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.threeten.extra.MutableClock

class BestillingApiTest : FullTestServer(MutableClock.of(Instant.parse("2025-11-22T12:00:00Z"), ZoneOffset.UTC)) {
    protected override val defaultClaims: Map<String, Any> = mapOf("NAVident" to "user", "groups" to listOf("group"))

    fun client(authToken: String = tokenFromDefaultProvider()): RequestSpecification =
        RestAssured.given()
            .header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            .header(HttpHeaders.Authorization, "Bearer $authToken")
            .port(embeddedServerPort)

    @Test
    fun `POST _api_bestilling_v1 (uten rapportType) gir feilmelding`() {
        val response =
            client()
                .body("")
                .post("/api/bestilling/v1")
                .then()
                .assertThat()
                .statusCode(HttpStatusCode.BadRequest.value)
                .extract()
                .response()!!
        assertThat(response.body().asString()).isEqualTo("")
    }

    @Test
    fun `POST _api_bestilling_v1 (med rapportType=ref-arbg men uten riktig body) gir feilmelding`() {
        val response =
            client()
                .queryParam("rapportType", "ref-arbg")
                .body("{}")
                .post("/api/bestilling/v1")
                .then()
                .assertThat()
                .statusCode(HttpStatusCode.BadRequest.value)
                .extract()
                .response()!!
        assertThat(response.body().asString()).startsWith("Feil i bestilling:")
    }

    @Test
    fun `POST _api_bestilling_v1 (med rapportType=ref-arbg body som bruker ikke-gyldige orgnr+fnr) gir feilmelding`() {
        val dokument = javaClass.getResource("/mq/refusjon_bestilling.json")?.readText()!!
        val response =
            client()
                .queryParam("rapportType", "ref-arbg")
                .body(dokument)
                .post("/api/bestilling/v1")
                .then()
                .assertThat()
                .statusCode(HttpStatusCode.BadRequest.value)
                .extract()
                .response()!!
        assertThat(response.body().asString()).startsWith("Bestillingen validerer ikke; fant følgende problemer:")
    }

    @Test
    fun `POST _api_bestilling_v1 (med rapportType=ref-arbg og riktig body) svarer riktig`() {
        TestUtil.loadDataSet("db/multiple.sql", dbContainer.toDataSource())

        val bestilling =
            genererBestilling(
                orgnr = Orgnr.genererGyldig(),
                valutert = LocalDate.now(),
                antallUnderenheter = 1,
                antallPersoner = 10,
                antallPosteringer = 15,
                ytelser = YtelseType.kjente.shuffled().take(7),
            )
        val dokument = RefusjonsRapportBestilling.json.encodeToString(bestilling)
        // Kommenter inn linja under og kjør denne test-metoden for å få et eksempel på et gyldig JSON-dokument
        // println(dokument)

        val response =
            client()
                .queryParam("rapportType", "ref-arbg")
                .body(dokument)
                .post("/api/bestilling/v1")
                .then()
                .assertThat()
                .statusCode(HttpStatusCode.NoContent.value)
                .extract()
                .response()!!
        assertThat(response.body().asString()).isEqualTo("")
    }

    data class YtelseType(val kode: String, val beskrivelse: String) {
        companion object {
            // Funnet ved å kjøre denne spørringen mot databasen i dev (2025-12-17):
            //
            //  WITH as_jsonb AS (SELECT id,
            //                         json(dokument)::jsonb AS jdok
            //                  FROM rapport_bestilling
            //                  WHERE dokument IS JSON),
            //     extracted AS (SELECT id,
            //                          jsonb_path_query(jdok, '$.datarec[*].kode')  AS kode,
            //                          jsonb_path_query(jdok, '$.datarec[*].tekst') AS tekst
            //                   FROM as_jsonb)
            // SELECT DISTINCT kode, tekst
            // FROM extracted
            // ORDER BY kode;
            val kjente =
                listOf(
                        "1" to "Foreldrepenger adopsjon",
                        "2" to "Foreldrepenger f¦dsel",
                        "2" to "Foreldrepenger renter",
                        "3" to "Svangerskapspenger",
                        "4" to "Sykepenger",
                        "5" to "Forsikret i arb.giverper.",
                        "6" to "Stort sykefrav{r",
                        "7" to "Gravide",
                        "8" to "Reisetilskudd",
                        "9" to "Pleiepenger",
                        "A" to "Trekk/Tilbakebetaling",
                        "F" to "Foreldrepenger feriepenge",
                        "H" to "Sykepenger feriepenger",
                        "I" to "Forsikr arbg per feriepng",
                        "J" to "Stort sykefrav{r feriepng",
                        "K" to "Gravide feriepenger",
                        "M" to "Pleie/oms/oppl{r feriepng",
                        "M" to "Refusjon",
                        "Y" to "Oppl{ringspenger",
                        "Y" to "Refusjon",
                        "Z" to "Omsorgspenger",
                    )
                    .map { YtelseType(it.first, it.second) }
        }
    }

    data class Person(val fnr: Fnr, val navn: String)

    fun genererBestilling(
        orgnr: Orgnr,
        valutert: LocalDate,
        underenheter: Set<Orgnr>,
        personer: Set<Person>,
        ytelser: Set<YtelseType>,
        antallPosteringer: Int,
    ): RefusjonsRapportBestilling {
        require(valutert <= LocalDate.now()) { "Valutert dato kan ikke være i fremtiden" }

        require(underenheter.size <= antallPosteringer) {
            "Kan ikke dekke ${underenheter.size} underenheter med bare $antallPosteringer posteringer"
        }
        require(personer.size <= antallPosteringer) { "Kan ikke dekke ${personer.size} personer med bare $antallPosteringer posteringer" }
        require(ytelser.size <= antallPosteringer) { "Kan ikke dekke ${ytelser.size} ytelser med bare $antallPosteringer posteringer" }

        val bankkonto = List(11) { Random.nextInt(10) }.joinToString("")

        fun <T> shuffledIterator(input: Set<T>): Iterator<T> =
            (input.shuffled().asSequence() + generateSequence { input.random() }).iterator()

        val ytelseSeq = shuffledIterator(ytelser)
        val underenhetSeq = shuffledIterator(underenheter)
        val personSeq = shuffledIterator(personer)

        val belopSeq = generateSequence { Belop(Random.nextDouble(30000.toDouble()).toBigDecimal()).verdi }.iterator()

        val dataRecSeq = generateSequence {
            val ytelse = ytelseSeq.next()
            val person = personSeq.next()
            Data(
                8020,
                underenhetSeq.next().verdi,
                ytelse.kode,
                ytelse.beskrivelse,
                person.fnr.verdi,
                person.navn,
                belopSeq.next(),
                null,
                null,
                null,
            )
        }

        val datarec = dataRecSeq.take(antallPosteringer).toList()
        val header = Header(orgnr.verdi, bankkonto, datarec.sumOf { it.belop }, valutert, datarec.size.toLong())

        return RefusjonsRapportBestilling(header, datarec)
    }

    fun genererBestilling(
        orgnr: Orgnr,
        valutert: LocalDate,
        antallUnderenheter: Int,
        antallPersoner: Int,
        antallPosteringer: Int,
        ytelser: List<YtelseType>,
    ): RefusjonsRapportBestilling =
        genererBestilling(
            orgnr,
            valutert,
            underenheter =
                buildSet {
                    while (size < antallUnderenheter) {
                        add(Orgnr.genererGyldig())
                    }
                },
            personer =
                buildSet {
                    while (size < antallPersoner) {
                        val fnr = Fnr.genererGyldig(forTestPerson = TestPerson.NAV)
                        val random = Random(fnr.verdi.toLong())
                        add(Person(fnr, genererNavn(random)))
                    }
                },
            ytelser = ytelser.toSet(),
            antallPosteringer = antallPosteringer,
        )

    fun genererNavn(random: Random): String {
        val antallFornavn = listOf(1, 1, 1, 2, 2, 3).random(random)
        val antallEtternavn = listOf(1, 1, 2, 2, 3).random(random)
        val fornavn = NavneData.vanligeFornavn.shuffled(random).take(antallFornavn)
        val etternavn = NavneData.vanligeEtternavn.shuffled(random).take(antallEtternavn)
        return (fornavn + etternavn).joinToString(" ")
    }
}

object NavneData {
    // Sakset fra https://www.ssb.no/befolkning/navn/statistikk/navn
    val vanligeFornavn =
        """
        2000	IDA	EMILIE	JULIE	THEA*	SARA*	INGRID*	MALIN	MARIA	MARTE*	NORA*
        2001	JULIE	SARA*	IDA	EMILIE	THEA*	NORA*	MARIA	HANNA*	INGRID*	MARTE*
        2002	SARA*	IDA	THEA*	JULIE	NORA*	EMMA	INGRID*	MARIA	EMILIE	HANNA*
        2003	EMMA	THEA*	IDA	JULIE	SARA*	NORA*	MARIA	EMILIE	HANNA*	INGRID*
        2004	EMMA	JULIE	IDA	THEA*	NORA*	EMILIE	MARIA	SARA*	HANNA*	INGRID*
        2005	THEA*	EMMA	SARA*	IDA	JULIE	HANNA*	EMILIE	NORA*	MALIN	INGRID*
        2006	THEA*	SARA*	EMMA	JULIE	NORA*	HANNA*	IDA	INGRID*	EMILIE	MARIA
        2007	SARA*	THEA*	EMMA	JULIE	NORA*	IDA	EMILIE	LINNEA*	INGRID*	ANNA
        2008	LINNEA*	EMMA	SARA*	NORA*	THEA*	IDA	SOFIE*	LEAH*	INGRID*	JULIE
        2009	EMMA	SARA*	LINNEA*	NORA*	SOFIE*	THEA*	IDA	EMILIE	INGRID*	MAJA*
        2010	EMMA	LINNEA*	SARA*	NORA*	SOFIE*	INGRID*	EMILIE	MAJA*	THEA*	IDA
        2011	EMMA	NORA*	SARA*	SOFIE*	MAJA*	THEA*	LINNEA*	INGRID*	EMILIE	JULIE
        2012	NORA*	EMMA	SOFIE*	LINNEA*	INGRID*	SARA*	EMILIE	SOFIA*	LEAH*	IDA*
        2013	SARA*	EMMA	SOFIE*	NORA*	MAJA*	INGRID*	SOFIA*	THEA*	LINNEA*	EMILIE
        2014	NORA*	EMMA	SARA*	SOFIE*	EMILIE	SOFIA*	MAJA*	THEA*	ANNA	LINNEA*
        2015	EMMA	NORA*	SARA*	SOFIE*	SOFIA*	OLIVIA	ELLA	EMILIE	LEAH*	ANNA*
        2016	NORA*	EMMA	SOFIE*	SARA*	SOFIA*	MAJA*	OLIVIA	ELLA	EMILIE	INGRID*
        2017	NORA*	SOFIE*	EMMA	SARA*	OLIVIA	MAJA*	ELLA	EMILIE	SOFIA*	INGRID*
        2018	EMMA	NORA*	OLIVIA	SARA*	MAJA*	EMILIE	SOFIE*	ELLA	LEAH*	AMALIE
        2019	EMMA	NORA*	ELLA	SOFIE*	OLIVIA	SOFIA*	ADA	MAJA*	FRIDA	INGRID
        2020	NORA*	EMMA	ELLA	MAJA*	OLIVIA	EMILIE	SOFIE*	LEA*	SOFIA*	INGRID
        2021	NORA*	EMMA	SOFIE*	OLIVIA*	ELLA	SOFIA*	MAJA*	LEAH*	FRIDA	INGRID*
        2022	NORA*	EMMA	OLIVIA*	ELLA	SOFIE*	LEAH*	FRIDA	IBEN	SOFIA*	SARA*
        2023	OLIVIA*	EMMA	ELLA	LEAH*	SOFIE*	NORA*	SOFIA*	MAJA*	ALMA	ADA
        2024	NORA*	EMMA	OLIVIA*	SOFIE*	ELLA	MAJA*	SOFIA*	LEAH*	SELMA	ELLINOR*
        2000	MARKUS*	KRISTIAN*	MARTIN	SANDER	KRISTOFFER*	MATHIAS*	ANDREAS	JONAS	HENRIK	DANIEL
        2001	MARKUS*	KRISTIAN*	MARTIN	ANDREAS	SANDER	KRISTOFFER*	MATHIAS*	HENRIK	JONAS	MARIUS
        2002	MARKUS*	KRISTIAN*	MARTIN	MATHIAS*	ANDREAS	SANDER	JONAS	KRISTOFFER*	HENRIK	MAGNUS
        2003	MATHIAS*	TOBIAS	JONAS	ANDREAS	KRISTIAN*	MARTIN	MARKUS*	SANDER	KRISTOFFER*	DANIEL
        2004	MATHIAS*	MARKUS*	MARTIN	ANDREAS	JONAS	KRISTIAN*	TOBIAS	DANIEL	ALEXANDER	SANDER
        2005	MARKUS*	KRISTIAN*	MATHIAS*	JONAS	TOBIAS	ALEXANDER*	ADRIAN	ANDREAS	MAGNUS	HENRIK
        2006	JONAS	MATHIAS*	ALEXANDER*	ELIAS	KRISTIAN*	ANDREAS	MARKUS*	SANDER	SEBASTIAN	EMIL
        2007	MATHIAS*	ALEXANDER*	LUCAS*	KRISTIAN*	JONAS	MARKUS*	EMIL	MAGNUS	ELIAS	HENRIK
        2008	LUCAS*	MATHIAS*	EMIL	MARKUS*	OLIVER	JONAS	MAGNUS	ALEXANDER*	KRISTIAN*	ANDREAS
        2009	LUCAS*	ALEXANDER*	EMIL	MATHIAS*	MAGNUS	OLIVER	MARKUS*	JONAS	WILLIAM	KRISTIAN*
        2010	LUCAS*	EMIL	MATHIAS*	WILLIAM	MARKUS*	MAGNUS	JONAS	KRISTIAN*	OLIVER	ALEXANDER*
        2011	LUCAS*	EMIL	MATHIAS*	MAGNUS	NOAH*	JONAS	FILIP*	WILLIAM	OLIVER	ALEXANDER*
        2012	LUCAS*	MATHIAS*	WILLIAM	EMIL	ALEXANDER*	JONAS	OSKAR*	MAGNUS	MARKUS*	OLIVER
        2013	FILIP*	LUCAS*	WILLIAM	OSKAR*	OLIVER	MATHIAS*	JAKOB*	ALEXANDER*	MAGNUS	ISAK*
        2014	WILLIAM	LUCAS*	EMIL	MARKUS*	MATHIAS*	JAKOB*	OSKAR*	FILIP*	MAGNUS	HENRIK
        2015	WILLIAM	FILIP*	LUCAS*	JAKOB*	OLIVER	MATHIAS*	AKSEL*	LIAM	OSKAR*	EMIL
        2016	WILLIAM	OSKAR*	LUCAS*	MATHIAS*	OLIVER	FILIP*	AKSEL*	JAKOB*	NOAH*	EMIL
        2017	JAKOB*	EMIL	OSKAR*	LUCAS*	OLIVER	FILIP*	NOAH*	WILLIAM	ELIAS	HENRIK
        2018	LUCAS*	FILIP*	OLIVER	OSKAR*	JAKOB*	EMIL	NOAH*	HENRIK	AKSEL*	ELIAS
        2019	JAKOB*	LUCAS*	FILIP*	OSKAR*	EMIL	WILLIAM	HENRIK	OLIVER	NOAH*	THEODOR*
        2020	JAKOB*	EMIL	NOAH*	OLIVER	FILIP*	WILLIAM	LUCAS*	LIAM	HENRIK	OSKAR*
        2021	NOAH*	OSKAR*	OLIVER	LUCAS*	ISAK*	AKSEL*	EMIL	FILIP*	JAKOB*	WILLIAM
        2022	JAKOB*	NOAH*	EMIL	LUCAS*	OLIVER	ISAK*	WILLIAM	FILIP*	AKSEL*	THEODOR*
        2023	LUCAS*	NOAH*	ISAK*	OLIVER	KASPER*	ELIAS	EMIL	JAKOB*	OSKAR*	THEODOR*
        2024	LUCAS*	NOAH*	OLIVER	EMIL	JAKOB*	WILLIAM	THEODOR*	LUDVIG*	LIAM	JOHANNES
        """
            .trimIndent()
            .split("[*]?\\s+".toRegex())
            .map { it.trim() }
            .filterNot { n -> n.isBlank() || n.all { it.isDigit() } }
            .toSet()

    val vanligeEtternavn =
        """
        Hansen
        Johansen
        Olsen
        Larsen
        Andersen
        Pedersen
        Nilsen
        Kristiansen
        Jensen
        Karlsen
        Johnsen
        Pettersen
        Eriksen
        Berg
        Haugen
        Hagen
        Johannessen
        Andreassen
        Jacobsen
        Dahl
        Lund
        Jørgensen
        Henriksen
        Halvorsen
        Jakobsen
        Sørensen
        Moen
        Strand
        Gundersen
        Iversen
        Solberg
        Svendsen
        Eide
        Martinsen
        Knutsen
        Paulsen
        Ali
        Bakken
        Nguyen
        Kristoffersen
        Lie
        Mathisen
        Amundsen
        Rasmussen
        Solheim
        Berge
        Lunde
        Bakke
        Nygård
        Moe
        Kristensen
        Fredriksen
        Holm
        Lien
        Hauge
        Ahmed
        Christensen
        Andresen
        Nielsen
        Knudsen
        Evensen
        Haugland
        Myhre
        Sæther
        Aas
        Thomassen
        Simonsen
        Hanssen
        Sivertsen
        Danielsen
        Berntsen
        Sandvik
        Rønning
        """
            .trimIndent()
            .split("\\s+".toRegex())
            .map { it.trim() }
            .filterNot { it.isBlank() }
            .toSet()
}
