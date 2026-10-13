package com.example.bukku

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class SignupFormTest {
    private val valid = SignupForm("hello@example.com", "password1", "password1", "한길", "2000-01-02", gender = "MALE")

    @Test fun optionalBirthTimeAndSpecialCharactersAreNotRequired() {
        assertTrue(valid.validate().isEmpty())
    }
    @Test fun passwordRequiresAsciiLettersAndNumbersAtEightCharacters() {
        assertFalse(valid.copy(password = "abcdefg1", confirm = "abcdefg1").validate().containsKey("password"))
        for (password in listOf("abcdef1", "abcdefgh", "12345678", "한글한글한글12")) {
            assertTrue(valid.copy(password = password, confirm = password).validate().containsKey("password"))
        }
    }
    @Test fun nameLengthIsMeasuredAfterTrimming() {
        assertTrue(valid.copy(name = " 가 ").validate().containsKey("name"))
        assertFalse(valid.copy(name = " 가나다라 ").validate().containsKey("name"))
        assertTrue(valid.copy(name = "가나다라마").validate().containsKey("name"))
    }
    @Test fun impossibleDateAndYearOutsideAllowedRangeAreRejected() {
        for (date in listOf("1899-12-31", "2000-02-30", "${LocalDate.now(seoul).year + 1}-01-01")) {
            assertTrue(valid.copy(birth = date).validate().containsKey("birth"))
        }
        assertFalse(valid.copy(birth = "1900-01-01").validate().containsKey("birth"))
    }
    @Test fun lunarCalendarRequiresExplicitLeapChoice() {
        assertTrue(valid.copy(lunar = true).validate().containsKey("leap"))
        assertTrue(valid.copy(lunar = true, leap = false).validate().isEmpty())
        assertTrue(valid.copy(lunar = true, leap = true).validate().isEmpty())
    }
    @Test fun confirmationAndGenderCannotBeOmitted() {
        val errors = valid.copy(confirm = "", gender = "").validate()
        assertTrue(errors.containsKey("confirm")); assertTrue(errors.containsKey("gender"))
    }
    @Test fun emailCannotContainWhitespaceOrOmitDomainSuffix() {
        for (email in listOf("hello", "a@b", "a b@example.com")) assertTrue(valid.copy(email = email).validate().containsKey("email"))
    }
}
