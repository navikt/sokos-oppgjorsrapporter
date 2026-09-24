package no.nav.sokos.utils

import io.kotest.assertions.fail
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.types.shouldBeSameInstanceAs
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class CoroutineUtilsTest :
    FunSpec({
        context("runBlockingIgnoringRogueCancellationException") {
            test("skiller seg fra vanlig runBlocking") {
                shouldThrow<CancellationException> { testBlocking { runBlocking { throw CancellationException("i vanlig runBlocking") } } }
                shouldThrow<RogueCancellationException> {
                    testBlocking {
                        runBlockingIgnoringRogueCancellationException { throw CancellationException("i vår egen runBlocking-helper") }
                    }
                }
            }
        }

        context("handleCancellationException") {
            test("propagerer ikke kansellering videre hvis den mottar en 'rogue' CancellationException") {
                shouldThrow<CancellationException> {
                    runCatching { throw CancellationException("uten handleCancellationException()") }.getOrThrow()
                }
                shouldThrow<RogueCancellationException> {
                    runCatching { throw CancellationException("med handleCancellationException()") }
                        .handleCancellationException()
                        .getOrThrow()
                }
            }

            test("propagerer kansellering korrekt") {
                val job = launch {
                    val parentScope = this
                    val goAhead = Channel<Unit>()
                    launch {
                        goAhead.receive()
                        parentScope.cancel()
                    }
                    launch {
                        val result =
                            runCatching {
                                    // Gi den andre jobben beskjed om at den kan gjøre cancel() på foreldre-scopet som skal omfatte begge
                                    // sub-jobbene
                                    goAhead.send(Unit)
                                    // ... og kall en suspend-funksjon; denne skal bli avbrutt med en CancellationException så snart den
                                    // andre jobben har gjort cancel()
                                    repeat(10) { delay(timeMillis = 100) }
                                }
                                .handleCancellationException()
                        fail("Forventet at handleCancellationException() kastet CancellationException; fikk i stedet result = $result")
                    }
                }
                job.join()
            }

            test("endrer ikke på exceptions som ikke er CancellationException") {
                val forventet = RuntimeException(UUID.randomUUID().toString())
                val mottatt = shouldThrow<RuntimeException> { runCatching { throw forventet }.handleCancellationException().getOrThrow() }
                mottatt shouldBeSameInstanceAs forventet
            }
        }
    })

// Enkel ikke-suspend wrapper for å kunne kalle runBlocking* fra testene (som selv er suspend-funksjoner i kotest) uten warnings.
fun <T> testBlocking(block: () -> T): T = block()
