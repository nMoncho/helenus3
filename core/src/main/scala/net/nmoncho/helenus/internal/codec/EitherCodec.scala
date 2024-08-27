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
import com.datastax.oss.driver.internal.core.`type`.DefaultTupleType
import com.datastax.oss.driver.internal.core.`type`.codec.ParseUtils
import com.datastax.oss.driver.shaded.guava.common.reflect.ScalaTypeToken
import com.datastax.oss.driver.shaded.guava.common.reflect.TypeParameter
import net.nmoncho.helenus.api.`type`.codec.Codec

/** [[TypeCodec]] implementation for [[Either]]. Translates to a two element tuple in Cassandra.
  * Another design possibility would be to use a UDT, but that would require users to configure a custom type.
  *
  * @param left left codec
  * @param right right codec
  */
class EitherCodec[A, B](left: TypeCodec[A], right: TypeCodec[B]) extends Codec[Either[A, B]]:
    import EitherCodec.*

    override def encode(value: Either[A, B], protocolVersion: ProtocolVersion): ByteBuffer =
        if value == null then null
        else
            val buffer = value.fold(
              left.encode(_, protocolVersion),
              right.encode(_, protocolVersion)
            )
            val size   = buffer.remaining()
            val result = ByteBuffer.allocate(8 + size)

            // Encoding tuples means putting each element's size first
            if value.isLeft then
                result.putInt(size).put(buffer.duplicate()).putInt(-1)
            else
                result.putInt(-1).putInt(size).put(buffer.duplicate())

            result.flip()

    override def decode(buffer: ByteBuffer, protocolVersion: ProtocolVersion): Either[A, B] =
        if buffer == null then null.asInstanceOf[Either[A, B]]
        else
            val input = buffer.duplicate()

            // If first element has size, then it's a `Left`, otherwise is a `Right`
            val elementSize = input.getInt
            if elementSize >= 0 then
                val element = input.slice()
                element.limit(elementSize)

                Left(left.decode(element, protocolVersion))
            else
                val elementSize = input.getInt
                val element     = input.slice()
                element.limit(elementSize)

                Right(right.decode(element, protocolVersion))
            end if

    override val getJavaType: GenericType[Either[A, B]] =
        GenericType
            .of(new ScalaTypeToken[Either[A, B]] {}
                .where(new TypeParameter[A] {}, left.getJavaType().getType())
                .where(new TypeParameter[B] {}, right.getJavaType().getType()).getType())
            .asInstanceOf[GenericType[Either[A, B]]]

    override val getCqlType: DataType = new DefaultTupleType(
      java.util.List.of(left.getCqlType, right.getCqlType)
    )

    override def format(value: Either[A, B]): String =
        if value == null then NULL
        else
            value.fold(
              l => s"${openingChar}${left.format(l)}${separator}${NULL}${closingChar}",
              r => s"${openingChar}${NULL}${separator}${right.format(r)}${closingChar}"
            )

    override def parse(value: String): Either[A, B] =
        if value == null || value.isEmpty || value.equalsIgnoreCase(NULL) then
            null.asInstanceOf[Either[A, B]]
        else
            var idx = skipSpacesAndExpect(value, 0, openingChar)

            val leftEndIdx    = ParseUtils.skipCQLValue(value, idx)
            val leftSubstring = value.substring(idx, leftEndIdx)
            val leftValue =
                if leftSubstring.equalsIgnoreCase(NULL) then
                    null.asInstanceOf[A] // need to do this due to `AnyVal` types not returning null
                else left.parse(leftSubstring)

            idx = skipSpacesAndExpect(value, leftEndIdx, separator)

            val rightEndIdx = ParseUtils.skipCQLValue(value, idx)
            val rightValue  = right.parse(value.substring(idx, rightEndIdx))

            idx = skipSpacesAndExpect(value, rightEndIdx, closingChar)

            if leftValue != null then Left(leftValue) else Right(rightValue)

    override def accepts(value: Any): Boolean =
        value match
            case Left(l) => left.accepts(l)
            case Right(r) => right.accepts(r)
            case _ => false

end EitherCodec

object EitherCodec:
    private val separator   = ','
    private val openingChar = '('
    private val closingChar = ')'

    def apply[A, B](left: TypeCodec[A], right: TypeCodec[B]): TypeCodec[Either[A, B]] =
        new EitherCodec(left, right)

end EitherCodec
