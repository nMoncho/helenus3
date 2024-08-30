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
package udt

import java.nio.ByteBuffer

import scala.collection.mutable
import scala.deriving.Mirror
import scala.reflect.ClassTag

import com.datastax.oss.driver.api.core.CqlIdentifier
import com.datastax.oss.driver.api.core.ProtocolVersion
import com.datastax.oss.driver.api.core.`type`.DataType
import com.datastax.oss.driver.api.core.`type`.UserDefinedType
import com.datastax.oss.driver.api.core.`type`.reflect.GenericType
import com.datastax.oss.driver.internal.core.`type`.DefaultUserDefinedType
import com.datastax.oss.driver.internal.core.`type`.codec.ParseUtils
import com.datastax.oss.driver.shaded.guava.common.reflect.ScalaTypeToken
import net.nmoncho.helenus.api.ColumnNamingScheme
import net.nmoncho.helenus.api.DefaultColumnNamingScheme
import net.nmoncho.helenus.api.`type`.codec.Codec
import net.nmoncho.helenus.api.`type`.codec.UDTCodec
import net.nmoncho.helenus.internal.Labelling

/** A [[IdenticalUDTCodec]] is one that maps a case class to a UDT, both having the <strong>same</strong>
  * field order, for example:
  *
  * {{{
  * case class IceCream(name: String, numCherries: Int, cone: Boolean)
  *
  * TYPE ice_cream(name TEXT, num_cherries INT, cone BOOLEAN)
  * }}}
  *
  * For case classes and UDTs that don't align on the field order, please use [[NonIdenticalUDTCodec]].
  * See our Developer Notes for more information.
  *
  * This trait follows [[TypeCodec]] interface closely, with some "tail-rec" modifications to handle
  * shapeless encoding.
  *
  * @tparam A case class being encoded
  */
trait IdenticalUDTCodec[A]:

    def columns: List[(String, DataType)]

    /** Encodes an [[A]] value as a [[ByteBuffer]], and appends it to a list
      *
      * @param value           value to be encoded
      * @param protocolVersion DSE Version in use
      * @return accumulated buffers, one per case class component, with the total buffer size
      */
    def encode(value: A, protocolVersion: ProtocolVersion): (List[ByteBuffer], Int)

    /** Decodes an [[A]] value from a [[ByteCodec]]
      *
      * @param buffer buffer to be decoded
      * @param protocolVersion DSE Version in use
      * @return a decoded [[A]] value
      */
    def decode(buffer: ByteBuffer, protocolVersion: ProtocolVersion): A

    /** Formats an [[A]] value
      *
      * @param value value to be formatted
      * @param sb where to format into
      * @return builder with formatted value
      */
    def format(value: A, sb: mutable.StringBuilder): mutable.StringBuilder

    /** Parses an [[A]] value from a String
      *
      * @param value where to parse from
      * @param idx from which index to start parsing
      * @return a parsed [[A]] value, and an index from where to continue parsing
      */
    def parse(value: String, idx: Int): (A, Int)

end IdenticalUDTCodec

