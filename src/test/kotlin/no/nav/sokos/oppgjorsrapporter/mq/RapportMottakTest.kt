package no.nav.sokos.oppgjorsrapporter.mq

import com.ibm.mq.jms.MQQueue
import com.ibm.msg.client.wmq.WMQConstants
import io.kotest.core.spec.style.FunSpec
import io.ktor.server.plugins.di.dependencies
import io.mockk.mockk
import io.mockk.verify
import javax.jms.Session
import mu.KLogger
import mu.KotlinLogging
import no.nav.sokos.oppgjorsrapporter.TestContainer
import no.nav.sokos.oppgjorsrapporter.TestUtil
import no.nav.sokos.oppgjorsrapporter.config
import no.nav.sokos.oppgjorsrapporter.config.PropertiesConfig
import no.nav.sokos.oppgjorsrapporter.rapport.RapportRepository

private val logger: KLogger = KotlinLogging.logger {}

class RapportMottakTest :
    FunSpec({
        val mqContainer = TestContainer.mq
        val queueName = "refusjon_queue"

        beforeSpec { _ ->
            logger.info("Oppretter $queueName på MQ...")
            val result = mqContainer.execInContainer("bash", "-c", "echo 'define qlocal($queueName)' | runmqsc ${mqContainer.queueManager}")
            logger.info("Har opprettet kø på MQ: $result")
        }

        context("test-container") {
            val dbContainer = TestContainer.postgres
            fun sendMelding(config: PropertiesConfig.MqProperties, dokument: String) {
                val connection = config.connect()
                val session = connection.createSession(Session.SESSION_TRANSACTED)
                val queue = (session.createQueue(queueName) as MQQueue).apply { targetClient = WMQConstants.WMQ_CLIENT_NONJMS_MQ }
                val mqProducer = session.createProducer(queue)
                val message = session.createTextMessage(dokument)
                mqProducer.send(message)
                session.commit()
                session.close()
            }

            test("RapportMottak klarer å hente meldinger fra MQ") {
                val repository: RapportRepository = mockk()
                TestUtil.withFullApplication(
                    dbContainer = dbContainer,
                    mqContainer = mqContainer,
                    { dependencies.provide { repository } },
                ) {
                    // Send melding til køen RapportMottak leser fra, og verifiser at meldingen blir borte (og kanskje noe mer?)
                    val config = application.config()
                    logger.warn("application config: $config")
                    val dokument = this::class.java.getResource("/refusjon_bestilling.json")?.readText()!!
                    sendMelding(config.mqConfiguration, dokument)
                    verify(exactly = 1) { repository.lagreBestilling(any()) }
                }
            }
        }
    })
