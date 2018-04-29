package com.jsoniter

import com.jsoniter.spi.JsonException
import junit.framework.TestCase
import java.io.ByteArrayInputStream
import java.math.BigInteger

//import java.math.BigDecimal;
//import java.math.BigInteger;

class TestIntegerKt : TestCase() {

    companion object {
        init {
            //        JsonIterator.setMode(DecodingMode.DYNAMIC_MODE_AND_MATCH_FIELD_STRICTLY);
        }
    }

    private var isStreaming: Boolean = false

    fun test_char() {
        val c = JsonIterator.deserialize("50", Char::class.java)
        assertEquals(50, c.toInt())
    }

    fun test_positive_negative_int() {
        assertEquals(0, parseInt("0"))
        assertEquals(4321, parseInt("4321"))
        assertEquals(54321, parseInt("54321"))
        assertEquals(654321, parseInt("654321"))
        assertEquals(7654321, parseInt("7654321"))
        assertEquals(87654321, parseInt("87654321"))
        assertEquals(987654321, parseInt("987654321"))
        assertEquals(2147483647, parseInt("2147483647"))
        assertEquals(-4321, parseInt("-4321"))
        assertEquals(-2147483648, parseInt("-2147483648"))
    }

    fun test_positive_negative_long() {
        assertEquals(0L, parseLong("0"))
        assertEquals(4321L, parseLong("4321"))
        assertEquals(54321L, parseLong("54321"))
        assertEquals(654321L, parseLong("654321"))
        assertEquals(7654321L, parseLong("7654321"))
        assertEquals(87654321L, parseLong("87654321"))
        assertEquals(987654321L, parseLong("987654321"))
        assertEquals(9223372036854775807L, parseLong("9223372036854775807"))
        assertEquals(-4321L, parseLong("-4321"))
        assertEquals(-9223372036854775807L, parseLong("-9223372036854775807"))
    }

    fun test_max_min_int() {
        assertEquals(Integer.MAX_VALUE, parseInt(Integer.toString(Integer.MAX_VALUE)))
        assertEquals(Integer.MAX_VALUE - 1, parseInt(Integer.toString(Integer.MAX_VALUE - 1)))
        assertEquals(Integer.MIN_VALUE + 1, parseInt(Integer.toString(Integer.MIN_VALUE + 1)))
        assertEquals(Integer.MIN_VALUE, parseInt(Integer.toString(Integer.MIN_VALUE)))
    }

    fun test_max_min_long() {
        assertEquals(java.lang.Long.MAX_VALUE, parseLong(java.lang.Long.toString(java.lang.Long.MAX_VALUE)))
        assertEquals(java.lang.Long.MAX_VALUE - 1, parseLong(java.lang.Long.toString(java.lang.Long.MAX_VALUE - 1)))
        assertEquals(java.lang.Long.MIN_VALUE + 1, parseLong(java.lang.Long.toString(java.lang.Long.MIN_VALUE + 1)))
        assertEquals(java.lang.Long.MIN_VALUE, parseLong(java.lang.Long.toString(java.lang.Long.MIN_VALUE)))
    }

    fun test_large_number() {
        try {
            JsonIterator.deserialize("2147483648", Int::class.java)
            fail()
        } catch (e: JsonException) {
        }

        run {
            var i = 300000000
            while (i < 2000000000) {
                try {
                    JsonIterator.deserialize(i.toString() + "0", Int::class.java)
                    fail()
                } catch (e: JsonException) {
                }

                try {
                    JsonIterator.deserialize((-i).toString() + "0", Int::class.java)
                    fail()
                } catch (e: JsonException) {
                }

                i += 10000000
            }
        }
        try {
            JsonIterator.deserialize("9223372036854775808", Long::class.java)
            fail()
        } catch (e: JsonException) {
        }

        var i = 1000000000000000000L
        while (i < 9000000000000000000L) {
            try {
                JsonIterator.deserialize(i.toString() + "0", Long::class.java)
                fail()
            } catch (e: JsonException) {
            }

            try {
                JsonIterator.deserialize((-i).toString() + "0", Long::class.java)
                fail()
            } catch (e: JsonException) {
            }

            i += 100000000000000000L
        }
    }

