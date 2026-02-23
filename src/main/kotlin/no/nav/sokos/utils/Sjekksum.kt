package no.nav.sokos.utils

fun mod11(sifre: List<Int>, vekter: List<Int>): Int =
    sifre
        .zip(vekter)
        .sumOf { (d, v) -> d * v }
        .mod(11)
        .let {
            if (it == 0) {
                0
            } else {
                11 - it
            }
        }
