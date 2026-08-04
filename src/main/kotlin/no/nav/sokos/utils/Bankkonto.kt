package no.nav.sokos.utils

import kotlinx.serialization.Serializable

@Serializable
@JvmInline
value class Bankkonto(val raw: String) {
    fun erGyldig(): Boolean = Validert.valider(raw).isSuccess

    fun somValidert(): Validert = Validert(raw)

    @Serializable
    @JvmInline
    value class Validert(val verdi: String) {
        init {
            val _ = valider(verdi).getOrThrow()
        }

        fun somUvalidert(): Bankkonto = Bankkonto(verdi)

        companion object {
            // Valideringen her er i utgangspunktet basert på
            // https://www.bits.no/document/standard-for-kontonummer-i-norsk-banknaering-ver10/
            //
            // Kontonummeret må være 11 siffer, men standarden har noen begrensninger på kontoserie (siffer 5+6):
            //  * den kan ikke være 00 (reservert avregningskonti),
            //  * den kan ikke være i intervallet [90, 99] (reservert interne formål)
            //
            // Men: I praksis ser vi dog at det gjøres utbetalinger til kontonummer med kontoserie 90-99.
            //      Vi antar derfor at denne delen av standarden ikke etterleves, og lar slike kontonummer passere validering (i strid med
            //      standarden).
            private val regex = "\\d{4}(?!00)\\d{7}".toRegex()
            // Siffer 11 er et kontrollsiffer som mod11-beregnes av de første 10
            val sifferVekter = listOf(5, 4, 3, 2, 7, 6, 5, 4, 3, 2)

            fun valider(kontonr: String): Result<String> =
                if (regex.matches(kontonr)) {
                    val sifre = kontonr.map { it.digitToInt() }
                    val sum = mod11(sifre, sifferVekter)
                    if (sum == 10 || sum != sifre.last()) {
                        Result.failure(IllegalArgumentException("Bankkonto er ugyldig (kontrollsiffer = $sum): $kontonr"))
                    } else {
                        Result.success(kontonr)
                    }
                } else {
                    Result.failure(IllegalArgumentException("Bankkonto må være på gyldig format: $kontonr"))
                }
        }
    }
}
