package no.nav.sokos.oppgjorsrapporter.mq

import io.kotest.core.spec.style.FunSpec

class TrekkHendBestillingTest :
    FunSpec({
        context("TrekkHendBestilling.decode()") {
            test("kan lese xml av variant 'kreditor'") {
                val _ = TrekkHendBestilling.decode(javaClass.classLoader.getResourceAsStream("mq/trekk_hend_bestilling-kreditor.xml"))
            }
            test("kan lese xml av variant 'namsmann'") {
                val _ = TrekkHendBestilling.decode(javaClass.classLoader.getResourceAsStream("mq/trekk_hend_bestilling-namsmann.xml"))
            }
        }
    })
