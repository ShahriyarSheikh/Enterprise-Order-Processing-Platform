package com.food.ordering.system.domain.valueobject;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class MoneyTest {

    @Test
    void amountsWithDifferentScalesAreEqual() {
        Money oneDecimalPlace = new Money(new BigDecimal("50.0"));
        Money twoDecimalPlaces = new Money(new BigDecimal("50.00"));

        assertEquals(oneDecimalPlace, twoDecimalPlaces);
    }

    @Test
    void equalAmountsHaveEqualHashCodes() {
        Money oneDecimalPlace = new Money(new BigDecimal("50.0"));
        Money twoDecimalPlaces = new Money(new BigDecimal("50.00"));

        assertEquals(oneDecimalPlace.hashCode(), twoDecimalPlaces.hashCode());
    }

    @Test
    void differentAmountsAreNotEqual() {
        assertNotEquals(new Money(new BigDecimal("50.00")), new Money(new BigDecimal("50.01")));
    }
}
