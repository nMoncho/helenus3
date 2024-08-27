/*
 * Copyright (c) 2021 the original author or authors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
 * IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package net.nmoncho.helenus.internal.codec

import java.nio.ByteBuffer

import com.datastax.oss.driver.api.core.ProtocolVersion
import com.datastax.oss.driver.api.core.`type`.DataType
import com.datastax.oss.driver.api.core.`type`.codec.TypeCodec
import com.datastax.oss.driver.api.core.`type`.reflect.GenericType
import net.nmoncho.helenus.api.`type`.codec.Codec

/** A [[TypeCodec]] that maps instances of `Inner`, a driver supported Java type, to
  * instances of a target `Outer` Java type.
  *
  * <p>This codec can be used to provide support for Java types that are not natively handled by the
  * driver, as long as there is a conversion path to and from another supported Java type.
  */
abstract class MappingCodec[Inner, Outer](inner: TypeCodec[Inner], outerJavaType: GenericType[Outer])
    extends Codec[Outer]:

    /** Converts from an instance of the inner Java type to an instance of the outer Java type. Used
      * when deserializing or parsing.
      *
      * @param value The value to convert; may be null.
      * @return The converted value; may be null.
      */
    protected def innerToOuter(value: Inner): Outer

    /** Converts from an instance of the outer Java type to an instance of the inner Java type. Used
      * when serializing or formatting.
      *
      * @param value The value to convert; may be null.
      * @return The converted value; may be null.
      */
    protected def outerToInner(value: Outer): Inner

    override def decode(bytes: ByteBuffer, protocolVersion: ProtocolVersion): Outer =
        innerToOuter(inner.decode(bytes, protocolVersion))

    override def encode(value: Outer, protocolVersion: ProtocolVersion): ByteBuffer =
        inner.encode(outerToInner(value), protocolVersion)

    override def format(value: Outer): String =
        inner.format(outerToInner(value))

    override def parse(value: String): Outer =
        innerToOuter(inner.parse(value))

    override def getJavaType(): GenericType[Outer] =
        outerJavaType

    override def getCqlType(): DataType =
        inner.getCqlType()

end MappingCodec
