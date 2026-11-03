package com.example.bukku

import java.time.YearMonth
import org.junit.Assert.*
import org.junit.Test

class CharmModelsTest {
    @Test fun friendPayloadNeverIncludesNameOrRelationship() {
        val form = FriendForm(name="친구이름",birth="2000-01-02",relationship="동료")
        assertTrue(form.validate().isEmpty())
        val json=form.json()
        assertFalse(json.has("name"));assertFalse(json.has("relationship"))
        assertTrue(json.isNull("birth_time"));assertTrue(json.isNull("gender"));assertTrue(json.isNull("is_leap_month"))
    }
    @Test fun lunarFriendRequiresAnExplicitLeapMonthChoice() {
        val form=FriendForm(name="친구",birth="2000-01-02",relationship="친구",lunar=true)
        assertTrue(form.validate().containsKey("leap"))
        assertTrue(form.copy(leap=false).validate().isEmpty())
        assertFalse(form.copy(leap=false).json().getBoolean("is_leap_month"))
    }
    @Test fun friendFormRejectsImpossibleDateAndMissingRelationship() {
        val errors=FriendForm(name="",birth="2000-02-30",relationship="").validate()
        assertTrue(errors.keys.containsAll(listOf("name","birth","relationship")))
    }
    @Test fun savedCharmRetainsTheSelectedRecordAndAllVisualChoices() {
        val charm=Charm(text="행운\n응원",pattern="물결",frame="점선",theme="sky",concern="그날의 기록",message="운세",advice="조언",score=78)
        assertEquals(charm,Charm.from(charm.json()))
    }
    @Test fun demonstrationDatesAreValidInFebruaryAndLeapYears() {
        for(month in listOf(YearMonth.of(2026,2),YearMonth.of(2028,2))) {
            val values=exampleCharms(month)
            assertTrue(values.all{it.demo&&it.date.startsWith(month.toString())})
            assertEquals(6,values.size)
        }
    }
}