object IdenticalUDTCodec:
    private val openingChar    = '{'
    private val fieldSeparator = ':'
    private val separator      = ','
    private val closingChar    = '}'

    import scala.compiletime.*

    inline def summonInstances[Elems <: Tuple](
        fieldNames: Seq[String],
        namingScheme: ColumnNamingScheme
    ): IdenticalUDTCodec[Elems] =
        inline erasedValue[Elems] match
            case _: (elem *: EmptyTuple) =>
                deriveLast[elem](fieldNames, namingScheme).asInstanceOf[IdenticalUDTCodec[Elems]]

            case _: (elem *: elems) =>
                deriveHList[elem, elems](fieldNames, namingScheme).asInstanceOf[IdenticalUDTCodec[Elems]]

        end match
    end summonInstances

    inline def deriveLast[H](
        fieldNames: Seq[String],
        namingScheme: ColumnNamingScheme
    ): IdenticalUDTCodec[H *: EmptyTuple] =
        val fieldName = fieldNames.head
        val column    = namingScheme.map(fieldName)
        val codec     = summonInline[Codec[H]]

        new IdenticalUDTCodec[H *: EmptyTuple]:
            override val columns: List[(String, DataType)] = List(column -> codec.getCqlType())

            override def encode(value: H *: EmptyTuple, protocolVersion: ProtocolVersion): (List[ByteBuffer], Int) =
                val encoded = codec.encode(value.head, protocolVersion)
                val size    = if encoded == null then 4 else 4 + encoded.remaining()

                List(encoded) -> size
            end encode

            override def decode(buffer: ByteBuffer, protocolVersion: ProtocolVersion): H *: EmptyTuple =
                if buffer == null then null.asInstanceOf[H *: EmptyTuple]
                else
                    val input = buffer.duplicate()

                    val elementSize = input.getInt()
                    val element = if elementSize < 0 then codec.decode(null, protocolVersion)
                    else
                        val elementBuffer = input.slice()
                        elementBuffer.limit(elementSize)
                        input.position(input.position() + elementSize)
                        codec.decode(elementBuffer, protocolVersion)

                    element *: EmptyTuple
            end decode

            override def format(value: H *: EmptyTuple, sb: StringBuilder): StringBuilder =
                sb.append(namingScheme.asCql(fieldName, pretty = true)).append(fieldSeparator).append {
                    value match
                        case null | null *: _ => NULL
                        case head *: _ => codec.format(head)
                }

            override def parse(value: String, idx: Int): (H *: EmptyTuple, Int) =
                val fieldNameEnd =
                    skipSpacesAndExpectId(value, idx, namingScheme.asCql(fieldName, pretty = true))
                val valueStart     = skipSpacesAndExpect(value, fieldNameEnd, fieldSeparator)
                val (parsed, next) = parseWithCodec(value, codec, valueStart)

                (parsed *: EmptyTuple) -> next
            end parse
        end new
    end deriveLast

    inline def deriveHList[H, T <: Tuple](
        fieldNames: Seq[String],
        namingScheme: ColumnNamingScheme
    ): IdenticalUDTCodec[H *: T] =
        val fieldName = fieldNames.head
        val column    = namingScheme.map(fieldName)
        val codec     = summonInline[Codec[H]]
        val tail      = summonInstances[T](fieldNames.tail, namingScheme)

        new IdenticalUDTCodec[H *: T]:
            override val columns: List[(String, DataType)] =
                (column -> codec.getCqlType()) :: tail.columns

            override def encode(value: H *: T, protocolVersion: ProtocolVersion): (List[ByteBuffer], Int) =
                val (tailBuffer, tailSize) = tail.encode(value.tail, protocolVersion)
                val encoded                = codec.encode(value.head, protocolVersion)
                val size                   = if encoded == null then 4 else 4 + encoded.remaining()

                (encoded :: tailBuffer) -> (size + tailSize)
            end encode

            override def decode(buffer: ByteBuffer, protocolVersion: ProtocolVersion): H *: T =
                if buffer == null then null.asInstanceOf[H *: T]
                else
                    val input = buffer.duplicate()

                    val elementSize = input.getInt()
                    val element = if elementSize < 0 then
                        codec.decode(null, protocolVersion)
                    else
                        val elementBuffer = input.slice()
                        elementBuffer.limit(elementSize)
                        codec.decode(elementBuffer, protocolVersion)

                    element *: tail.decode(
                      input.position(input.position() + Math.max(0, elementSize)),
                      protocolVersion
                    )

            override def format(value: H *: T, sb: StringBuilder): StringBuilder =
                val head = sb.append(namingScheme.asCql(fieldName, pretty = true))
                    .append(fieldSeparator)
                    .append:
                        value match
                            case null | null *: _ => NULL
                            case head *: _ => codec.format(head)
                    .append(separator)

                tail.format(value.tail, head)
            end format

            override def parse(value: String, idx: Int): (H *: T, Int) =
                val fieldNameEnd =
                    skipSpacesAndExpectId(value, idx, namingScheme.asCql(fieldName, pretty = true))
                val valueStart             = skipSpacesAndExpect(value, fieldNameEnd, fieldSeparator)
                val (parsed, valueEnd)     = parseWithCodec(value, codec, valueStart)
                val afterValue             = skipSpacesAndExpect(value, valueEnd, separator)
                val (parsedTail, nextTail) = tail.parse(value, afterValue)

                (parsed *: parsedTail) -> nextTail
            end parse
        end new
    end deriveHList

    inline def deriveCodec[A <: Product](keyspace: Option[String], name: Option[String], frozen: Boolean)(
        using mirror: Mirror.ProductOf[A],
        labelling: Labelling[A],
        tag: ClassTag[A],
        namingScheme: ColumnNamingScheme = DefaultColumnNamingScheme
    ): UDTCodec[A] =
        val codec = summonInstances[mirror.MirroredElemTypes](labelling.elemLabels, namingScheme)

        val actualKeyspace = keyspace.getOrElse("system")
        val actualName     = name.getOrElse(namingScheme.map(labelling.label))

        new UDTCodec[A]:

            override def fields: Seq[(String, DataType)] = codec.columns

            override def encode(value: A, protocolVersion: ProtocolVersion): ByteBuffer =
                if value == null then null
                else
                    val (buffers, size) = codec.encode(Tuple.fromProductTyped(value), protocolVersion)
                    val result          = ByteBuffer.allocate(size)

                    buffers.foreach: field =>
                        if field == null then
                            result.putInt(-1)
                        else
                            result.putInt(field.remaining())
                            result.put(field.duplicate())

                    result.flip()

            override def decode(bytes: ByteBuffer, protocolVersion: ProtocolVersion): A =
                mirror.fromTuple(codec.decode(bytes, protocolVersion))

            override val getCqlType: UserDefinedType =
                import scala.jdk.CollectionConverters.*

                val (identifiers, dataTypes) =
                    codec.columns.foldRight(List.empty[CqlIdentifier] -> List.empty[DataType]):
                        case ((name, dataType), (identifiers, dataTypes)) =>
                            (CqlIdentifier.fromInternal(name) :: identifiers) -> (dataType :: dataTypes)

                new DefaultUserDefinedType(
                  CqlIdentifier.fromInternal(actualKeyspace),
                  CqlIdentifier.fromInternal(actualName),
                  frozen,
                  identifiers.asJava,
                  dataTypes.asJava
                )
            end getCqlType

            override val getJavaType: GenericType[A] =
                GenericType.of(new ScalaTypeToken[A] {}.getType()).asInstanceOf[GenericType[A]]

            override def format(value: A): String =
                if value == null then NULL
                else
                    val sb = new mutable.StringBuilder().append(openingChar)
                    val t  = Tuple.fromProductTyped(value)
                    codec.format(t, sb)

                    sb.append(closingChar).toString()

            override def parse(value: String): A =
                if value == null || value.isEmpty || value.equalsIgnoreCase(NULL) then null.asInstanceOf[A]
                else
                    val start = ParseUtils.skipSpaces(value, 0)

                    expectParseChar(value, start, openingChar)
                    val (parsed, end) = codec.parse(value, start + 1)
                    expectParseChar(value, end, closingChar)

                    mirror.fromTuple(parsed)

            override def accepts(cqlType: DataType): Boolean = cqlType match
                case udt: UserDefinedType =>
                    udt.getFieldNames == getCqlType.getFieldNames &&
                    udt.getFieldTypes == getCqlType.getFieldTypes

                case _ => false
        end new
    end deriveCodec

end IdenticalUDTCodec
