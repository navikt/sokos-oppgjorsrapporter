package no.nav.sokos.oppgjorsrapporter.tilgangsmaskin.kontrakter

// Liste av begrunnelse ved avvist tilgang finnes i lenken under:
// https://confluence.adeo.no/spaces/TM/pages/628888614/Intro+til+Tilgangsmaskinen#IntrotilTilgangsmaskinen-Hvilkereglersjekkertilgangsmaskinen%3F
enum class AvvisningskodeDTO {
    AVVIST_STRENGT_FORTROLIG_ADRESSE,
    AVVIST_STRENGT_FORTROLIG_UTLAND,
    AVVIST_AVDØD,
    AVVIST_PERSON_UTLAND,
    AVVIST_SKJERMING,
    AVVIST_FORTROLIG_ADRESSE,
    AVVIST_UKJENT_BOSTED,
    AVVIST_GEOGRAFISK,
    AVVIST_HABILITET,
}
