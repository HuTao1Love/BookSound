package com.zyagodin.booksound.core

import com.zyagodin.booksound.core.organize.NameField
import com.zyagodin.booksound.core.organize.NameTemplate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NameTemplateTest {

    private val name = "Рифудзин на Магонотэ - Реинкарнация безработного  - Том 16 - Молодость - Бог человеческий [HEDGEHOG INC]"

    @Test
    fun `full template reads every field`() {
        val fields = NameTemplate.parse("%author% - %series% - Том %number% - %title% [%narrator%]", name)
        assertEquals(
            mapOf(
                NameField.AUTHOR to "Рифудзин на Магонотэ",
                NameField.SERIES to "Реинкарнация безработного",
                NameField.NUMBER to "16",
                NameField.TITLE to "Молодость - Бог человеческий",
                NameField.NARRATOR to "HEDGEHOG INC",
            ),
            fields,
        )
    }

    @Test
    fun `aliases and russian placeholders`() {
        val fields = NameTemplate.parse("%автор% - %book% - %name% [%reader%]", name)!!
        assertEquals("Рифудзин на Магонотэ", fields[NameField.AUTHOR])
        assertEquals("Реинкарнация безработного", fields[NameField.SERIES])
        assertEquals("Том 16 - Молодость - Бог человеческий", fields[NameField.TITLE])
        assertEquals("HEDGEHOG INC", fields[NameField.NARRATOR])
    }

    @Test
    fun `trailing brackets may be left out of the template`() {
        val fields = NameTemplate.parse("%author% - %title%", "Frank Herbert – Dune [2007, MP3, 128 kbps] (Simon Vance)")!!
        assertEquals("Frank Herbert", fields[NameField.AUTHOR])
        assertEquals("Dune", fields[NameField.TITLE])
    }

    @Test
    fun `numbers year and skipped parts`() {
        val fields = NameTemplate.parse("%series% %number% - %title% (%year%) %*%", "Дозоры 01 - Ночной дозор (1998) MP3 128")!!
        assertEquals(mapOf(NameField.SERIES to "Дозоры", NameField.NUMBER to "1", NameField.TITLE to "Ночной дозор", NameField.YEAR to "1998"), fields)
    }

    @Test
    fun `names of another shape do not match`() {
        assertNull(NameTemplate.parse("%author% - %series% - Том %number% - %title%", "Just a title"))
        assertNull(NameTemplate.parse("%author% - %title%", "NoSeparatorHere"))
    }

    @Test
    fun `validation`() {
        assertEquals(NameTemplate.Problem.NoPlaceholders, NameTemplate.validate("just text"))
        assertEquals(NameTemplate.Problem.UnknownPlaceholder("foo"), NameTemplate.validate("%author% - %foo%"))
        assertEquals(NameTemplate.Problem.Duplicate(NameField.TITLE), NameTemplate.validate("%title% - %name%"))
        assertEquals(NameTemplate.Problem.AdjacentPlaceholders, NameTemplate.validate("%author%%title%"))
        assertNull(NameTemplate.validate("%series%%number%"))
        NameTemplate.DEFAULTS.forEach { assertNull(it, NameTemplate.validate(it)) }
    }
}
