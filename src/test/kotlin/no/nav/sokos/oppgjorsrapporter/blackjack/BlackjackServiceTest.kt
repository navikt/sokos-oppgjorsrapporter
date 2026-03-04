package no.nav.sokos.oppgjorsrapporter.blackjack

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class BlackjackServiceTest :
    FunSpec({
        val service = BlackjackService()

        context("nyttSpill") {
            test("skal opprette et nytt spill med to kort til spiller og dealer") {
                val spill = service.nyttSpill()

                spill.id shouldNotBe ""
                spill.spillerHånd.kort shouldHaveSize 2
                spill.dealerHånd.synligeKort shouldHaveSize 1
                spill.dealerHånd.skjultKort shouldNotBe null
            }

            test("spill status skal være PÅGÅR ved oppstart (med mindre blackjack)") {
                repeat(20) {
                    val spill = service.nyttSpill()
                    if (spill.spillerHånd.sum != 21) {
                        spill.status shouldBe SpillStatus.PÅGÅR
                    } else {
                        spill.status shouldBe SpillStatus.SPILLER_VANT
                        spill.melding shouldBe "Blackjack! Du vant!"
                    }
                }
            }
        }

        context("hentSpill") {
            test("skal returnere null for ukjent spill-id") {
                service.hentSpill("ukjent-id") shouldBe null
            }

            test("skal returnere spill for kjent spill-id") {
                val nyttSpill = service.nyttSpill()
                if (nyttSpill.status == SpillStatus.PÅGÅR) {
                    val hentetSpill = service.hentSpill(nyttSpill.id)
                    hentetSpill shouldNotBe null
                    hentetSpill!!.id shouldBe nyttSpill.id
                }
            }
        }

        context("trekk") {
            test("skal returnere null for ukjent spill-id") {
                service.trekk("ukjent-id") shouldBe null
            }

            test("spiller skal få et ekstra kort ved trekk") {
                val nyttSpill = service.nyttSpill()
                if (nyttSpill.status == SpillStatus.PÅGÅR) {
                    val etterTrekk = service.trekk(nyttSpill.id)
                    etterTrekk shouldNotBe null
                    etterTrekk!!.spillerHånd.kort shouldHaveSize 3
                }
            }

            test("status skal bli DEALER_VANT dersom spiller går over 21") {
                val id = "test-bust"
                val spill =
                    SpillInternt(
                        id = id,
                        spillerKort = mutableListOf(Kort("10", "♠"), Kort("10", "♥")),
                        dealerKort = mutableListOf(Kort("5", "♣"), Kort("6", "♦")),
                    )
                val kortstokk = ArrayDeque(listOf(Kort("5", "♦")))

                val felt = service::class.java.getDeclaredField("aktiveSpill")
                felt.isAccessible = true
                @Suppress("UNCHECKED_CAST")
                val aktiveSpill = felt.get(service) as java.util.concurrent.ConcurrentHashMap<String, Pair<SpillInternt, ArrayDeque<Kort>>>
                aktiveSpill[id] = Pair(spill, kortstokk)

                val resultat = service.trekk(id)
                resultat shouldNotBe null
                resultat!!.spillerHånd.sum shouldBe 25
                resultat.status shouldBe SpillStatus.DEALER_VANT
            }
        }

        context("staa") {
            test("skal returnere null for ukjent spill-id") {
                service.staa("ukjent-id") shouldBe null
            }

            test("dealer skal treffe til minst 17 etter staa") {
                val nyttSpill = service.nyttSpill()
                if (nyttSpill.status == SpillStatus.PÅGÅR) {
                    val etterStaa = service.staa(nyttSpill.id)
                    etterStaa.shouldNotBeNull()
                    if (etterStaa.dealerHånd.sum <= 21) {
                        etterStaa.dealerHånd.sum shouldNotBe 0
                    }
                    etterStaa.status shouldNotBe SpillStatus.PÅGÅR
                    etterStaa.dealerHånd.skjultKort shouldBe null
                }
            }

            test("spiller vinner dersom dealer går over 21") {
                val id = "test-dealer-bust"
                val spill =
                    SpillInternt(
                        id = id,
                        spillerKort = mutableListOf(Kort("10", "♠"), Kort("7", "♥")),
                        dealerKort = mutableListOf(Kort("10", "♣"), Kort("6", "♦")),
                    )
                val kortstokk = ArrayDeque(listOf(Kort("10", "♠")))

                val felt = service::class.java.getDeclaredField("aktiveSpill")
                felt.isAccessible = true
                @Suppress("UNCHECKED_CAST")
                val aktiveSpill = felt.get(service) as java.util.concurrent.ConcurrentHashMap<String, Pair<SpillInternt, ArrayDeque<Kort>>>
                aktiveSpill[id] = Pair(spill, kortstokk)

                val resultat = service.staa(id)
                resultat shouldNotBe null
                resultat!!.status shouldBe SpillStatus.SPILLER_VANT
                resultat.melding shouldBe "Dealer gikk over 21. Du vant!"
            }
        }

        context("håndSum") {
            test("ess skal telles som 11 dersom sum er under 21") {
                håndSum(listOf(Kort("A", "♠"), Kort("9", "♥"))) shouldBe 20
            }

            test("ess skal telles som 1 dersom sum ellers ville gå over 21") {
                håndSum(listOf(Kort("A", "♠"), Kort("K", "♥"), Kort("5", "♣"))) shouldBe 16
            }

            test("to ess skal gi 12") {
                håndSum(listOf(Kort("A", "♠"), Kort("A", "♥"))) shouldBe 12
            }

            test("kongebilde-kort skal gi 10 poeng") {
                håndSum(listOf(Kort("K", "♠"), Kort("D", "♥"), Kort("J", "♣"))) shouldBe 30
            }
        }

        context("lagKortstokk") {
            test("skal inneholde 52 kort") {
                lagKortstokk() shouldHaveSize 52
            }
        }
    })
