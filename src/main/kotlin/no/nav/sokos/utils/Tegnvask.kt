package no.nav.sokos.utils

object Tegnvask {
    /** CP1252 definerer 27 tegn der ISO-8859-1 har kontrolltegn. */
    private val feiltolketCp1252 =
        mapOf(
            '\u0080' to "€",
            '\u0082' to "‚",
            '\u0083' to "ƒ",
            '\u0084' to "„",
            '\u0085' to "…",
            '\u0086' to "†",
            '\u0087' to "‡",
            '\u0088' to "ˆ",
            '\u0089' to "‰",
            '\u008A' to "Š",
            '\u008B' to "‹",
            '\u008C' to "Œ",
            '\u008E' to "Ž",
            '\u0091' to "'",
            '\u0092' to "'",
            '\u0093' to "\"",
            '\u0094' to "\"",
            '\u0095' to "•",
            '\u0096' to "–",
            '\u0097' to "—",
            '\u0098' to "˜",
            '\u0099' to "™",
            '\u009A' to "š",
            '\u009B' to "›",
            '\u009C' to "œ",
            '\u009E' to "ž",
            '\u009F' to "Ÿ",
        )

    /** Tegn ingen font kan tegne. Beholder \t, \n og \r. */
    private val ikkeVisbare = Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F-\\u009F\\uFEFF]")

    fun reparerFeiltolkedeTegn(tekst: String): String =
        tekst.map { feiltolketCp1252[it] ?: it.toString() }.joinToString("").replace(ikkeVisbare, "")
}
