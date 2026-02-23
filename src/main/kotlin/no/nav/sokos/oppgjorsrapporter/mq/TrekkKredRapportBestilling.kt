package no.nav.sokos.oppgjorsrapporter.mq

import com.fasterxml.jackson.annotation.JsonFormat
import com.fasterxml.jackson.annotation.JsonRootName
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import tools.jackson.core.JsonParser
import tools.jackson.databind.DeserializationContext
import tools.jackson.databind.annotation.JsonDeserialize
import tools.jackson.dataformat.xml.XmlMapper
import tools.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper
import tools.jackson.dataformat.xml.annotation.JacksonXmlProperty
import tools.jackson.module.kotlin.KotlinFeature
import tools.jackson.module.kotlin.KotlinModule

@JsonRootName(value = "rundata")
data class TrekkKredRapportBestilling(@get:JacksonXmlProperty(localName = "brukerdata") val brukerData: Brukerdata)

data class Brukerdata(val mottaker: Mottaker, val brevinfo: BrevInfo)

data class Mottaker(val navn: Navn, val adresse: TrekkKredAdresse)

data class Navn(@get:JacksonXmlProperty(localName = "fulltnavn") val fulltNavn: String)

data class TrekkKredAdresse(
    @get:JacksonXmlProperty(localName = "adresselinje1") val adresseLinje1: String,
    @get:JacksonXmlProperty(localName = "adresselinje2") val adresseLinje2: String,
    @get:JacksonXmlProperty(localName = "adresselinje3") val adresseLinje3: String,
    val postnr: String,
    val sted: String,
)

data class BrevInfo(
    @JacksonXmlProperty(isAttribute = true) @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyyMMdd") val brevdato: LocalDate,
    @get:JacksonXmlProperty(localName = "variablefelter") val variableFelter: VariableFelter,
)

data class VariableFelter(@get:JacksonXmlProperty(localName = "UR") val ur: UR)

data class UR(
    val orgnummer: String,
    val kreditor: String,
    val kontonummer: String,
    @get:JacksonXmlProperty(localName = "tssid") val ttsId: String,
    @get:JacksonXmlProperty(localName = "rapfom")
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyyMMdd")
    val rapportFOM: LocalDate,
    @get:JacksonXmlProperty(localName = "raptom")
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyyMMdd")
    val rapportTOM: LocalDate,
    @get:JacksonXmlProperty(localName = "arkivref") @JacksonXmlElementWrapper(useWrapping = false) val arkivRef: List<ArkivRef>,
    @get:JacksonXmlProperty(localName = "sumtot") val sumTotal: Belop,
)

data class Belop(@JsonDeserialize(using = BelopDeserializer::class) val belop: BigDecimal)

data class ArkivRef(
    @get:JacksonXmlProperty(isAttribute = true) val nr: String,
    @get:JacksonXmlProperty(localName = "enhet") @JacksonXmlElementWrapper(useWrapping = false) val enhet: List<Enhet>,
    @get:JacksonXmlProperty(localName = "delsumref") val delsumRef: Belop,
)

data class Enhet(
    @JacksonXmlProperty(isAttribute = true) val enhetnr: String,
    @JacksonXmlProperty(isAttribute = true) val navn: String,
    @JacksonXmlProperty(localName = "trekklinje") @JacksonXmlElementWrapper(useWrapping = false) val trekkLinje: List<TrekkLinje>,
    val delsum: Belop,
)

data class TrekkLinje(
    @JacksonXmlProperty(localName = "saksref") val saksreferanse: String,
    @JacksonXmlProperty(localName = "arborgnr") val arbeidgiverOrgnr: String,
    val fnr: String,
    val navn: String,
    @JacksonXmlProperty(localName = "trekkfom") val trekkFOM: String,
    @JacksonXmlProperty(localName = "trekktom") val trekkTOM: String,
    @JsonDeserialize(using = BelopDeserializer::class) val belop: BigDecimal,
    @JacksonXmlProperty(localName = "kidstatus") val kidStatus: String,
    val dettssid: String,
)

var kotlinModule =
    KotlinModule.Builder()
        .enable(KotlinFeature.StrictNullChecks) // Example: throw exception on null for non-nullable Kotlin types
        .build()

val xmlMapper = XmlMapper.builder().addModule(kotlinModule).build()

class BelopDeserializer : tools.jackson.databind.ValueDeserializer<BigDecimal>() {
    override fun deserialize(p: JsonParser?, ctxt: DeserializationContext?): BigDecimal? {
        val value = p?.string
        return if (value.isNullOrBlank()) {
            null
        } else {
            BigDecimal(value).divide(BigDecimal(100), 2, RoundingMode.HALF_UP)
        }
    }
}
