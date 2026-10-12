package com.instasave.app.util

import java.math.BigInteger

object Shortcode {

    private val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    /** Converts an Instagram shortcode to the numeric media id (pk). */
    fun toPk(shortcode: String): Long? {
        if (shortcode.isEmpty()) return null
        val base = BigInteger.valueOf(64L)
        var res = BigInteger.ZERO
        for (c in shortcode) {
            val v = ALPHABET.indexOf(c)
            if (v < 0) return null
            res = res.multiply(base).add(BigInteger.valueOf(v.toLong()))
        }
        // longValueExact() needs API 31, so the overflow check is done manually.
        return if (res.bitLength() > 63) null else res.toLong()
    }

    /** Converts a numeric media id (pk) back to a shortcode. */
    fun fromPk(pk: Long): String {
        var n = pk
        if (n <= 0L) return ALPHABET[0].toString()
        val sb = StringBuilder()
        while (n > 0) {
            sb.append(ALPHABET[(n % 64).toInt()])
            n /= 64
        }
        return sb.reverse().toString()
    }
}