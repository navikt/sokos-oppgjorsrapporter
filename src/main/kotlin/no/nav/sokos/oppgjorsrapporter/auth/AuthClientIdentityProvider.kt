package no.nav.sokos.oppgjorsrapporter.auth

enum class AuthClientIdentityProvider(val verdi: String) {
    ENTRA_ID("entra_id"),
    IDPORTEN("idporten"),
    MASKINPORTEN("maskinporten"),
    TOKEN_X("tokenx"),
}
