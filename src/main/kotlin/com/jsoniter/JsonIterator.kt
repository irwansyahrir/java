package com.jsoniter

import com.jsoniter.any.Any
import com.jsoniter.spi.*

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.lang.reflect.Type
import java.math.BigDecimal
import java.math.BigInteger
import java.util.ArrayList
import java.util.HashMap

class JsonIterator : Closeable {

    internal var `in`: InputStream? // TODO: backtics, https://kotlinlang.org/docs/reference/java-interop.html#escaping-for-java-identifiers-that-are-keywords-in-kotlin
    internal var buf: ByteArray
    internal var head: Int
    internal var tail: Int

    private constructor(`in`: InputStream?, buf: ByteArray, head: Int, tail: Int) {
        this.`in` = `in`
        this.buf = buf
        this.head = head
        this.tail = tail
        this.reusableSlice = Slice(null, 0, 0)
        this.reusableChars = CharArray(32)
    }

    var configCache: Config? = null
    internal var skipStartedAt = -1 // skip should keep bytes starting at this pos

    internal var tempObjects: Map<String, Any>? = null // used in reflection object decoder
    internal val reusableSlice: Slice
    internal var reusableChars: CharArray
    internal var existingObject: Any? = null // the object should be bind to next

    constructor() : this(null, ByteArray(0), 0, 0) {}

    fun reset(buf: ByteArray) {
        this.buf = buf
        this.head = 0
        this.tail = buf.size
    }

    fun reset(buf: ByteArray, head: Int, tail: Int) {
        this.buf = buf
        this.head = head
        this.tail = tail
    }

    fun reset(value: Slice) {
        this.buf = value.data()
        this.head = value.head()
        this.tail = value.tail()
    }

    fun reset(`in`: InputStream) {
        JsonIterator.enableStreamingSupport()
        this.`in` = `in`
        this.head = 0
        this.tail = 0
    }

    @Throws(IOException::class)
    override fun close() {
        if (`in` != null) {
            `in`!!.close()
        }
    }

    internal fun unreadByte() {
        if (head == 0) {
            throw reportError("unreadByte", "unread too many bytes")
        }
        head--
    }

    fun reportError(op: String, msg: String): JsonException {
        var peekStart = head - 10
        if (peekStart < 0) {
            peekStart = 0
        }
        var peekSize = head - peekStart
        if (head > tail) {
            peekSize = tail - peekStart
        }
        val peek = String(buf, peekStart, peekSize)
        throw JsonException(op + ": " + msg + ", head: " + head + ", peek: " + peek + ", buf: " + String(buf))
    }

    fun currentBuffer(): String {
        var peekStart = head - 10
        if (peekStart < 0) {
            peekStart = 0
        }
        val peek = String(buf, peekStart, head - peekStart)
        return "head: " + head + ", peek: " + peek + ", buf: " + String(buf)
    }

    @Throws(IOException::class)
    fun readNull(): Boolean {
        val c = IterImpl.nextToken(this)
        if (c != 'n'.toByte()) {
            unreadByte()
            return false
        }
        IterImpl.skipFixedBytes(this, 3) // null
        return true
    }

    @Throws(IOException::class)
    fun readBoolean(): Boolean {
        val c = IterImpl.nextToken(this)
        if ('t'.toByte() == c) {
            IterImpl.skipFixedBytes(this, 3) // true
            return true
        }
        if ('f'.toByte() == c) {
            IterImpl.skipFixedBytes(this, 4) // false
            return false
        }
        throw reportError("readBoolean", "expect t or f, found: $c")
    }

    @Throws(IOException::class)
    fun readShort(): Short {
        val v = readInt()
        return if (java.lang.Short.MIN_VALUE <= v && v <= java.lang.Short.MAX_VALUE) {
            v.toShort()
        } else {
            throw reportError("readShort", "short overflow: $v")
        }
    }

    @Throws(IOException::class)
    fun readInt(): Int {
        return IterImplNumber.readInt(this)
    }

    @Throws(IOException::class)
    fun readLong(): Long {
        return IterImplNumber.readLong(this)
    }

    @Throws(IOException::class)
    fun readArray(): Boolean {
        return IterImplArray.readArray(this)
    }

    @Throws(IOException::class)
    fun readNumberAsString(): String {
        val numberChars = IterImplForStreaming.readNumber(this)
        return String(numberChars.chars, 0, numberChars.charsLength)
    }

    interface ReadArrayCallback {
        @Throws(IOException::class)
        fun handle(iter: JsonIterator, attachment: Any): Boolean
    }

