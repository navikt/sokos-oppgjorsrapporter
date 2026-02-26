package no.nav.sokos.oppgjorsrapporter.mq

import io.kotest.core.spec.style.FunSpec
import no.nav.sokos.oppgjorsrapporter.TestUtil
import no.nav.sokos.oppgjorsrapporter.mq.trekk_kred.TrekkKredRapportBestilling
import no.nav.sokos.oppgjorsrapporter.mq.trekk_kred.xmlMapper
import tools.jackson.module.kotlin.readValue

class TrekkKredRapportBestillingTest :
    FunSpec({ test("lese inn xml") { xmlMapper.readValue<TrekkKredRapportBestilling>(TestUtil.readFile("mq/trekk_kred_bestilling.xml")) } })
