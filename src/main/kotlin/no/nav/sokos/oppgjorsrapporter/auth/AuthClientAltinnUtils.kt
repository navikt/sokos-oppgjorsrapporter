package no.nav.sokos.oppgjorsrapporter.auth

import no.nav.sokos.utils.runBlockingIgnoringRogueCancellationException

private fun AuthClient.altinnTokenGetter(scope: String): suspend () -> String {
    val maskinportenTokenGetter = tokenGetter(AuthClientIdentityProvider.MASKINPORTEN, target = scope)

    return { altinnExchange(maskinportenTokenGetter()) }
}

fun AuthClient.pdpTokenGetter(scope: String): () -> String = {
    runBlockingIgnoringRogueCancellationException { altinnTokenGetter(scope)() }
}

fun AuthClient.dialogportenTokenGetter(scope: String): suspend () -> String = altinnTokenGetter(scope)