    @Throws(IOException::class)
    fun readArrayCB(callback: ReadArrayCallback, attachment: Any): Boolean {
        return IterImplArray.readArrayCB(this, callback, attachment)
    }

    @Throws(IOException::class)
    fun readString(): String? {
        return IterImplString.readString(this)
    }

    @Throws(IOException::class)
    fun readStringAsSlice(): Slice {
        return IterImpl.readSlice(this)
    }

    @Throws(IOException::class)
    fun readObject(): String? {
        return IterImplObject.readObject(this)
    }

    interface ReadObjectCallback {
        @Throws(IOException::class)
        fun handle(iter: JsonIterator, field: String, attachment: Any): Boolean
    }

    @Throws(IOException::class)
    fun readObjectCB(cb: ReadObjectCallback, attachment: Any) {
        IterImplObject.readObjectCB(this, cb, attachment)
    }

    @Throws(IOException::class)
    fun readFloat(): Float {
        return IterImplNumber.readFloat(this)
    }

    @Throws(IOException::class)
    fun readDouble(): Double {
        return IterImplNumber.readDouble(this)
    }

    @Throws(IOException::class)
    fun readBigDecimal(): BigDecimal? {
        // skip whitespace by read next
        val valueType = whatIsNext()
        if (valueType == ValueType.NULL) {
            skip()
            return null
        }
        if (valueType != ValueType.NUMBER) {
            throw reportError("readBigDecimal", "not number")
        }
        val numberChars = IterImplForStreaming.readNumber(this)
        return BigDecimal(numberChars.chars, 0, numberChars.charsLength)
    }

    @Throws(IOException::class)
    fun readBigInteger(): BigInteger? {
        // skip whitespace by read next
        val valueType = whatIsNext()
        if (valueType == ValueType.NULL) {
            skip()
            return null
        }
        if (valueType != ValueType.NUMBER) {
            throw reportError("readBigDecimal", "not number")
        }
        val numberChars = IterImplForStreaming.readNumber(this)
        return BigInteger(String(numberChars.chars, 0, numberChars.charsLength))
    }

    @Throws(IOException::class)
    fun readAny(): Any {
        try {
            return IterImpl.readAny(this)
        } catch (e: ArrayIndexOutOfBoundsException) {
            throw reportError("read", "premature end")
        }

    }

    @Throws(IOException::class)
    fun read(): Any? {
        try {
            val valueType = whatIsNext()
            when (valueType) {
                ValueType.STRING -> return readString()
                ValueType.NUMBER -> {
                    val numberChars = IterImplForStreaming.readNumber(this)
                    val number = java.lang.Double.valueOf(String(numberChars.chars, 0, numberChars.charsLength))
                    if (numberChars.dotFound) {
                        return number
                    }
                    if (number == Math.floor(number) && !java.lang.Double.isInfinite(number)) {
                        val longNumber = number.toLong()
                        return if (longNumber <= Integer.MAX_VALUE && longNumber >= Integer.MIN_VALUE) {
                            longNumber.toInt()
                        } else longNumber
                    }
                    return number
                }
                ValueType.NULL -> {
                    IterImpl.skipFixedBytes(this, 4)
                    return null
                }
                ValueType.BOOLEAN -> return readBoolean()
                ValueType.ARRAY -> {
                    val list = ArrayList(4)
                    readArrayCB(fillArray, list)
                    return list
                }
                ValueType.OBJECT -> {
                    val map = HashMap(4)
                    readObjectCB(fillObject, map)
                    return map
                }
                else -> throw reportError("read", "unexpected value type: $valueType")
            }
        } catch (e: ArrayIndexOutOfBoundsException) {
            throw reportError("read", "premature end")
        }

    }

    /**
     * try to bind to existing object, returned object might not the same instance
     *
     * @param existingObject the object instance to reuse
     * @param <T>            object type
     * @return data binding result, might not be the same object
     * @throws IOException if I/O went wrong
    </T> */
    @Throws(IOException::class)
    fun <T> read(existingObject: T): T {
        try {
            this.existingObject = existingObject
            val clazz = existingObject.javaClass
            val cacheKey = currentConfig().getDecoderCacheKey(clazz)
            return Codegen.getDecoder(cacheKey, clazz).decode(this) as T
        } catch (e: ArrayIndexOutOfBoundsException) {
            throw reportError("read", "premature end")
        }

    }

    private fun currentConfig(): Config {
        if (configCache == null) {
            configCache = JsoniterSpi.getCurrentConfig()
        }
        return configCache
    }

