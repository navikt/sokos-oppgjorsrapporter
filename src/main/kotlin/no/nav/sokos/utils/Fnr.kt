package no.nav.sokos.utils

import kotlinx.serialization.Serializable

@Serializable
@JvmInline
value class Fnr(val raw: String) {
    fun erGyldig(): Boolean = Validert.valider(raw).isSuccess

    fun somValidert(): Validert = Validert(raw)

    @Serializable
    @JvmInline
    value class Validert(val verdi: String) {
        init {
            val _ = valider(verdi).getOrThrow()
        }

        fun somUvalidert(): Fnr = Fnr(verdi)

        companion object {
            private val regex =
                (
                    // to første siffer er gyldig dag (+40 for D-nummer)
                    "(?:[04][1-9]|[1256]\\d|[37][01])" +
                        // to neste siffer er gyldig måned, med støtte for testpersoner (+40 for H-nummer (internt i Nav), +80 for
                        // TestNorge)
                        "(?:[048][1-9]|[159][012])" +
                        // resten av de 11 sifferne er tall
                        "\\d{7}")
                    .toRegex()

            val sifferVekter1 = listOf(3, 7, 6, 1, 8, 9, 4, 5, 2)
            val sifferVekter2 = listOf(5, 4, 3, 2, 7, 6, 5, 4, 3, 2)

            fun valider(fnr: String): Result<String> =
                if (regex.matches(fnr)) {
                    val sifre = fnr.map { it.digitToInt() }

                    val k1 = mod11(sifre.take(9), sifferVekter1)
                    val k2 = mod11(sifre.take(9) + k1, sifferVekter2)

                    if (10 in listOf(k1, k2) || k1 != sifre[9] || k2 != sifre[10]) {
                        Result.failure(IllegalArgumentException("Fnr er ugyldig (kontrollsifre = $k1 $k2): $fnr"))
                    } else {
                        Result.success(fnr)
                    }
                } else {
                    Result.failure(IllegalArgumentException("Fnr må være på gyldig format: $fnr"))
                }
        }
    }
}
