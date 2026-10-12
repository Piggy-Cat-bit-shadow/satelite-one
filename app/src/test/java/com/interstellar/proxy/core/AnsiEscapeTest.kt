package com.interstellar.proxy.core

import org.junit.Test

/**
 * The logs page renders plain text, so a core log line must not carry escape sequences.
 *
 * The symptom is reported as bracketed litter - `[36mINFO[0m`, `[31mERROR[0m` - because the
 * escape byte itself is invisible in a text view while its payload is not. These cases pin
 * both halves: the sequences go, and ordinary bracketed text does not.
 */
class AnsiEscapeTest {

    private val esc = '\u001B'

    @Test
    fun `strips the colour sequences the core emits`() {
        val raw = "${esc}[36mINFO${esc}[0m router: updating rule-set AI"
        check(AnsiEscape.strip(raw) == "INFO router: updating rule-set AI") {
            "colour sequences survived: '${AnsiEscape.strip(raw)}'"
        }
    }

    @Test
    fun `strips every level prefix, not just one colour`() {
        val cases = mapOf(
            "${esc}[36mINFO${esc}[0m a" to "INFO a",
            "${esc}[37mDEBUG${esc}[0m b" to "DEBUG b",
            "${esc}[31mERROR${esc}[0m c" to "ERROR c",
            "${esc}[33mWARN${esc}[0m d" to "WARN d",
        )
        for ((raw, expected) in cases) {
            check(AnsiEscape.strip(raw) == expected) {
                "expected '$expected' from $raw, got '${AnsiEscape.strip(raw)}'"
            }
        }
    }

    @Test
    fun `strips a sequence attached to nothing`() {
        check(AnsiEscape.strip("${esc}[0m") == "") { "a bare reset sequence survived" }
        check(AnsiEscape.strip("a${esc}[1;32mb${esc}[mc") == "abc") {
            "stacked parameters survived: '${AnsiEscape.strip("a${esc}[1;32mb${esc}[mc")}'"
        }
    }

    @Test
    fun `strips non-colour sequences too`() {
        // Cursor hiding is the same litter with a different payload.
        check(AnsiEscape.strip("${esc}[?25lx") == "x") { "private-mode sequence survived" }
        // OSC window-title, terminated by BEL.
        check(AnsiEscape.strip("${esc}]0;title\u0007y") == "y") { "OSC survived" }
        // OSC terminated by ST.
        check(AnsiEscape.strip("${esc}]8;;http://x${esc}\\z") == "z") { "OSC ST survived" }
    }

    @Test
    fun `keeps bracketed text that is not a sequence`() {
        // No escape byte: this is ordinary content and must not be eaten.
        val text = "route [0m] set, port [36m unreal"
        check(AnsiEscape.strip(text) == text) { "over-stripped real text: '${AnsiEscape.strip(text)}'" }
    }

    @Test
    fun `leaves a clean line untouched`() {
        val text = "INFO service started"
        check(AnsiEscape.strip(text) == text) { "a clean line was modified" }
    }

    @Test
    fun `strips sequences from every line of a multi-line entry`() {
        val raw = "${esc}[36mINFO${esc}[0m first\n${esc}[31mERROR${esc}[0m second"
        check(AnsiEscape.strip(raw) == "INFO first\nERROR second") {
            "multi-line entry kept sequences: '${AnsiEscape.strip(raw)}'"
        }
    }
}
