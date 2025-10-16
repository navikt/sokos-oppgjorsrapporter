package no.nav.sokos.oppgjorsrapporter.auth

import kotlinx.coroutines.runBlocking

private fun AuthClient.hentAltinnToken(scope: String): () -> String {
    val maskinportenTokenGetter = tokenGetter(AuthClientIdentityProvider.MASKINPORTEN, target = scope)

    return {
        runBlocking {
            altinnExchange(maskinportenTokenGetter())
        }
    }
}

fun AuthClient.pdpTokenGetter(scope: String) = hentAltinnToken(scope)
