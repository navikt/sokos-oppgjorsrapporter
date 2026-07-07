package no.nav.sokos.oppgjorsrapporter.mq

import com.fasterxml.jackson.annotation.JsonFormat
import com.fasterxml.jackson.annotation.JsonRootName
import java.math.BigDecimal
import java.time.LocalDate
import no.nav.sokos.oppgjorsrapporter.rapport.UlagretRapport
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
import tools.jackson.module.kotlin.readValue

@JsonRootName(value = "rundata")
data class TrekkKredRapportBestilling(
    @JacksonXmlProperty(isAttribute = true) @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyyMMdd") val dato: LocalDate,
    @get:JacksonXmlProperty(localName = "brukerdata") val brukerData: Brukerdata,
) : RapportBestillingMsg() {
    override fun nevntInfo(): List<UlagretRapport.NevntInfo> =
        listOf(UlagretRapport.NevntVersjon(1)) +
            brukerData.brevinfo.variableFelter.ur.arkivRefList
                .flatMap { it.enhetList }
                .flatMap { it.trekkLinjeList }
                .map { UlagretRapport.NevntFnr(it.fnr) }
                .distinct()

    override fun valideringsFeil(): List<String> {
        val urData = brukerData.brevinfo.variableFelter.ur
        return listOfNotNull(
            // Orgnr skal være gyldige
            urData.orgnummer.takeUnless { it.erGyldig() }?.let { "Ikke gyldig orgnr: ${it.raw}" },

            // kontonummer skal ikke være null
            if (urData.kontonummer == null) "Kontonummer må være angitt" else null,

            // Totalsum skal være lik delsummene i alle arkiv referansene
            Pair(urData.sumTotal.belop, urData.arkivRefList.sumOf { it.delsumRef.belop })
                .takeUnless { (tot, sumArkivRef) -> tot == sumArkivRef }
                ?.let { (tot, sumArkivRef) -> "Totalsum for bestilling ($tot) stemmer ikke med summen av arkivrefs ($sumArkivRef)" },
            // For hver arkivreferanse skal delsummen være lik delsummene for enheter
            *(urData.arkivRefList
                .map { arkivRef ->
                    Pair(arkivRef.delsumRef.belop, arkivRef.enhetList.sumOf { it.delsum.belop })
                        .takeUnless { (delsumArkivRef, sumEnheter) -> delsumArkivRef == sumEnheter }
                        ?.let { (delsumArkivRef, sumEnheter) ->
                            "I arkivref ${arkivRef.nr} stemmer ikke delsum ($delsumArkivRef) med summen av dens enheter ($sumEnheter)"
                        }
                }
                .toTypedArray()),
            // For hver enhet skal summen av trekklinjene være lik delsum enhet
            *(urData.arkivRefList
                .flatMap { arkivRef ->
                    arkivRef.enhetList.map { enhet ->
                        Pair(enhet.delsum.belop, enhet.trekkLinjeList.sumOf { it.belop })
                            .takeUnless { (delsumEnhet, sumTrekklinjer) -> delsumEnhet == sumTrekklinjer }
                            ?.let { (delsumEnhet, sumTrekklinjer) ->
                                "I arkivref/enhet ${arkivRef.nr}/${enhet.enhetnr} stemmer ikke delsum ($delsumEnhet) med summen av dens trekklinjer ($sumTrekklinjer)"
                            }
                    }
                }
                .toTypedArray()),
        )
    }

    companion object {
        val kotlinModule = KotlinModule.Builder().enable(KotlinFeature.StrictNullChecks).build()

        val xmlMapper =
            XmlMapper.builder()
                .addModule(SimpleModule().apply { addDeserializer(String::class.java, TrimmingDeserializer()) })
                .addModule(kotlinModule)
                .build()

        fun decode(dokument: String): TrekkKredRapportBestilling {
            // Dersom XML-dokumentet påstår at det er i enkoding ISO-8859-1, men inneholder tegn i rangen [0x80-0x9f] (som er
            // "undefined" i det tegnsettet), så er det /sannsynligvis/ egentlig enkodet i Windows Codepage 1252.
            // Java sin "decode som ISO-8859-1" har ikke noen mekanisme for å gi feilmelding om at enkelte byte-values ikke
            // faktisk er definert i tegnsettet ISO-8859-1, men legger heller rått inn enhver slik udefinert byte-value som
            // tilsvarende char-value... og det like pdfgenrs dårlig.
            // Vi vasker derfor bort slike tegn - ved å gjette på at CP 1252 "en-dash" og "em-dash" skal være vanlig "-",
            // mens andre udefinerte byte-verdier blir "?"
            val vasket: String? =
                "\\A<[?]xml\\s(.*?)\\s[?]>".toRegex().find(dokument.trim())?.let { xmlDecl ->
                    if (xmlDecl.groups[1]?.value?.contains("""encoding="ISO-8859-1"""") ?: false) {
                        dokument.replace("[\\x80-\\x9f]".toRegex()) { undef ->
                            if (undef.value == "\u0096" || undef.value == "\u0097") "-" else "?"
                        }
                    } else null
                }
            return xmlMapper.readValue(vasket ?: dokument)
        }
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
        // TODO Dette må endres for å kunne motta trekkoppgjør til fnr (TOB-6780)
        @JsonDeserialize(using = OrgnrDeserializer::class) val orgnummer: OrgNr,
        val kreditor: String,
        // kontonummer kan være utenlandsk eller norsk konto, i motsettning til ref-arbg
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
        @get:JacksonXmlProperty(localName = "arborgnr") @JsonDeserialize(using = OrgnrDeserializer::class) val arbeidgiverOrgnr: OrgNr,
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

class OrgnrDeserializer : ValueDeserializer<OrgNr>() {
    override fun deserialize(p: JsonParser, ctxt: DeserializationContext) =
        p.string
            ?.trim()
            ?.let { trimmed ->
                if (trimmed.all { it == '0' }) {
                    ""
                } else {
                    val børKuttes = trimmed.length - 9
                    if (børKuttes > 0 && trimmed.take(børKuttes).all { it == '0' }) {
                        trimmed.drop(børKuttes)
                    } else {
                        trimmed
                    }
                }
            }
            ?.let { OrgNr(it) }
}

class BankkontoDeserializer : ValueDeserializer<Bankkonto?>() {
    override fun deserialize(p: JsonParser, ctxt: DeserializationContext) =
        p.string?.trim()?.trimStart('0')?.takeIf { it.isNotBlank() }?.let { Bankkonto(it) }
}
