package no.nav.sokos.oppgjorsrapporter.mq

import com.fasterxml.jackson.annotation.JsonFormat
import com.fasterxml.jackson.annotation.JsonRootName
import java.math.BigDecimal
import java.time.LocalDate
import no.nav.sokos.utils.Bankkonto
import no.nav.sokos.utils.Fnr
import no.nav.sokos.utils.OrgNr
import tools.jackson.core.JsonParser
import tools.jackson.databind.DeserializationContext
import tools.jackson.databind.ValueDeserializer
import tools.jackson.databind.annotation.JsonDeserialize
import tools.jackson.databind.module.SimpleModule
import tools.jackson.dataformat.xml.XmlMapper
import tools.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper
import tools.jackson.dataformat.xml.annotation.JacksonXmlProperty
import tools.jackson.module.kotlin.KotlinFeature
import tools.jackson.module.kotlin.KotlinModule

@JsonRootName(value = "rundata")
data class TrekkKredRapportBestilling(
    @JacksonXmlProperty(isAttribute = true) @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyyMMdd") val dato: LocalDate,
    @get:JacksonXmlProperty(localName = "brukerdata") val brukerData: Brukerdata,
) : RapportBestillingMsg() {
    companion object {
        val kotlinModule = KotlinModule.Builder().enable(KotlinFeature.StrictNullChecks).build()

        val xmlMapper =
            XmlMapper.builder()
                .addModule(SimpleModule().apply { addDeserializer(String::class.java, TrimmingDeserializer()) })
                .addModule(kotlinModule)
                .build()
    }

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
        @JsonDeserialize(using = OrgnnrDeserializer::class) val orgnummer: OrgNr,
        val kreditor: String,
        @JsonDeserialize(using = BankkontoDeserializer::class) val kontonummer: Bankkonto?,
        @get:JacksonXmlProperty(localName = "tssid") val tssId: String,
        @get:JacksonXmlProperty(localName = "rapfom")
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyyMMdd")
        val rapportFom: LocalDate,
        @get:JacksonXmlProperty(localName = "raptom")
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyyMMdd")
        val rapportTom: LocalDate,
        @get:JacksonXmlProperty(localName = "arkivref") @JacksonXmlElementWrapper(useWrapping = false) val arkivRefList: List<ArkivRef>,
        @get:JacksonXmlProperty(localName = "sumtot") val sumTotal: Belop,
    )

    data class Belop(@JsonDeserialize(using = BelopDeserializer::class) val belop: BigDecimal)

    data class ArkivRef(
        @get:JacksonXmlProperty(isAttribute = true) val nr: String,
        @get:JacksonXmlProperty(localName = "enhet") @JacksonXmlElementWrapper(useWrapping = false) val enhetList: List<Enhet>,
        @get:JacksonXmlProperty(localName = "delsumref") val delsumRef: Belop,
    )

    data class Enhet(
        @get:JacksonXmlProperty(isAttribute = true) val enhetnr: String,
        @get:JacksonXmlProperty(isAttribute = true) val navn: String,
        @get:JacksonXmlProperty(localName = "trekklinje")
        @JacksonXmlElementWrapper(useWrapping = false)
        val trekkLinjeList: List<TrekkLinje>,
        val delsum: Belop,
    )

    data class TrekkLinje(
        @get:JacksonXmlProperty(localName = "saksref") val saksreferanse: String,
        @get:JacksonXmlProperty(localName = "arborgnr") @JsonDeserialize(using = OrgnnrDeserializer::class) val arbeidgiverOrgnr: OrgNr,
        val fnr: Fnr,
        val navn: String,
        @get:JacksonXmlProperty(localName = "trekkfom")
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyyMMdd")
        val trekkFOM: LocalDate,
        @get:JacksonXmlProperty(localName = "trekktom")
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyyMMdd")
        val trekkTOM: LocalDate,
        @JsonDeserialize(using = BelopDeserializer::class) val belop: BigDecimal,
        @get:JacksonXmlProperty(localName = "kidstatus") val kidStatus: String,
        val dettssid: String,
    )
}

class BelopDeserializer : ValueDeserializer<BigDecimal>() {
    override fun deserialize(p: JsonParser?, ctxt: DeserializationContext?): BigDecimal? {
        val sanitized = p?.string?.trim()?.takeUnless { it.isEmpty() } ?: return null
        val normalized =
            when {
                sanitized.endsWith("-") -> "-" + sanitized.removeSuffix("-")
                else -> sanitized
            }
        return BigDecimal(normalized).movePointLeft(2)
    }
}

class TrimmingDeserializer : ValueDeserializer<String>() {
    override fun deserialize(p: JsonParser, ctxt: DeserializationContext) = p.string?.trimEnd()
}

class OrgnnrDeserializer : ValueDeserializer<OrgNr>() {
    override fun deserialize(p: JsonParser, ctxt: DeserializationContext) = p.string?.trim()?.trimStart('0')?.let { OrgNr(it) }
}

class BankkontoDeserializer : ValueDeserializer<Bankkonto?>() {
    override fun deserialize(p: JsonParser, ctxt: DeserializationContext) =
        p.string?.trim()?.trimStart('0')?.takeIf { it.isNotBlank() }?.let { Bankkonto(it) }
}
