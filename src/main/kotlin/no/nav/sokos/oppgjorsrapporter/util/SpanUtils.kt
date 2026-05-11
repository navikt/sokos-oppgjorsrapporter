package no.nav.sokos.oppgjorsrapporter.util

import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.StatusCode

// Skal brukes inne i metoder som er annotert med @WithSpan.  Uten kode a la dette vil evt. exceptions som oppstår inne i slike custom-spans
// ikke bli markert i tracene.
inline fun <T> handleSpanException(block: () -> T): T =
    try {
        block()
    } catch (e: Exception) {
        Span.current().recordException(e)
        Span.current().setStatus(StatusCode.ERROR)
        throw e
    }
