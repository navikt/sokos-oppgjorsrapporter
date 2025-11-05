package no.nav.sokos.oppgjorsrapporter.mq

import io.kotest.core.spec.style.FunSpec
import no.nav.sokos.oppgjorsrapporter.TestContainer
import no.nav.sokos.oppgjorsrapporter.TestUtil

class MqConsumerTest :
    FunSpec({
        context("test-container") {
            val dbContainer = TestContainer.postgres
            val mqContainer = TestContainer.mq

            test("MqConsumer klarer å hente meldinger fra MQ") {
                TestUtil.withFullApplication(dbContainer = dbContainer, mqContainer = mqContainer) {
                    // Send melding til køen RapportMottak leser fra, og verifiser at meldingen blir borte (og kanskje noe mer?)

                }
            }
        }
    })
