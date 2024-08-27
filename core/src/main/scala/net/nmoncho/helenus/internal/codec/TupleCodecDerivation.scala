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

import scala.collection.mutable
import scala.reflect.ClassTag

import com.datastax.oss.driver.api.core.ProtocolVersion
import com.datastax.oss.driver.api.core.`type`.DataType
import com.datastax.oss.driver.api.core.`type`.codec.TypeCodec
import com.datastax.oss.driver.api.core.`type`.reflect.GenericType
import com.datastax.oss.driver.internal.core.`type`.DefaultTupleType
import com.datastax.oss.driver.internal.core.`type`.codec.ParseUtils
import net.nmoncho.helenus.api.`type`.codec.Codec

trait TupleCodecDerivation:

    trait TupleCodec[T <: Tuple] extends Codec[T]:
        private[helenus] def codecs: Seq[TypeCodec[?]]

    def tupleOf[T <: Tuple](using c: TupleCodec[T]): TupleCodec[T] = c

    // TODO enable `inline` in TupleComponentCodec methods

    trait TupleComponentCodec[T]:
        protected val separator = ','

        /** Encodes value `T` as a [[ByteBuffer]], and appends it to a list
          *
          * @param value value to be encoded
          * @param protocolVersion which DSE version to use
          * @return accumulated buffers, one per tuple component, with the total buffer size
          */
        def encode(value: T, protocolVersion: ProtocolVersion): (List[ByteBuffer], Int)

        /** Decodes a value `T` from a [[ByteBuffer]]
          */
        def decode(buffer: ByteBuffer, protocolVersion: ProtocolVersion): T

        /** List of [[TupleComponentCodec]], one per tuple component.
          * Used internally for tasks that don't require type safety
          */
        private[codec] def codecs: Seq[TypeCodec[?]]

        /** Formats a tuple component into a [[mutable.StringBuilder]]
          *
          * @param value component to format
          * @param sb format target
          * @return modified target (since it's mutable this isn't required)
          */
        private[codec] def format(value: T, sb: mutable.StringBuilder): mutable.StringBuilder

        /** Parses a tuple component, returning also the next index where to continue with the parse.
          */
        private[codec] def parse(value: String, idx: Int): (T, Int)

    end TupleComponentCodec

    given lastTupleElement[H](using codec: Codec[H]): TupleComponentCodec[H *: EmptyTuple] =
        new TupleComponentCodec[H *: EmptyTuple]:

            override val codecs: Seq[TypeCodec[?]] = List(codec)

            override def encode(
                value: H *: EmptyTuple,
                protocolVersion: ProtocolVersion
            ): (List[ByteBuffer], Int) =
                val encoded = codec.encode(value.head, protocolVersion)
                val size    = if encoded == null then 4 else 4 + encoded.remaining()

                List(encoded) -> size
            end encode

            override def decode(buffer: ByteBuffer, protocolVersion: ProtocolVersion): H *: EmptyTuple =
                if buffer == null then null.asInstanceOf[H *: EmptyTuple]
                else
                    val input = buffer.duplicate()

                    val elementSize = input.getInt
                    val element = if elementSize < 0 then
                        codec.decode(null, protocolVersion)
                    else
                        val element = input.slice()
                        element.limit(elementSize)
                        input.position(input.position() + elementSize)

                        codec.decode(element, protocolVersion)

                    element *: EmptyTuple
            end decode

            override private[codec] def format(value: H *: EmptyTuple, sb: StringBuilder): StringBuilder =
                sb.append(
                  if value == null then NULL
                  else codec.format(value.head)
                )
            end format

            override private[codec] def parse(value: String, idx: Int): (H *: EmptyTuple, Int) =
                val (parsed, next) = parseElementWithCodec(codec, value, idx)

                (parsed *: EmptyTuple) -> next
            end parse

    given headTupleElement[H, T <: Tuple](
        using codec: Codec[H],
        tail: TupleComponentCodec[T]
    ): TupleComponentCodec[H *: T] =
        new TupleComponentCodec[H *: T]:
            override val codecs: Seq[TypeCodec[?]] = codec +: tail.codecs

            inline def encode(
                value: H *: T,
                protocolVersion: ProtocolVersion
            ): (List[ByteBuffer], Int) =
                val (tailBuffer, tailSize) = tail.encode(value.tail, protocolVersion)
                val encoded                = codec.encode(value.head, protocolVersion)
                val size                   = if encoded == null then 4 else 4 + encoded.remaining()

                (encoded :: tailBuffer) -> (size + tailSize)
            end encode

            override def decode(buffer: ByteBuffer, protocolVersion: ProtocolVersion): H *: T =
                if buffer == null then null.asInstanceOf[H *: T]
                else
                    val input = buffer.duplicate()

                    val elementSize = input.getInt
                    val element = if elementSize < 0 then
                        codec.decode(null, protocolVersion)
                    else
                        val element = input.slice()
                        element.limit(elementSize)

                        codec.decode(element, protocolVersion)

                    element *: tail.decode(
                      input.position(input.position() + Math.max(0, elementSize)),
                      protocolVersion
                    )
            end decode

            override private[codec] def format(value: H *: T, sb: StringBuilder): StringBuilder =
                if value == null then sb.append(NULL)
                else
                    tail.format(
                      value.tail,
                      sb.append(codec.format(value.head)).append(separator)
                    )
            end format

            override private[codec] def parse(value: String, idx: Int): (H *: T, Int) =
                val (parsed, next)        = parseElementWithCodec(codec, value, idx)
                val (tupleTail, nextTail) = tail.parse(value, next)

                (parsed *: tupleTail) -> nextTail
            end parse

    given generateTupleCodec[T <: Tuple](using codec: TupleComponentCodec[T], tag: ClassTag[T]): Codec[T] =
        new Codec[T]:
            private val openingChar = '('
            private val closingChar = ')'

            private[helenus] val codecs: Seq[TypeCodec[?]] = codec.codecs

            // TODO can we generate this with a Macro?
            override def getJavaType(): GenericType[T] =
                GenericType.of(tag.runtimeClass.asInstanceOf[Class[T]])

            override def getCqlType(): DataType =
                import scala.jdk.CollectionConverters.*

                new DefaultTupleType(codecs.map(_.getCqlType()).asJava)

            override def encode(value: T, protocolVersion: ProtocolVersion): ByteBuffer =
                if value == null then null
                else
                    val (buffers, size) = codec.encode(value, protocolVersion)
                    val result          = ByteBuffer.allocate(size)

                    buffers.foreach { field =>
                        if field == null then result.putInt(-1)
                        else
                            result.putInt(field.remaining())
                            result.put(field.duplicate())
                    }

                    result.flip()
            end encode

            override def decode(buffer: ByteBuffer, protocolVersion: ProtocolVersion): T =
                if buffer == null then null.asInstanceOf[T]
                else codec.decode(buffer, protocolVersion)

            override def format(value: T): String =
                if value == null then NULL
                else
                    val sb = new mutable.StringBuilder()
                    sb.append(openingChar)
                    codec.format(value, sb)
                    sb.append(closingChar)

                    sb.toString()
            end format

            override def parse(value: String): T =
                if value == null || value.isEmpty || value.equalsIgnoreCase(NULL) then
                    null.asInstanceOf[T]
                else
                    val start = ParseUtils.skipSpaces(value, 0)

                    expectParseChar(value, start, openingChar)
                    val (parsed, end) = codec.parse(value, start)
                    expectParseChar(value, end, closingChar)

                    parsed
            end parse

            override def accepts(value: Any): Boolean = value match
                // FIXME This can still accept case classes with the same component types
                case product: Product if product.productArity == codecs.size =>
                    product.productIterator.zip(codecs.iterator).forall { case (element, codec) =>
                        codec.accepts(element)
                    }

                case _ =>
                    false
            end accepts

            override lazy val toString: String = s"TupleCodec[(${codecs.map(_.getCqlType).mkString(", ")})]"

    end generateTupleCodec

    /** Parses a tuple component, from a starting position.
      *
      * @param codec codec to use for parsing
      * @param value entire string to parse from
      * @param idx index where to start parsing of this specific tuple component
      * @tparam T tuple component type
      * @return tuple element, and next parsing position
      */
    private[codec] def parseElementWithCodec[T](
        codec: TypeCodec[T],
        value: String,
        idx: Int
    ): (T, Int) =
        val start         = ParseUtils.skipSpaces(value, idx + 1)
        val (parsed, end) = parseWithCodec(value, codec, start)

        val next = ParseUtils.skipSpaces(value, end)
        if next >= value.length then
            throw new IllegalArgumentException(
              s"Malformed tuple value '$value', expected something else but got EOF"
            )

        parsed -> next
    end parseElementWithCodec

end TupleCodecDerivation
