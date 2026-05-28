package no.nav.sokos.oppgjorsrapporter.rapport

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class NevntInfoSerializationTest :
    FunSpec({
        test("serialisering av NevntInfo tar ikke med type-info") {
            UlagretRapport.NevntInfo.serialize(listOf<UlagretRapport.NevntInfo>(UlagretRapport.NevntVersjon(1))) shouldBe
                """
                [
                    {
                        "versjon": 1
                    }
                ]
                """
                    .trimIndent()
        }
    })
