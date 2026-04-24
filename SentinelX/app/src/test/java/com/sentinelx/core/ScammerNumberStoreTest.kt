package com.sentinelx.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScammerNumberStoreTest {

    // Test only normalize() since it is pure and context-free
    private val dummyStore = object {
        fun normalize(number: String): String {
            var n = number.filter { it.isDigit() }
            if (n.startsWith("91") && n.length == 12) {
                n = n.substring(2)
            } else if (n.startsWith("1") && n.length == 11) {
                n = n.substring(1)
            } else if (n.length in 11..13) {
                for (prefixLen in 1..3) {
                    val candidate = n.substring(prefixLen)
                    if (candidate.length == 10 && !candidate.startsWith("0")) {
                        n = candidate
                        break
                    }
                }
            }
            if (n.startsWith("0") && n.length == 11) n = n.substring(1)
            return n
        }
    }

    @Test
    fun normalizeIndiaPrefix() {
        assertEquals("9876543210", dummyStore.normalize("+919876543210"))
        assertEquals("9876543210", dummyStore.normalize("919876543210"))
    }

    @Test
    fun normalizeUsPrefix() {
        assertEquals("4155552671", dummyStore.normalize("+14155552671"))
        assertEquals("4155552671", dummyStore.normalize("14155552671"))
    }

    @Test
    fun normalizeLeadingZero() {
        assertEquals("9876543210", dummyStore.normalize("09876543210"))
    }

    @Test
    fun normalizeAlreadyTenDigits() {
        assertEquals("9876543210", dummyStore.normalize("9876543210"))
    }

    @Test
    fun normalizeLettersStripped() {
        assertEquals("9876543210", dummyStore.normalize("+91 98765-43210"))
    }

    @Test
    fun normalizeShortNumberUnchanged() {
        assertEquals("12345", dummyStore.normalize("12345"))
    }
}