    /**
     * try to bind to existing object, returned object might not the same instance
     *
     * @param typeLiteral    the type object
     * @param existingObject the object instance to reuse
     * @param <T>            object type
     * @return data binding result, might not be the same object
     * @throws IOException if I/O went wrong
    </T> */
    @Throws(IOException::class)
    fun <T> read(typeLiteral: TypeLiteral<T>, existingObject: T): T {
        try {
            this.existingObject = existingObject
            val cacheKey = currentConfig().getDecoderCacheKey(typeLiteral.type)
            return Codegen.getDecoder(cacheKey, typeLiteral.type).decode(this) as T
        } catch (e: ArrayIndexOutOfBoundsException) {
            throw reportError("read", "premature end")
        }

    }

    @Throws(IOException::class)
    fun <T> read(clazz: Class<T>): T {
        return read(clazz as Type) as T
    }

    @Throws(IOException::class)
    fun <T> read(typeLiteral: TypeLiteral<T>): T {
        return read(typeLiteral.type) as T
    }

    @Throws(IOException::class)
    fun read(type: Type): Any {
        try {
            val cacheKey = currentConfig().getDecoderCacheKey(type)
            return Codegen.getDecoder(cacheKey, type).decode(this)
        } catch (e: ArrayIndexOutOfBoundsException) {
            throw reportError("read", "premature end")
        }

    }

    @Throws(IOException::class)
    fun whatIsNext(): ValueType {
        val valueType = valueTypes[IterImpl.nextToken(this)]
        unreadByte()
        return valueType
    }

    @Throws(IOException::class)
    fun skip() {
        IterImplSkip.skip(this)
    }

