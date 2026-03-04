package no.nav.sokos.oppgjorsrapporter.blackjack

import kotlinx.serialization.Serializable

@Serializable
data class Kort(
    val verdi: String,
    val farge: String,
)

@Serializable
data class SpillerHånd(
    val kort: List<Kort>,
    val sum: Int,
)

@Serializable
data class DealerHånd(
    val synligeKort: List<Kort>,
    val skjultKort: Kort?,
    val sum: Int,
)

@Serializable
enum class SpillStatus {
    PÅGÅR,
    SPILLER_VANT,
    DEALER_VANT,
    UAVGJORT,
}

@Serializable
data class Spill(
    val id: String,
    val spillerHånd: SpillerHånd,
    val dealerHånd: DealerHånd,
    val status: SpillStatus,
    val melding: String,
)

internal data class SpillInternt(
    val id: String,
    val spillerKort: MutableList<Kort> = mutableListOf(),
    val dealerKort: MutableList<Kort> = mutableListOf(),
    var avsluttet: Boolean = false,
)

internal fun kortPoeng(kort: Kort): Int =
    when (kort.verdi) {
        "A" -> 11
        "K", "D", "J" -> 10
        else -> kort.verdi.toInt()
    }

internal fun håndSum(kort: List<Kort>): Int {
    var sum = kort.sumOf { kortPoeng(it) }
    var ess = kort.count { it.verdi == "A" }
    while (sum > 21 && ess > 0) {
        sum -= 10
        ess--
    }
    return sum
}

internal fun lagKortstokk(): ArrayDeque<Kort> {
    val farger = listOf("♠", "♥", "♣", "♦")
    val verdier = listOf("2", "3", "4", "5", "6", "7", "8", "9", "10", "J", "D", "K", "A")
    val kortstokk = farger.flatMap { farge -> verdier.map { verdi -> Kort(verdi, farge) } }.toMutableList()
    kortstokk.shuffle()
    return ArrayDeque(kortstokk)
}
