package com.follow.clash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticSanitizerTest {
    @Test
    fun removesSubscriptionPathAndQuery() {
        val result = DiagnosticSanitizer.redact(
            "download https://example.com/api/v1/subscribe?token=private-value",
        )

        assertEquals("download https://example.com/…", result)
        assertFalse(result.contains("private-value"))
    }

    @Test
    fun masksCredentialFields() {
        val result = DiagnosticSanitizer.redact("password=hello secret:world uuid=123")

        assertTrue(result.contains("password=<redacted>"))
        assertTrue(result.contains("secret:<redacted>"))
        assertTrue(result.contains("uuid=<redacted>"))
    }

    @Test
    fun keepsOnlyProfileHost() {
        assertEquals(
            "sub.example.com",
            DiagnosticSanitizer.sourceHost("https://sub.example.com/client/secret?token=x"),
        )
    }
}
