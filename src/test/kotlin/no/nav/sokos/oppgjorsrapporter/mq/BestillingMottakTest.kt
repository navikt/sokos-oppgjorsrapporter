package no.nav.sokos.oppgjorsrapporter.mq

import com.ibm.mq.jms.MQQueue
import com.ibm.msg.client.wmq.WMQConstants
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.FunSpec
import io.ktor.server.plugins.di.dependencies
import io.mockk.mockk
import io.mockk.verify
import javax.jms.Session
import kotlin.time.Duration.Companion.seconds
import no.nav.sokos.oppgjorsrapporter.TestContainer
import no.nav.sokos.oppgjorsrapporter.TestUtil
import no.nav.sokos.oppgjorsrapporter.config
import no.nav.sokos.oppgjorsrapporter.config.PropertiesConfig
import no.nav.sokos.oppgjorsrapporter.rapport.RapportService
import no.nav.sokos.oppgjorsrapporter.rapport.RapportType

class BestillingMottakTest :
    FunSpec({
        context("test-container") {
            val dbContainer = TestContainer.postgres
            val mqContainer = TestContainer.mq

            fun sendMelding(queueName: String, dokument: String, config: PropertiesConfig.MqProperties) =
                config.connect().use { connection ->
                    connection.createSession(Session.SESSION_TRANSACTED).use { session ->
                        val queue = (session.createQueue(queueName) as MQQueue).apply { targetClient = WMQConstants.WMQ_CLIENT_NONJMS_MQ }
                        val mqProducer = session.createProducer(queue)
                        val message = session.createTextMessage(dokument)
                        mqProducer.send(message)
                        session.commit()
                    }
                }

            test("BestillingMottak klarer å hente meldinger fra MQ") {
                val service: RapportService = mockk(relaxed = true)
                TestUtil.withFullApplication(dbContainer = dbContainer, mqContainer = mqContainer, { dependencies.provide { service } }) {
                    // Send melding til køen BestillingMottak leser fra, og verifiser at meldingen blir borte (og kanskje noe mer?)
                    val config = application.config()
                    val dokument = javaClass.getResource("/mq/refusjon_bestilling.json")?.readText()!!

                    sendMelding(
                        config.mqConfiguration.queues.find { it.rapportType == RapportType.`ref-arbg` }?.queueName!!,
                        dokument,
                        config.mqConfiguration,
                    )

                    eventually(5.seconds) {
                        verify(exactly = 1) {
                            val _ = service.lagreBestilling(any(), any(), any())
                        }
                    }
                }
            }
        }
    })
