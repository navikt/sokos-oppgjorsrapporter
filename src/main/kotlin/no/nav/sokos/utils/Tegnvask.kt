package no.nav.sokos.utils

/**
 * Tegn som mangler glyf i Source Sans Pro, og som derfor får PDF/A-eksporten i pdfgenrs til å feile med `the text "\u{9a}" could not be
 * displayed with font "Source Sans Pro"` - og det gjør at pdf generering for rapporten feiler.
 *
 * Dekker C0-kontrolltegn, DEL, C1-kontrolltegn, samt usynlige formateringstegn og BOM.
 *
 * Bakgrunn: Ereg returnerte en adresse som ligner på `"Fáo\u009A Bár 2"` for et orgnr. Tegnet stammer trolig fra en tegnsettforveksling et
 * sted i ereg, men vi har ikke sikker informasjon om hva det opprinnelig var. Vi erstatter derfor med `?` framfor å gjette på en bokstav -
 * en synlig feil er bedre enn en feilstavet adresse i et utgående brev.
 */
private val tegnUtenGlyf = Regex("[\\p{javaISOControl}\\u200B-\\u200F\\u2028\\u2029\\u202A-\\u202E\\uFEFF]")

/** Erstatter tegn som ikke kan vises i en PDF med `?`. */
fun String.vaskTegnUtenGlyf(): String = replace(tegnUtenGlyf, "?")
