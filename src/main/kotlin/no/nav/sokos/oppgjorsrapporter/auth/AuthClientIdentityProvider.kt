package no.nav.sokos.oppgjorsrapporter.auth

enum class AuthClientIdentityProvider(
    val verdi: String,
) {
    AZURE_AD("azuread"),
    IDPORTEN("idporten"),
    MASKINPORTEN("maskinporten"),
    TOKEN_X("tokenx"),
}
