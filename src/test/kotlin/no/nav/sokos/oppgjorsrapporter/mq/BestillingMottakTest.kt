package no.nav.sokos.oppgjorsrapporter.mq

import com.ibm.mq.jms.MQQueue
import com.ibm.msg.client.wmq.WMQConstants
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.FunSpec
import io.ktor.server.plugins.di.dependencies
import io.mockk.Called
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import java.time.LocalDate
import javax.jms.Session
import kotlin.time.Duration.Companion.seconds
import no.nav.sokos.oppgjorsrapporter.TestContainer
import no.nav.sokos.oppgjorsrapporter.TestUtil
import no.nav.sokos.oppgjorsrapporter.config
import no.nav.sokos.oppgjorsrapporter.config.ApplicationState
import no.nav.sokos.oppgjorsrapporter.config.PropertiesConfig
import no.nav.sokos.oppgjorsrapporter.ereg.EregService
import no.nav.sokos.oppgjorsrapporter.ereg.Navn
import no.nav.sokos.oppgjorsrapporter.ereg.Organisasjon
import no.nav.sokos.oppgjorsrapporter.rapport.RapportService
import no.nav.sokos.oppgjorsrapporter.rapport.RapportType
import no.nav.sokos.oppgjorsrapporter.withEnabledBakgrunnsJobb

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

            test("BestillingMottak nekter å motta meldinger til opphørt orgnr") {
                val service: RapportService = mockk(relaxed = true)
                val eregService: EregService = mockk()
                coEvery { eregService.hentNoekkelInfo(any()) } answers
                    {
                        Organisasjon(firstArg(), Navn("Et navn"), opphoersdato = LocalDate.parse("2020-01-01"))
                    }
                TestUtil.withFullApplication(
                    dbContainer = dbContainer,
                    mqContainer = mqContainer,
                    dependencyOverrides = {
                        dependencies.provide { service }
                        dependencies.provide { eregService }
                    },
                ) {
                    // Send melding til køen BestillingMottak leser fra, og verifiser at meldingen blir borte (og kanskje noe mer?)
                    val config = application.config()
                    val dokument = javaClass.getResource("/mq/refusjon_bestilling_gyldig.json")?.readText()!!

                    val applicationState: ApplicationState = application.dependencies.resolve()
                    applicationState.withEnabledBakgrunnsJobb<BestillingMottak> {
                        sendMelding(config.mq.queues.find { it.rapportType == RapportType.`ref-arbg` }?.queueName!!, dokument, config.mq)

                        eventually(5.seconds) {
                            coVerify() {
                                val _ = eregService.hentNoekkelInfo(any())
                            }
                        }
                        verify(timeout = 500) { service wasNot Called }
                    }
                }
            }

            test("BestillingMottak klarer å hente meldinger fra MQ") {
                val service: RapportService = mockk(relaxed = true)
                val eregService: EregService = mockk()
                coEvery { eregService.hentNoekkelInfo(any()) } answers { Organisasjon(firstArg(), Navn("Et navn")) }
                TestUtil.withFullApplication(
                    dbContainer = dbContainer,
                    mqContainer = mqContainer,
                    dependencyOverrides = {
                        dependencies.provide { service }
                        dependencies.provide { eregService }
                    },
                ) {
                    // Send melding til køen BestillingMottak leser fra, og verifiser at meldingen blir borte (og kanskje noe mer?)
                    val config = application.config()
                    val dokument = javaClass.getResource("/mq/refusjon_bestilling_gyldig.json")?.readText()!!

                    val applicationState: ApplicationState = application.dependencies.resolve()
                    applicationState.withEnabledBakgrunnsJobb<BestillingMottak> {
                        sendMelding(config.mq.queues.find { it.rapportType == RapportType.`ref-arbg` }?.queueName!!, dokument, config.mq)

                        eventually(5.seconds) {
                            verify(exactly = 1) {
                                val _ = service.lagreBestilling(any(), any(), any())
                            }
                        }
                    }
                }
            }
        }
    })
