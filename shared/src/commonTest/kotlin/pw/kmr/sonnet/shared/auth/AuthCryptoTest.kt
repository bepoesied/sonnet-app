package pw.kmr.sonnet.shared.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AuthCryptoTest {
    @Test
    fun `pkce s256 challenge matches rfc 7636 appendix b vector`() {
        val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        val expectedChallenge = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"

        assertEquals(expectedChallenge, computePkceCodeChallenge(verifier))
    }

    @Test
    fun `default challenge generator emits valid rfc 7636 verifier challenge and state`() {
        val verifierRegex = Regex("^[A-Za-z0-9\\-._~]{43,128}$")
        val challengeRegex = Regex("^[A-Za-z0-9_-]{43}$")
        val stateRegex = Regex("^[A-Za-z0-9\\-._~]{16,512}$")

        val first = DefaultAuthChallengeGenerator.generate()
        val second = DefaultAuthChallengeGenerator.generate()

        assertTrue(verifierRegex.matches(first.codeVerifier))
        assertTrue(challengeRegex.matches(first.codeChallenge))
        assertTrue(stateRegex.matches(first.state))
        assertEquals(computePkceCodeChallenge(first.codeVerifier), first.codeChallenge)
        assertNotEquals(first.codeVerifier, second.codeVerifier)
        assertNotEquals(first.state, second.state)
    }

    @Test
    fun `constant time equals handles equal different and mismatched length strings`() {
        assertTrue(constantTimeEquals("abc-123", "abc-123"))
        assertFalse(constantTimeEquals("abc-123", "abc-124"))
        assertFalse(constantTimeEquals("abc-123", "abc-12"))
        assertFalse(constantTimeEquals("", "a"))
        assertTrue(constantTimeEquals("", ""))
    }
}
