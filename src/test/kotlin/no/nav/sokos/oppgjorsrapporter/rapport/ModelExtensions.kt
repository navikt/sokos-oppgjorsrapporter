package no.nav.sokos.oppgjorsrapporter.rapport

fun Rapport.medId(id: Long) = copy(id = Rapport.Id(id))

fun Rapport.medOrgNr(orgNr: String) = copy(orgNr = OrgNr(orgNr))

