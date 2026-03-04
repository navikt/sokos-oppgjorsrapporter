package no.nav.sokos.oppgjorsrapporter.blackjack

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class BlackjackService {
    private val aktiveSpill = ConcurrentHashMap<String, Pair<SpillInternt, ArrayDeque<Kort>>>()

    fun nyttSpill(): Spill {
        val id = UUID.randomUUID().toString()
        val kortstokk = lagKortstokk()
        val spill =
            SpillInternt(
                id = id,
                spillerKort = mutableListOf(kortstokk.removeFirst(), kortstokk.removeFirst()),
                dealerKort = mutableListOf(kortstokk.removeFirst(), kortstokk.removeFirst()),
            )
        aktiveSpill[id] = Pair(spill, kortstokk)

        if (håndSum(spill.spillerKort) == 21) {
            spill.avsluttet = true
            aktiveSpill.remove(id)
            return byggSpillrespons(spill, avslutter = true)
        }
        return byggSpillrespons(spill, avslutter = false)
    }

    fun hentSpill(id: String): Spill? = aktiveSpill[id]?.first?.let { byggSpillrespons(it, it.avsluttet) }

    fun trekk(id: String): Spill? {
        val (spill, kortstokk) = aktiveSpill[id] ?: return null
        if (spill.avsluttet) return byggSpillrespons(spill, avslutter = true)

        spill.spillerKort.add(kortstokk.removeFirst())

        if (håndSum(spill.spillerKort) > 21) {
            spill.avsluttet = true
        }
        if (spill.avsluttet) aktiveSpill.remove(id)
        return byggSpillrespons(spill, spill.avsluttet)
    }

    fun staa(id: String): Spill? {
        val (spill, kortstokk) = aktiveSpill[id] ?: return null
        if (spill.avsluttet) return byggSpillrespons(spill, avslutter = true)

        while (håndSum(spill.dealerKort) < 17) {
            spill.dealerKort.add(kortstokk.removeFirst())
        }
        spill.avsluttet = true
        aktiveSpill.remove(id)
        return byggSpillrespons(spill, avslutter = true)
    }

    private fun byggSpillrespons(
        spill: SpillInternt,
        avslutter: Boolean,
    ): Spill {
        val spillerSum = håndSum(spill.spillerKort)
        val dealerSum = håndSum(spill.dealerKort)

        val dealerHånd =
            if (avslutter) {
                DealerHånd(
                    synligeKort = spill.dealerKort,
                    skjultKort = null,
                    sum = dealerSum,
                )
            } else {
                DealerHånd(
                    synligeKort = listOf(spill.dealerKort.first()),
                    skjultKort = spill.dealerKort[1],
                    sum = kortPoeng(spill.dealerKort.first()),
                )
            }

        val (status, melding) =
            if (!avslutter) {
                SpillStatus.PÅGÅR to "Din tur – trekk et kort eller stå."
            } else if (spillerSum > 21) {
                SpillStatus.DEALER_VANT to "Du gikk over 21. Dealer vant!"
            } else if (dealerSum > 21) {
                SpillStatus.SPILLER_VANT to "Dealer gikk over 21. Du vant!"
            } else if (spillerSum == 21 && spill.spillerKort.size == 2) {
                SpillStatus.SPILLER_VANT to "Blackjack! Du vant!"
            } else if (spillerSum > dealerSum) {
                SpillStatus.SPILLER_VANT to "Du vant med $spillerSum mot $dealerSum!"
            } else if (dealerSum > spillerSum) {
                SpillStatus.DEALER_VANT to "Dealer vant med $dealerSum mot $spillerSum."
            } else {
                SpillStatus.UAVGJORT to "Uavgjort med $spillerSum!"
            }

        return Spill(
            id = spill.id,
            spillerHånd = SpillerHånd(kort = spill.spillerKort.toList(), sum = spillerSum),
            dealerHånd = dealerHånd,
            status = status,
            melding = melding,
        )
    }
}
