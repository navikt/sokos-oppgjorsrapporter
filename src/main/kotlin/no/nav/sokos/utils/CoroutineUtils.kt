package no.nav.sokos.utils

import kotlinx.coroutines.*

// For forklaring av problemstillingene rundt "rogue" `CancellationException`s, se
// https://medium.com/better-programming/the-silent-killer-thats-crashing-your-coroutines-9171d1e8f79b, samt evt.
// https://github.com/Kotlin/kotlinx.coroutines/issues/3658 og
// https://medium.com/@muhammetemingundogar53/why-you-shouldnt-catch-cancellationexception-in-kotlin-coroutines-3a5880cca606

// Merk at *poenget* med denne exception-typen er at den *ikke* skal arve fra `CancellationException`, slik at greier som f.eks. stille
// dreper korutiner som kaster `CancellationException`s *ikke* dreper dem som følge av en "rogue" `CancellationException`.
//
// Merk videre at dette potensielt kan bli trøblete dersom en slik "rogue" `CancellationException` stammer fra java-kode, siden Kotlin sin
// `CancellationException` er et typealias for `java.util.concurrent.CancellationException`, og java-kode i liten grad har noe forhold til
// "jeg ønsker å kansellere den korutinen jeg er i"-betydningen av `CancellationException`.
// Skulle du komme opp i slikt trøbbel, så er den opprinnelige `CancellationException` tilgjengelig som `rogueExeption.cause`.
class RogueCancellationException(message: String, cause: CancellationException) : IllegalStateException(message, cause)

// Bruk denne i stedet for vanlig `runBlocking()`, slik at evt. `CancellationException` som kastes derfra ikke propagerer (og potensielt
// stille dreper en ikke-kansellert korutine).
// I likhet med `runBlocking()` er use-caset for denne funksjonen "jeg trenger å kalle suspend-funksjoner fra en ikke-suspend-kontekst".
fun <T> runBlockingIgnoringRogueCancellationException(block: suspend CoroutineScope.() -> T): T =
    try {
        runBlocking(block = block)
    } catch (e: CancellationException) {
        // Under antagelsen om at `runBlockingIgnoringRogueCansellationException()` ble kalt fra en ikke-suspend-kontekst, vil en
        // `CancellationException` som dukker opp her nødvendigvis måtte være en "rogue"-variant.
        throw RogueCancellationException("runBlocking -> 'rogue' CancellationException", e)
    }

// Når forskjellige `*Catching`-operasjoner returnerer et `Result`, kan det være gjemt en `CancellationException` inne i feil-tilfellet av
// resultatet.  Dette kan potensielt være en indikasjon på at korutinen som kalte `*Catching`-operasjonen har blitt kansellert - men kan
// også være en "rogue" `CancellationException`.  For å håndtere disse riktig, kan denne funksjonen brukes slik (fra `suspend`-kontekster):
//
//   runCatching { ... } // ... eller etter .mapCatching(), .recoverCatching(), etc.
//     .handleCancellationException()
//
// for å "løfte ut" `CancellationException` fra den `Result`-typede returverdien - enten ved å kaste `CancellationException` (dersom
// 'suspend'-funksjonen kjører i en korutine-kontekst som ikke lenger er aktiv), eller som en `RogueCancellationException` i failure-caset
// av `Result`-returverdien.
suspend fun <R> Result<R>.handleCancellationException(): Result<R> =
    fold(
        { Result.success(it) },
        { e ->
            when (e) {
                is CancellationException -> {
                    currentCoroutineContext().ensureActive()
                    Result.failure(RogueCancellationException("handleCancellationException -> 'rogue' CancellationException", e))
                }
                else -> Result.failure(e)
            }
        },
    )
