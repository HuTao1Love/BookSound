package com.zyagodin.booksound.core.naming

import java.text.Collator
import java.util.Locale

/**
 * Orders strings the way people expect file names to be ordered: "Part 2" before "Part 10",
 * case-insensitive, locale-aware for letters.
 */
object NaturalOrder : Comparator<String> {
    private val collator: Collator = Collator.getInstance(Locale.ROOT).apply { strength = Collator.SECONDARY }

    override fun compare(a: String, b: String): Int {
        val ta = tokenize(a)
        val tb = tokenize(b)
        for (i in 0 until minOf(ta.size, tb.size)) {
            val x = ta[i]
            val y = tb[i]
            val xNum = x[0].isDigit()
            val yNum = y[0].isDigit()
            val c = when {
                xNum && yNum -> compareNumbers(x, y)
                else -> collator.compare(x, y)
            }
            if (c != 0) return c
        }
        return ta.size.compareTo(tb.size).takeIf { it != 0 } ?: a.compareTo(b)
    }

    private fun compareNumbers(x: String, y: String): Int {
        val a = x.trimStart('0')
        val b = y.trimStart('0')
        if (a.length != b.length) return a.length.compareTo(b.length)
        val c = a.compareTo(b)
        return if (c != 0) c else x.length.compareTo(y.length)
    }

    private fun tokenize(s: String): List<String> {
        val tokens = mutableListOf<String>()
        var i = 0
        while (i < s.length) {
            val start = i
            val digit = s[i].isDigit()
            while (i < s.length && s[i].isDigit() == digit) i++
            tokens += s.substring(start, i)
        }
        return tokens
    }
}
