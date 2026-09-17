package no.nav.sokos.oppgjorsrapporter.util

import io.ktor.utils.io.*

fun rethrowCancellationException(e: Throwable) {
    if (e is CancellationException) throw e
}

// Når forskjellige `*Catching`-operasjoner returnerer et `Result`, kan det være gjemt en `CancellationException` inne i feil-tilfellet av
// resultatet.  I coroutines bør man ikke maskere slike exceptions.  Denne funksjonen kan brukes som:
//
//   runCatching { ... } // kan også brukes etter .mapCatching(), .recoverCatching(), etc.
//     .rethrowCancellationException()
//
// for å "løfte ut" `CancellationException` fra den `Result`-typede returverdien.
fun <R> Result<R>.rethrowCancellationException(): Result<R> = onFailure { rethrowCancellationException(it) }
