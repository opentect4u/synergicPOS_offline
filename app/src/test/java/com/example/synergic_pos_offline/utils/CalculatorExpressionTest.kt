package com.example.synergic_pos_offline.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CalculatorExpressionTest {

    private fun eval(expr: String) = CalculatorExpression.evaluate(expr)!!

    @Test fun `percent added to a total is a percent of that total`() =
        assertEquals(220.0, eval("200+10%"), 1e-9)

    @Test fun `percent taken off a total is a percent of that total`() =
        assertEquals(400.0, eval("500−20%"), 1e-9)

    @Test fun `percent times a number is that share of it`() =
        assertEquals(20.0, eval("200×10%"), 1e-9)

    @Test fun `dividing by a percent divides by the fraction`() =
        assertEquals(500.0, eval("50÷10%"), 1e-9)

    @Test fun `a percent on its own is the fraction`() =
        assertEquals(0.1, eval("10%"), 1e-9)

    @Test fun `multiplication still binds before addition`() =
        assertEquals(70.0, eval("50+200×10%"), 1e-9)

    @Test fun `a percent applies to everything totalled before it`() =
        assertEquals(330.0, eval("100+200+10%"), 1e-9)

    @Test fun `a chain can carry on after a percent`() =
        assertEquals(225.0, eval("200+10%+5"), 1e-9)

    @Test fun `GST on top of a discounted price`() =
        assertEquals(1062.0, eval("1000−10%+18%"), 1e-9)

    @Test fun `plain arithmetic is unchanged`() {
        assertEquals(14.0, eval("2+3×4"), 1e-9)
        assertEquals(2.5, eval("10÷4"), 1e-9)
        assertEquals(-3.0, eval("-5+2"), 1e-9)
        assertEquals(0.5, eval(".5"), 1e-9)
    }

    @Test fun `nonsense is refused rather than guessed at`() {
        assertNull(CalculatorExpression.evaluate("50%%"))
        assertNull(CalculatorExpression.evaluate("10%5"))
        assertNull(CalculatorExpression.evaluate("200+%"))
        assertNull(CalculatorExpression.evaluate("5÷0"))
        assertNull(CalculatorExpression.evaluate("5+"))
        assertNull(CalculatorExpression.evaluate(""))
    }
}