    companion object {
        private var isStreamingEnabled = false
        internal val valueTypes = arrayOfNulls<ValueType>(256)

        init {
            for (i in valueTypes.indices) {
                valueTypes[i] = ValueType.INVALID
            }
            valueTypes['"'.toInt()] = ValueType.STRING
            valueTypes['-'.toInt()] = ValueType.NUMBER
            valueTypes['0'.toInt()] = ValueType.NUMBER
            valueTypes['1'.toInt()] = ValueType.NUMBER
            valueTypes['2'.toInt()] = ValueType.NUMBER
            valueTypes['3'.toInt()] = ValueType.NUMBER
            valueTypes['4'.toInt()] = ValueType.NUMBER
            valueTypes['5'.toInt()] = ValueType.NUMBER
            valueTypes['6'.toInt()] = ValueType.NUMBER
            valueTypes['7'.toInt()] = ValueType.NUMBER
            valueTypes['8'.toInt()] = ValueType.NUMBER
            valueTypes['9'.toInt()] = ValueType.NUMBER
            valueTypes['t'.toInt()] = ValueType.BOOLEAN
            valueTypes['f'.toInt()] = ValueType.BOOLEAN
            valueTypes['n'.toInt()] = ValueType.NULL
            valueTypes['['.toInt()] = ValueType.ARRAY
            valueTypes['{'.toInt()] = ValueType.OBJECT
        }

        fun parse(`in`: InputStream, bufSize: Int): JsonIterator {
            enableStreamingSupport()
            return JsonIterator(`in`, ByteArray(bufSize), 0, 0)
        }

        fun parse(buf: ByteArray): JsonIterator {
            return JsonIterator(null, buf, 0, buf.size)
        }

        fun parse(buf: ByteArray, head: Int, tail: Int): JsonIterator {
            return JsonIterator(null, buf, head, tail)
        }

        fun parse(str: String): JsonIterator {
            return parse(str.toByteArray())
        }

        fun parse(slice: Slice): JsonIterator {
            return JsonIterator(null, slice.data(), slice.head(), slice.tail())
        }

        private val fillArray = object : ReadArrayCallback {
            @Throws(IOException::class)
            override fun handle(iter: JsonIterator, attachment: Any): Boolean {
                val list = attachment as List<*>
                list.add(iter.read())
                return true
            }
        }

        private val fillObject = object : ReadObjectCallback {
            @Throws(IOException::class)
            override fun handle(iter: JsonIterator, field: String, attachment: Any): Boolean {
                val map = attachment as Map<*, *>
                map.put(field, iter.read())
                return true
            }
        }

        fun <T> deserialize(config: Config, input: String, clazz: Class<T>): T {
            JsoniterSpi.setCurrentConfig(config)
            try {
                return deserialize(input.toByteArray(), clazz)
            } finally {
                JsoniterSpi.clearCurrentConfig()
            }
        }

        fun <T> deserialize(input: String, clazz: Class<T>): T {
            return deserialize(input.toByteArray(), clazz)
        }

        fun <T> deserialize(config: Config, input: String, typeLiteral: TypeLiteral<T>): T {
            JsoniterSpi.setCurrentConfig(config)
            try {
                return deserialize(input.toByteArray(), typeLiteral)
            } finally {
                JsoniterSpi.clearCurrentConfig()
            }
        }

        fun <T> deserialize(input: String, typeLiteral: TypeLiteral<T>): T {
            return deserialize(input.toByteArray(), typeLiteral)
        }

        fun <T> deserialize(config: Config, input: ByteArray, clazz: Class<T>): T {
            JsoniterSpi.setCurrentConfig(config)
            try {
                return deserialize(input, clazz)
            } finally {
                JsoniterSpi.clearCurrentConfig()
            }
        }

        fun <T> deserialize(input: ByteArray, clazz: Class<T>): T {
            val lastNotSpacePos = findLastNotSpacePos(input)
            val iter = JsonIteratorPool.borrowJsonIterator()
            iter.reset(input, 0, lastNotSpacePos)
            try {
                val `val` = iter.read(clazz)
                if (iter.head != lastNotSpacePos) {
                    throw iter.reportError("deserialize", "trailing garbage found")
                }
                return `val`
            } catch (e: ArrayIndexOutOfBoundsException) {
                throw iter.reportError("deserialize", "premature end")
            } catch (e: IOException) {
                throw JsonException(e)
            } finally {
                JsonIteratorPool.returnJsonIterator(iter)
            }
        }

        fun <T> deserialize(config: Config, input: ByteArray, typeLiteral: TypeLiteral<T>): T {
            JsoniterSpi.setCurrentConfig(config)
            try {
                return deserialize(input, typeLiteral)
            } finally {
                JsoniterSpi.clearCurrentConfig()
            }
        }

        fun <T> deserialize(input: ByteArray, typeLiteral: TypeLiteral<T>): T {
            val lastNotSpacePos = findLastNotSpacePos(input)
            val iter = JsonIteratorPool.borrowJsonIterator()
            iter.reset(input, 0, lastNotSpacePos)
            try {
                val `val` = iter.read(typeLiteral)
                if (iter.head != lastNotSpacePos) {
                    throw iter.reportError("deserialize", "trailing garbage found")
                }
                return `val`
            } catch (e: ArrayIndexOutOfBoundsException) {
                throw iter.reportError("deserialize", "premature end")
            } catch (e: IOException) {
                throw JsonException(e)
            } finally {
                JsonIteratorPool.returnJsonIterator(iter)
            }
        }

        fun deserialize(config: Config, input: String): Any {
            JsoniterSpi.setCurrentConfig(config)
            try {
                return deserialize(input.toByteArray())
            } finally {
                JsoniterSpi.clearCurrentConfig()
            }
        }

        fun deserialize(input: String): Any {
            return deserialize(input.toByteArray())
        }

        fun deserialize(config: Config, input: ByteArray): Any {
            JsoniterSpi.setCurrentConfig(config)
            try {
                return deserialize(input)
            } finally {
                JsoniterSpi.clearCurrentConfig()
            }
        }

        fun deserialize(input: ByteArray): Any {
            val lastNotSpacePos = findLastNotSpacePos(input)
            val iter = JsonIteratorPool.borrowJsonIterator()
            iter.reset(input, 0, lastNotSpacePos)
            try {
                val `val` = iter.readAny()
                if (iter.head != lastNotSpacePos) {
                    throw iter.reportError("deserialize", "trailing garbage found")
                }
                return `val`
            } catch (e: ArrayIndexOutOfBoundsException) {
                throw iter.reportError("deserialize", "premature end")
            } catch (e: IOException) {
                throw JsonException(e)
            } finally {
                JsonIteratorPool.returnJsonIterator(iter)
            }
        }

        private fun findLastNotSpacePos(input: ByteArray): Int {
            for (i in input.indices.reversed()) {
                val c = input[i]
                if (c != ' '.toByte() && c != '\t'.toByte() && c != '\n'.toByte() && c != '\r'.toByte()) {
                    return i + 1
                }
            }
            return 0
        }

        fun setMode(mode: DecodingMode) {
            val newConfig = JsoniterSpi.getDefaultConfig().copyBuilder().decodingMode(mode).build()
            JsoniterSpi.setDefaultConfig(newConfig)
            JsoniterSpi.setCurrentConfig(newConfig)
        }

        fun enableStreamingSupport() {
            if (isStreamingEnabled) {
                return
            }
            isStreamingEnabled = true
            try {
                DynamicCodegen.enableStreamingSupport()
            } catch (e: JsonException) {
                throw e
            } catch (e: Exception) {
                throw JsonException(e)
            }

        }
    }
}
