package com.journeycontinuity.app.auth

import io.github.jan.supabase.auth.exception.AuthErrorCode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthPrivacyResponseTest {
    @Test fun onlyVerifiedAbsentLoginResponseIsPrivacyEquivalent() {
        assertTrue(isAbsentLoginAccountResponse(422, AuthErrorCode.OtpDisabled))
        assertFalse(isAbsentLoginAccountResponse(429, AuthErrorCode.OtpDisabled))
        assertFalse(isAbsentLoginAccountResponse(500, AuthErrorCode.OtpDisabled))
        assertFalse(isAbsentLoginAccountResponse(422, AuthErrorCode.EmailProviderDisabled))
        assertFalse(isAbsentLoginAccountResponse(422, null))
    }

    @Test fun onlyVerifiedDuplicateCreateEmailResponseIsPrivacyEquivalent() {
        assertTrue(isDuplicateCreateAccountEmailResponse(422, AuthErrorCode.EmailExists))
        assertFalse(isDuplicateCreateAccountEmailResponse(429, AuthErrorCode.EmailExists))
        assertFalse(isDuplicateCreateAccountEmailResponse(500, AuthErrorCode.EmailExists))
        assertFalse(isDuplicateCreateAccountEmailResponse(422, AuthErrorCode.UserAlreadyExists))
        assertFalse(isDuplicateCreateAccountEmailResponse(422, null))
    }
}
