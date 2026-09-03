package no.nav.sokos.utils

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class TegnvaskingTest :
    FunSpec({
        context("vaskTegnUtenGlyf") {
            test("erstatter C1-kontrolltegn") { "Fáoo\u009A".vaskTegnUtenGlyf() shouldBe "Fáoo?" }
            test("erstatter C0-kontrolltegn") { "a\u0007b".vaskTegnUtenGlyf() shouldBe "a?b" }
            test("erstatter usynlige formateringstegn") { "a\u200Bb".vaskTegnUtenGlyf() shouldBe "a?b" }
            test("beholder norske og samiske bokstaver") { "Fáo Bárš æøå".vaskTegnUtenGlyf() shouldBe "Fáo Bárš æøå" }
            test("lar tekst uten slike tegn stå urørt") { "Veien 24".vaskTegnUtenGlyf() shouldBe "Veien 24" }
        }
    })
