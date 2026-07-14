package no.nav.sokos.oppgjorsrapporter.mq

import com.fasterxml.jackson.annotation.JsonRootName
import java.time.LocalDate
import no.nav.sokos.oppgjorsrapporter.rapport.UlagretRapport
import no.nav.sokos.utils.Fnr
import no.nav.sokos.utils.OrgNr
import tools.jackson.databind.annotation.JsonDeserialize
import tools.jackson.databind.module.SimpleModule
import tools.jackson.dataformat.xml.XmlMapper
import tools.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper
import tools.jackson.module.kotlin.KotlinFeature
import tools.jackson.module.kotlin.KotlinModule
import tools.jackson.module.kotlin.readValue

@JsonRootName(value = "hendelseMottaker")
data class TrekkHendBestilling(
    val malPakke: String? = null,
    val typeMottaker: TypeMottaker,
    @JsonDeserialize(using = OrgnrDeserializer::class) val orgNr: OrgNr,
    val orgNavn: String? = null,
    val orgAdresse: Adresse? = null,
    val dato: LocalDate,
    val trekkhendelse: TrekkHendelse,
) : RapportBestillingMsg() {
    override fun nevntInfo(): List<UlagretRapport.NevntInfo> =
        listOf(UlagretRapport.NevntVersjon(1)) +
            when (typeMottaker) {
                TypeMottaker.kreditor -> trekkhendelse.kreditorMelding!!.flatMap { listOf(UlagretRapport.NevntFnr(it.debitorsFnr)) }

                TypeMottaker.namsmann -> trekkhendelse.namsmannMelding!!.flatMap { listOf(UlagretRapport.NevntFnr(it.debitorsFnr)) }
            }.distinct()

    // Vi gjør ikke noe validering for trekk-hend bestillinger.
    override fun valideringsFeil(): List<String> = emptyList()

    init {
        when (typeMottaker) {
            TypeMottaker.kreditor -> {
                require(trekkhendelse.namsmannMelding == null)
                require(trekkhendelse.kreditorMelding != null)
                require(trekkhendelse.kreditorMelding.size > 0)
            }
            TypeMottaker.namsmann -> {
                require(trekkhendelse.kreditorMelding == null)
                require(trekkhendelse.namsmannMelding != null)
                require(trekkhendelse.namsmannMelding.size > 0)
            }
        }
    }

    companion object {
        val kotlinModule = KotlinModule.Builder().enable(KotlinFeature.StrictNullChecks).build()

        private val xmlMapper =
            XmlMapper.builder()
                .addModule(SimpleModule().apply { addDeserializer(String::class.java, TrimmingDeserializer()) })
                .addModule(kotlinModule)
                .build()

        fun decode(dokument: String): TrekkHendBestilling = xmlMapper.readValue<TrekkHendBestilling>(dokument)
    }

    enum class TypeMottaker {
        kreditor,
        namsmann,
    }

    data class Adresse(
        val adresselinje1: String? = null,
        val adresselinje2: String? = null,
        val adresselinje3: String? = null,
        val adresselinje4: String? = null,
        val postnr: String? = null,
        val poststed: String? = null,
        val land: String? = null,
    )

    data class TrekkHendelse(
        @JacksonXmlElementWrapper(useWrapping = false) val kreditorMelding: List<KreditorMelding>? = null,
        @JacksonXmlElementWrapper(useWrapping = false) val namsmannMelding: List<NamsmannMelding>? = null,
    ) {
        data class KreditorMelding(
            @JsonDeserialize(using = OrgnrDeserializer::class) val namsmannsOrgNr: OrgNr?,
            val namsmannsNavn: String?,
            val kreditorsKidRef: String?,
            val debitorsFnr: Fnr,
            val debitorsNavn: String,
            val typeHendelse: String,
        )

        data class NamsmannMelding(
            @JsonDeserialize(using = OrgnrDeserializer::class) val kreditorsOrgNr: OrgNr?,
            val kreditorsNavn: String?,
            val kreditorsKidRef: String?,
            val debitorsFnr: Fnr,
            val debitorsNavn: String,
            val typeHendelse: String,
        )
    }
}
