package no.nav.sokos.utils

import kotlinx.serialization.Serializable

@Serializable
@JvmInline
value class OrgNr(val raw: String) {
    fun erGyldig(): Boolean = Validert.valider(raw).isSuccess

    fun somValidert(): Validert = Validert(raw)

    @Serializable
    @JvmInline
    value class Validert(val verdi: String) {
        init {
            val _ = valider(verdi).getOrThrow()
        }

        fun somUvalidert(): OrgNr = OrgNr(verdi)

        companion object {
            val regex = "\\d{9}".toRegex()
            // https://www.brreg.no/om-oss/registrene-vare/om-enhetsregisteret/organisasjonsnummeret/
            val sifferVekter = listOf(3, 2, 7, 6, 5, 4, 3, 2)

            fun valider(orgnr: String): Result<String> =
                if (regex.matches(orgnr)) {
                    val sifre = orgnr.map { it.digitToInt() }
                    val sum = mod11(sifre, sifferVekter)
                    if (sum == 10 || sum != sifre.last()) {
                        Result.failure(IllegalArgumentException("OrgNr er ugyldig (kontrollsiffer = $sum): $orgnr"))
                    } else {
                        Result.success(orgnr)
                    }
                } else {
                    Result.failure(IllegalArgumentException("OrgNr må være på gyldig format: $orgnr"))
                }
        }
    }
}
