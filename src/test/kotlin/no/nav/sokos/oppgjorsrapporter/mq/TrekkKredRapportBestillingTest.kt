package no.nav.sokos.oppgjorsrapporter.mq

import io.kotest.core.spec.style.FunSpec
import no.nav.sokos.oppgjorsrapporter.TestUtil
import tools.jackson.module.kotlin.readValue

class TrekkKredRapportBestillingTest :
    FunSpec({
        test("lese inn xml") {
            val hei = xmlMapper.readValue<TrekkKredRapportBestilling>(TestUtil.readFile("mq/T14_fra_mq.xml"))
            println(hei)
        }
    })
