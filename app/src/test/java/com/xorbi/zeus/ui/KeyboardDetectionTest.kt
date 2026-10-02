package com.xorbi.zeus.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `Settings.Secure.DEFAULT_INPUT_METHOD` holds a flattened component name, and
 * this is the parse that decides whether the setup screen tells the user they are
 * done. Getting it wrong either hides the "you are enabled" confirmation or, worse,
 * claims success before the keyboard is actually selected.
 */
class KeyboardDetectionTest {

    @Test
    fun `reads the package from a fully qualified component`() {
        assertEquals(
            "com.xorbi.zeus",
            KeyboardDetection.packageOf("com.xorbi.zeus/com.xorbi.zeus.ui.ZeusInputMethodService"),
        )
    }

    @Test
    fun `tolerates an appended input-method id`() {
        assertEquals(
            "com.xorbi.zeus",
            KeyboardDetection.packageOf("com.xorbi.zeus/com.xorbi.zeus.ui.ZeusInputMethodService:latin"),
        )
    }

    @Test
    fun `handles a different keyboard`() {
        assertEquals(
            "com.google.android.inputmethod.latin",
            KeyboardDetection.packageOf(
                "com.google.android.inputmethod.latin/com.google.android.inputmethod.latin.LatinIME",
            ),
        )
    }

    @Test
    fun `returns null when unset`() {
        assertNull(KeyboardDetection.packageOf(null))
        assertNull(KeyboardDetection.packageOf(""))
        assertNull(KeyboardDetection.packageOf("   "))
    }

    @Test
    fun `returns null rather than trusting a malformed value`() {
        // No separator at all: not a component name, so refuse to guess.
        assertNull(KeyboardDetection.packageOf("com.xorbi.zeus"))
        assertNull(KeyboardDetection.packageOf("/no.package"))
        assertNull(KeyboardDetection.packageOf(":id-only"))
    }

    @Test
    fun `empty package is not matched`() {
        assertNull(KeyboardDetection.packageOf("/com.xorbi.zeus.ui.ZeusInputMethodService"))
    }
}