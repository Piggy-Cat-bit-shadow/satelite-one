package com.interstellar.proxy.core

/**
 * Removes ANSI escape sequences from text the core produced.
 *
 * `log.timestamp`/level colouring in sing-box's own formatter emits SGR sequences, and a
 * libbox `LogEntry.message` carries them verbatim. This app renders plain text, so a
 * sequence reaches the logs page as litter:
 *
 *     [36mINFO[0m ...
 *     [31mERROR[0m ...
 *
 * The escape byte itself is invisible in a text view, which is why only the bracketed
 * remainder is reported as the symptom.
 *
 * Deliberately a display-layer concern: the core's log content is not touched, only the
 * presentation of it. Every sequence form sing-box can emit is removed, not just the
 * colour ones — a left-behind `[?25l`-style cursor or OSC title sequence is the same
 * litter with a different payload.
 */
internal object AnsiEscape {

    /**
     * ESC [ parameters intermediates final-byte   (SGR, cursor, erase, ...)
     * ESC ] ... BEL | ESC \                       (OSC: window title, hyperlink)
     * ESC <single char>                           (two-character escapes: ESC ( B, ...)
     */
    private val SEQUENCE = Regex(
        "\u001B\\[[0-9;?]*[ -/]*[@-~]" +
            "|\u001B\\][^\u0007\u001B]*(?:\u0007|\u001B\\\\)" +
            "|\u001B[@-Z\\\\-_]",
    )

    /**
     * Strip every escape sequence from [text].
     *
     * A line with no escape byte is returned unchanged and without allocating a match,
     * because this runs once per core log line on the UI's log path.
     */
    fun strip(text: String): String =
        if (text.indexOf('\u001B') < 0) text else SEQUENCE.replace(text, "")
}
