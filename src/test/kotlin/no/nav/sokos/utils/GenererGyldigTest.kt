package no.nav.sokos.utils

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveAtLeastSize
import io.kotest.matchers.shouldBe
import kotlin.random.Random

class GenererGyldigTest :
    FunSpec({
        context("Generer gyldig Bankkonto") {
            test("med kjent random-seed") {
                val seed = 42
                val r1 = Random(seed)
                val gen1 = List(5) { Bankkonto.genererGyldig(random = r1) }
                val r2 = Random(seed)
                val gen2 = List(5) { Bankkonto.genererGyldig(random = r2) }
                gen1.distinct() shouldHaveAtLeastSize 4
                gen1 shouldBe gen2
            }
            test("med default random") {
                val gen = List(5) { Bankkonto.genererGyldig() }
                gen.distinct() shouldHaveAtLeastSize 4
            }
        }

        context("Generer gyldig Fnr") {
            test("med kjent random-seed") {
                val seed = 42
                val r1 = Random(seed)
                val gen1 = List(5) { Fnr.genererGyldig(random = r1) }
                val r2 = Random(seed)
                val gen2 = List(5) { Fnr.genererGyldig(random = r2) }
                gen1.distinct() shouldHaveAtLeastSize 4
                gen1 shouldBe gen2
            }
            test("med default random") {
                val gen = List(5) { Fnr.genererGyldig() }
                gen.distinct() shouldHaveAtLeastSize 4
            }
        }

        context("Generer gyldig OrgNr") {
            test("med kjent random-seed") {
                val seed = 42
                val r1 = Random(seed)
                val gen1 = List(5) { OrgNr.genererGyldig(random = r1) }
                val r2 = Random(seed)
                val gen2 = List(5) { OrgNr.genererGyldig(random = r2) }
                gen1.distinct() shouldHaveAtLeastSize 4
                gen1 shouldBe gen2
            }
            test("med default random") {
                val gen = List(5) { OrgNr.genererGyldig() }
                gen.distinct() shouldHaveAtLeastSize 4
            }
        }
    })
