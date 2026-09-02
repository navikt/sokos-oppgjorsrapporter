package no.nav.sokos.utils

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class TegnvaskTest :
    FunSpec({
        context("reparerFeiltolkedeTegn") {
            test("oversetter CP1252-tegn til riktig bokstav") { Tegnvask.reparerFeiltolkedeTegn("Foo\u009Akoda") shouldBe "Fooškoda" }
            test("fjerner tegn som er udefinert i CP1252") { Tegnvask.reparerFeiltolkedeTegn("Foo\u0081bar") shouldBe "Foobar" }
            test("beholder linjeskift og tabulator") { Tegnvask.reparerFeiltolkedeTegn("Foo\nbar\tbaz") shouldBe "Foo\nbar\tbaz" }
        }
    })
