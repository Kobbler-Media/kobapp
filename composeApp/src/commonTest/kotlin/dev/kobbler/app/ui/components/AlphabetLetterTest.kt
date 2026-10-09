package dev.kobbler.app.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Parity with the server's `compute_first_letter` (kobbler api/src/search/mod.rs) — the
 * cases below are its own test table. A drift here puts the alphabet bar's highlight on
 * a different letter than the one the list is scrolled to.
 */
class AlphabetLetterTest {

    @Test
    fun stripsArticles() {
        val cases = listOf(
            "The Hobbit" to "H",
            "A Game" to "G",
            "An Example" to "E",
            "the lower" to "L",
            "a lower" to "L",
            "an lower" to "L",
        )
        cases.forEach { (input, expected) ->
            assertEquals(expected, alphabetLetterOf(input), "input: $input")
        }
    }

    @Test
    fun nonAsciiFallsToHash() {
        val cases = listOf(
            "Злочестие" to "#",
            "the Злочестие" to "#",
            "日本語タイトル" to "#",
        )
        cases.forEach { (input, expected) ->
            assertEquals(expected, alphabetLetterOf(input), "input: $input")
        }
    }

    @Test
    fun edgeCases() {
        val cases = listOf(
            "" to "#",
            "   " to "#",
            "123 Numbers" to "#",
            // No space after, so nothing is stripped and the article is the title.
            "A" to "A",
            "An" to "A",
            "The" to "T",
        )
        cases.forEach { (input, expected) ->
            assertEquals(expected, alphabetLetterOf(input), "input: $input")
        }
    }
}