    fun test_byte() {
        val `val` = JsonIterator.deserialize("120", Byte::class.java)
        assertEquals(java.lang.Byte.valueOf(120.toByte()), `val`)
        val vals = JsonIterator.deserialize("[120]", ByteArray::class.java)
        assertEquals(120.toByte(), vals[0])
    }

    /*@Category(StreamingCategory::class)
    @Throws(IOException::class)
    fun test_streaming() {
        isStreaming = true
        this.test_positive_negative_int()
        test_positive_negative_long()
        test_max_min_int()
        test_max_min_long()
        test_large_number()
    }*/

    fun test_leading_zero() {
        assertEquals(Integer.valueOf(0), JsonIterator.deserialize("0", Int::class.javaPrimitiveType))
        assertEquals(java.lang.Long.valueOf(0), JsonIterator.deserialize("0", Long::class.javaPrimitiveType))
        try {
            JsonIterator.deserialize("01", Int::class.javaPrimitiveType)
            fail()
        } catch (e: JsonException) {
        }

        try {
            JsonIterator.deserialize("02147483647", Int::class.javaPrimitiveType)
            fail()
        } catch (e: JsonException) {
        }

        try {
            JsonIterator.deserialize("01", Long::class.javaPrimitiveType)
            fail()
        } catch (e: JsonException) {
        }

        try {
            JsonIterator.deserialize("09223372036854775807", Long::class.javaPrimitiveType)
            fail()
        } catch (e: JsonException) {
        }

        /* FIXME if we should fail on parsing of leading zeroes for other numbers
        try {
            JsonIterator.deserialize("01", double.class);
            fail();
        } catch (JsonException e) {
        }
        try {
            JsonIterator.deserialize("01", float.class);
            fail();
        } catch (JsonException e) {
        }
        try {
            JsonIterator.deserialize("01", BigInteger.class);
            fail();
        } catch (JsonException e) {
        }
        try {
            JsonIterator.deserialize("01", BigDecimal.class);
            fail();
        } catch (JsonException e) {
        }
*/
    }

    fun test_max_int() {
        val ints = JsonIterator.deserialize("[2147483647,-2147483648]", IntArray::class.java)
        assertEquals(Integer.MAX_VALUE, ints[0])
        assertEquals(Integer.MIN_VALUE, ints[1])
    }

    private fun parseInt(input: String): Int {
        if (isStreaming) {
            val iter = JsonIterator.parse(ByteArrayInputStream(input.toByteArray()), 2)
            return iter.readInt()
        } else {
            val iter = JsonIterator.parse(input)
            val v = iter.readInt()
            assertEquals(input.length, iter.head) // iterator head should point on next non-parsed byte
            return v
        }
    }

    private fun parseLong(input: String): Long {
        if (isStreaming) {
            val iter = JsonIterator.parse(ByteArrayInputStream(input.toByteArray()), 2)
            return iter.readLong()
        } else {
            val iter = JsonIterator.parse(input)
            val v = iter.readLong()
            assertEquals(input.length, iter.head) // iterator head should point on next non-parsed byte
            return v
        }
    }

    fun testBigInteger() {
        val number = JsonIterator.deserialize("100", BigInteger::class.java)
        assertEquals(BigInteger("100"), number)
    }

    fun testChooseInteger() {
        val number = JsonIterator.deserialize("100", Any::class.java)
        assertEquals(100, number)
    }

    fun testChooseLong() {
        val number = JsonIterator.deserialize(java.lang.Long.valueOf(java.lang.Long.MAX_VALUE).toString(), Any::class.java)
        assertEquals(java.lang.Long.MAX_VALUE, number)
    }
}
