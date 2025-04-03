/*
 * Copyright 2021 the original author or authors
 *
 * SPDX-License-Identifier: MIT
 */

package net.nmoncho.helenus.internal.codec

import java.nio.ByteBuffer

import scala.collection.mutable
import scala.deriving.Mirror
import scala.reflect.ClassTag

import com.datastax.oss.driver.api.core.ProtocolVersion
import com.datastax.oss.driver.api.core.`type`.DataType
import com.datastax.oss.driver.api.core.`type`.codec.TypeCodec
import com.datastax.oss.driver.api.core.`type`.reflect.GenericType
import com.datastax.oss.driver.internal.core.`type`.DefaultTupleType
import com.datastax.oss.driver.internal.core.`type`.codec.ParseUtils
import com.datastax.oss.driver.shaded.guava.common.reflect.ScalaTypeParameter
import com.datastax.oss.driver.shaded.guava.common.reflect.ScalaTypeToken
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

    inline given generateTupleCodec[T <: Tuple](
        using codec: TupleComponentCodec[T],
        tag: ClassTag[T],
        m: Mirror.ProductOf[T]
    ): Codec[T] =
        val codecs: Seq[TypeCodec[?]] = codec.codecs

        new Codec[T]:
            private val openingChar = '('
            private val closingChar = ')'

            // TODO can we generate this with a Macro?
            override def getJavaType(): GenericType[T] =
                tupleGenericType[T]
            // GenericType.of(tag.runtimeClass.asInstanceOf[Class[T]])

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
        end new

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

    import scala.compiletime.*

    // format:off
    def gt2[T1: Codec, T2: Codec]: GenericType[(T1, T2)] =
        GenericType
            .of(new ScalaTypeToken[(T1, T2)] {}
                .where(new ScalaTypeParameter[T1] {}, summon[Codec[T1]].getJavaType().getType())
                .where(new ScalaTypeParameter[T2] {}, summon[Codec[T2]].getJavaType().getType())
                .getType())
            .asInstanceOf[GenericType[(T1, T2)]]

    def gt3[T1: Codec, T2: Codec, T3: Codec]: GenericType[(T1, T2, T3)] =
        GenericType
            .of(new ScalaTypeToken[(T1, T2, T3)] {}
                .where(new ScalaTypeParameter[T1] {}, summon[Codec[T1]].getJavaType().getType())
                .where(new ScalaTypeParameter[T2] {}, summon[Codec[T2]].getJavaType().getType())
                .where(new ScalaTypeParameter[T3] {}, summon[Codec[T3]].getJavaType().getType())
                .getType())
            .asInstanceOf[GenericType[(T1, T2, T3)]]

    def gt4[T1: Codec, T2: Codec, T3: Codec, T4: Codec]: GenericType[(T1, T2, T3, T4)] =
        GenericType
            .of(new ScalaTypeToken[(T1, T2, T3, T4)] {}
                .where(new ScalaTypeParameter[T1] {}, summon[Codec[T1]].getJavaType().getType())
                .where(new ScalaTypeParameter[T2] {}, summon[Codec[T2]].getJavaType().getType())
                .where(new ScalaTypeParameter[T3] {}, summon[Codec[T3]].getJavaType().getType())
                .where(new ScalaTypeParameter[T4] {}, summon[Codec[T4]].getJavaType().getType())
                .getType())
            .asInstanceOf[GenericType[(T1, T2, T3, T4)]]

    def gt5[T1: Codec, T2: Codec, T3: Codec, T4: Codec, T5: Codec]: GenericType[(T1, T2, T3, T4, T5)] =
        GenericType
            .of(
              new ScalaTypeToken[(T1, T2, T3, T4, T5)]() {}
                  .where(new ScalaTypeParameter[T1] {}, summon[Codec[T1]].getJavaType().getType())
                  .where(new ScalaTypeParameter[T2] {}, summon[Codec[T2]].getJavaType().getType())
                  .where(new ScalaTypeParameter[T3] {}, summon[Codec[T3]].getJavaType().getType())
                  .where(new ScalaTypeParameter[T4] {}, summon[Codec[T4]].getJavaType().getType())
                  .where(new ScalaTypeParameter[T5] {}, summon[Codec[T5]].getJavaType().getType())
                  .getType()
            )
            .asInstanceOf[GenericType[(T1, T2, T3, T4, T5)]]

    def gt6[T1: Codec, T2: Codec, T3: Codec, T4: Codec, T5: Codec, T6: Codec]: GenericType[(T1, T2, T3, T4, T5, T6)] =
        GenericType
            .of(new ScalaTypeToken[(T1, T2, T3, T4, T5, T6)]() {}
                .where(new ScalaTypeParameter[T1] {}, summon[Codec[T1]].getJavaType().getType())
                .where(new ScalaTypeParameter[T2] {}, summon[Codec[T2]].getJavaType().getType())
                .where(new ScalaTypeParameter[T3] {}, summon[Codec[T3]].getJavaType().getType())
                .where(new ScalaTypeParameter[T4] {}, summon[Codec[T4]].getJavaType().getType())
                .where(new ScalaTypeParameter[T5] {}, summon[Codec[T5]].getJavaType().getType())
                .where(new ScalaTypeParameter[T6] {}, summon[Codec[T6]].getJavaType().getType())
                .getType())
            .asInstanceOf[GenericType[(T1, T2, T3, T4, T5, T6)]]

    def gt7[T1: Codec, T2: Codec, T3: Codec, T4: Codec, T5: Codec, T6: Codec, T7: Codec]
        : GenericType[(T1, T2, T3, T4, T5, T6, T7)] =
        GenericType
            .of(new ScalaTypeToken[(T1, T2, T3, T4, T5, T6, T7)]() {}
                .where(new ScalaTypeParameter[T1] {}, summon[Codec[T1]].getJavaType().getType())
                .where(new ScalaTypeParameter[T2] {}, summon[Codec[T2]].getJavaType().getType())
                .where(new ScalaTypeParameter[T3] {}, summon[Codec[T3]].getJavaType().getType())
                .where(new ScalaTypeParameter[T4] {}, summon[Codec[T4]].getJavaType().getType())
                .where(new ScalaTypeParameter[T5] {}, summon[Codec[T5]].getJavaType().getType())
                .where(new ScalaTypeParameter[T6] {}, summon[Codec[T6]].getJavaType().getType())
                .where(new ScalaTypeParameter[T7] {}, summon[Codec[T7]].getJavaType().getType())
                .getType())
            .asInstanceOf[GenericType[(T1, T2, T3, T4, T5, T6, T7)]]

    def gt8[T1: Codec, T2: Codec, T3: Codec, T4: Codec, T5: Codec, T6: Codec, T7: Codec, T8: Codec]
        : GenericType[(T1, T2, T3, T4, T5, T6, T7, T8)] =
        GenericType
            .of(new ScalaTypeToken[(T1, T2, T3, T4, T5, T6, T7, T8)]() {}
                .where(new ScalaTypeParameter[T1] {}, summon[Codec[T1]].getJavaType().getType())
                .where(new ScalaTypeParameter[T2] {}, summon[Codec[T2]].getJavaType().getType())
                .where(new ScalaTypeParameter[T3] {}, summon[Codec[T3]].getJavaType().getType())
                .where(new ScalaTypeParameter[T4] {}, summon[Codec[T4]].getJavaType().getType())
                .where(new ScalaTypeParameter[T5] {}, summon[Codec[T5]].getJavaType().getType())
                .where(new ScalaTypeParameter[T6] {}, summon[Codec[T6]].getJavaType().getType())
                .where(new ScalaTypeParameter[T7] {}, summon[Codec[T7]].getJavaType().getType())
                .where(new ScalaTypeParameter[T8] {}, summon[Codec[T8]].getJavaType().getType())
                .getType())
            .asInstanceOf[GenericType[(T1, T2, T3, T4, T5, T6, T7, T8)]]

    def gt9[T1: Codec, T2: Codec, T3: Codec, T4: Codec, T5: Codec, T6: Codec, T7: Codec, T8: Codec, T9: Codec]
        : GenericType[(T1, T2, T3, T4, T5, T6, T7, T8, T9)] =
        GenericType
            .of(new ScalaTypeToken[(T1, T2, T3, T4, T5, T6, T7, T8, T9)]() {}
                .where(new ScalaTypeParameter[T1] {}, summon[Codec[T1]].getJavaType().getType())
                .where(new ScalaTypeParameter[T2] {}, summon[Codec[T2]].getJavaType().getType())
                .where(new ScalaTypeParameter[T3] {}, summon[Codec[T3]].getJavaType().getType())
                .where(new ScalaTypeParameter[T4] {}, summon[Codec[T4]].getJavaType().getType())
                .where(new ScalaTypeParameter[T5] {}, summon[Codec[T5]].getJavaType().getType())
                .where(new ScalaTypeParameter[T6] {}, summon[Codec[T6]].getJavaType().getType())
                .where(new ScalaTypeParameter[T7] {}, summon[Codec[T7]].getJavaType().getType())
                .where(new ScalaTypeParameter[T8] {}, summon[Codec[T8]].getJavaType().getType())
                .where(new ScalaTypeParameter[T9] {}, summon[Codec[T9]].getJavaType().getType())
                .getType())
            .asInstanceOf[GenericType[(T1, T2, T3, T4, T5, T6, T7, T8, T9)]]

    def gt10[
        T1: Codec,
        T2: Codec,
        T3: Codec,
        T4: Codec,
        T5: Codec,
        T6: Codec,
        T7: Codec,
        T8: Codec,
        T9: Codec,
        T10: Codec
    ]: GenericType[(T1, T2, T3, T4, T5, T6, T7, T8, T9, T10)] =
        GenericType
            .of(new ScalaTypeToken[(T1, T2, T3, T4, T5, T6, T7, T8, T9, T10)]() {}
                .where(new ScalaTypeParameter[T1] {}, summon[Codec[T1]].getJavaType().getType())
                .where(new ScalaTypeParameter[T2] {}, summon[Codec[T2]].getJavaType().getType())
                .where(new ScalaTypeParameter[T3] {}, summon[Codec[T3]].getJavaType().getType())
                .where(new ScalaTypeParameter[T4] {}, summon[Codec[T4]].getJavaType().getType())
                .where(new ScalaTypeParameter[T5] {}, summon[Codec[T5]].getJavaType().getType())
                .where(new ScalaTypeParameter[T6] {}, summon[Codec[T6]].getJavaType().getType())
                .where(new ScalaTypeParameter[T7] {}, summon[Codec[T7]].getJavaType().getType())
                .where(new ScalaTypeParameter[T8] {}, summon[Codec[T8]].getJavaType().getType())
                .where(new ScalaTypeParameter[T9] {}, summon[Codec[T9]].getJavaType().getType())
                .where(new ScalaTypeParameter[T10] {}, summon[Codec[T10]].getJavaType().getType())
                .getType())
            .asInstanceOf[GenericType[(T1, T2, T3, T4, T5, T6, T7, T8, T9, T10)]]

    def gt11[
        T1: Codec,
        T2: Codec,
        T3: Codec,
        T4: Codec,
        T5: Codec,
        T6: Codec,
        T7: Codec,
        T8: Codec,
        T9: Codec,
        T10: Codec,
        T11: Codec
    ]: GenericType[(T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11)] =
        GenericType
            .of(new ScalaTypeToken[(T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11)]() {}
                .where(new ScalaTypeParameter[T1] {}, summon[Codec[T1]].getJavaType().getType())
                .where(new ScalaTypeParameter[T2] {}, summon[Codec[T2]].getJavaType().getType())
                .where(new ScalaTypeParameter[T3] {}, summon[Codec[T3]].getJavaType().getType())
                .where(new ScalaTypeParameter[T4] {}, summon[Codec[T4]].getJavaType().getType())
                .where(new ScalaTypeParameter[T5] {}, summon[Codec[T5]].getJavaType().getType())
                .where(new ScalaTypeParameter[T6] {}, summon[Codec[T6]].getJavaType().getType())
                .where(new ScalaTypeParameter[T7] {}, summon[Codec[T7]].getJavaType().getType())
                .where(new ScalaTypeParameter[T8] {}, summon[Codec[T8]].getJavaType().getType())
                .where(new ScalaTypeParameter[T9] {}, summon[Codec[T9]].getJavaType().getType())
                .where(new ScalaTypeParameter[T10] {}, summon[Codec[T10]].getJavaType().getType())
                .where(new ScalaTypeParameter[T11] {}, summon[Codec[T11]].getJavaType().getType())
                .getType())
            .asInstanceOf[GenericType[(T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11)]]

    def gt12[
        T1: Codec,
        T2: Codec,
        T3: Codec,
        T4: Codec,
        T5: Codec,
        T6: Codec,
        T7: Codec,
        T8: Codec,
        T9: Codec,
        T10: Codec,
        T11: Codec,
        T12: Codec
    ]: GenericType[(T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12)] =
        GenericType
            .of(new ScalaTypeToken[(T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12)]() {}
                .where(new ScalaTypeParameter[T1] {}, summon[Codec[T1]].getJavaType().getType())
                .where(new ScalaTypeParameter[T2] {}, summon[Codec[T2]].getJavaType().getType())
                .where(new ScalaTypeParameter[T3] {}, summon[Codec[T3]].getJavaType().getType())
                .where(new ScalaTypeParameter[T4] {}, summon[Codec[T4]].getJavaType().getType())
                .where(new ScalaTypeParameter[T5] {}, summon[Codec[T5]].getJavaType().getType())
                .where(new ScalaTypeParameter[T6] {}, summon[Codec[T6]].getJavaType().getType())
                .where(new ScalaTypeParameter[T7] {}, summon[Codec[T7]].getJavaType().getType())
                .where(new ScalaTypeParameter[T8] {}, summon[Codec[T8]].getJavaType().getType())
                .where(new ScalaTypeParameter[T9] {}, summon[Codec[T9]].getJavaType().getType())
                .where(new ScalaTypeParameter[T10] {}, summon[Codec[T10]].getJavaType().getType())
                .where(new ScalaTypeParameter[T11] {}, summon[Codec[T11]].getJavaType().getType())
                .where(new ScalaTypeParameter[T12] {}, summon[Codec[T12]].getJavaType().getType())
                .getType())
            .asInstanceOf[GenericType[(T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12)]]

    def gt13[
        T1: Codec,
        T2: Codec,
        T3: Codec,
        T4: Codec,
        T5: Codec,
        T6: Codec,
        T7: Codec,
        T8: Codec,
        T9: Codec,
        T10: Codec,
        T11: Codec,
        T12: Codec,
        T13: Codec
    ]: GenericType[(T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13)] =
        GenericType
            .of(new ScalaTypeToken[(T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13)]() {}
                .where(new ScalaTypeParameter[T1] {}, summon[Codec[T1]].getJavaType().getType())
                .where(new ScalaTypeParameter[T2] {}, summon[Codec[T2]].getJavaType().getType())
                .where(new ScalaTypeParameter[T3] {}, summon[Codec[T3]].getJavaType().getType())
                .where(new ScalaTypeParameter[T4] {}, summon[Codec[T4]].getJavaType().getType())
                .where(new ScalaTypeParameter[T5] {}, summon[Codec[T5]].getJavaType().getType())
                .where(new ScalaTypeParameter[T6] {}, summon[Codec[T6]].getJavaType().getType())
                .where(new ScalaTypeParameter[T7] {}, summon[Codec[T7]].getJavaType().getType())
                .where(new ScalaTypeParameter[T8] {}, summon[Codec[T8]].getJavaType().getType())
                .where(new ScalaTypeParameter[T9] {}, summon[Codec[T9]].getJavaType().getType())
                .where(new ScalaTypeParameter[T10] {}, summon[Codec[T10]].getJavaType().getType())
                .where(new ScalaTypeParameter[T11] {}, summon[Codec[T11]].getJavaType().getType())
                .where(new ScalaTypeParameter[T12] {}, summon[Codec[T12]].getJavaType().getType())
                .where(new ScalaTypeParameter[T13] {}, summon[Codec[T13]].getJavaType().getType())
                .getType())
            .asInstanceOf[GenericType[(T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13)]]

    def gt14[
        T1: Codec,
        T2: Codec,
        T3: Codec,
        T4: Codec,
        T5: Codec,
        T6: Codec,
        T7: Codec,
        T8: Codec,
        T9: Codec,
        T10: Codec,
        T11: Codec,
        T12: Codec,
        T13: Codec,
        T14: Codec
    ]: GenericType[(T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14)] =
        GenericType
            .of(new ScalaTypeToken[(T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14)]() {}
                .where(new ScalaTypeParameter[T1] {}, summon[Codec[T1]].getJavaType().getType())
                .where(new ScalaTypeParameter[T2] {}, summon[Codec[T2]].getJavaType().getType())
                .where(new ScalaTypeParameter[T3] {}, summon[Codec[T3]].getJavaType().getType())
                .where(new ScalaTypeParameter[T4] {}, summon[Codec[T4]].getJavaType().getType())
                .where(new ScalaTypeParameter[T5] {}, summon[Codec[T5]].getJavaType().getType())
                .where(new ScalaTypeParameter[T6] {}, summon[Codec[T6]].getJavaType().getType())
                .where(new ScalaTypeParameter[T7] {}, summon[Codec[T7]].getJavaType().getType())
                .where(new ScalaTypeParameter[T8] {}, summon[Codec[T8]].getJavaType().getType())
                .where(new ScalaTypeParameter[T9] {}, summon[Codec[T9]].getJavaType().getType())
                .where(new ScalaTypeParameter[T10] {}, summon[Codec[T10]].getJavaType().getType())
                .where(new ScalaTypeParameter[T11] {}, summon[Codec[T11]].getJavaType().getType())
                .where(new ScalaTypeParameter[T12] {}, summon[Codec[T12]].getJavaType().getType())
                .where(new ScalaTypeParameter[T13] {}, summon[Codec[T13]].getJavaType().getType())
                .where(new ScalaTypeParameter[T14] {}, summon[Codec[T14]].getJavaType().getType())
                .getType())
            .asInstanceOf[GenericType[(T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14)]]

    def gt15[
        T1: Codec,
        T2: Codec,
        T3: Codec,
        T4: Codec,
        T5: Codec,
        T6: Codec,
        T7: Codec,
        T8: Codec,
        T9: Codec,
        T10: Codec,
        T11: Codec,
        T12: Codec,
        T13: Codec,
        T14: Codec,
        T15: Codec
    ]: GenericType[(T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15)] =
        GenericType
            .of(new ScalaTypeToken[(T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15)]() {}
                .where(new ScalaTypeParameter[T1] {}, summon[Codec[T1]].getJavaType().getType())
                .where(new ScalaTypeParameter[T2] {}, summon[Codec[T2]].getJavaType().getType())
                .where(new ScalaTypeParameter[T3] {}, summon[Codec[T3]].getJavaType().getType())
                .where(new ScalaTypeParameter[T4] {}, summon[Codec[T4]].getJavaType().getType())
                .where(new ScalaTypeParameter[T5] {}, summon[Codec[T5]].getJavaType().getType())
                .where(new ScalaTypeParameter[T6] {}, summon[Codec[T6]].getJavaType().getType())
                .where(new ScalaTypeParameter[T7] {}, summon[Codec[T7]].getJavaType().getType())
                .where(new ScalaTypeParameter[T8] {}, summon[Codec[T8]].getJavaType().getType())
                .where(new ScalaTypeParameter[T9] {}, summon[Codec[T9]].getJavaType().getType())
                .where(new ScalaTypeParameter[T10] {}, summon[Codec[T10]].getJavaType().getType())
                .where(new ScalaTypeParameter[T11] {}, summon[Codec[T11]].getJavaType().getType())
                .where(new ScalaTypeParameter[T12] {}, summon[Codec[T12]].getJavaType().getType())
                .where(new ScalaTypeParameter[T13] {}, summon[Codec[T13]].getJavaType().getType())
                .where(new ScalaTypeParameter[T14] {}, summon[Codec[T14]].getJavaType().getType())
                .where(new ScalaTypeParameter[T15] {}, summon[Codec[T15]].getJavaType().getType())
                .getType())
            .asInstanceOf[GenericType[(T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15)]]

    def gt16[
        T1: Codec,
        T2: Codec,
        T3: Codec,
        T4: Codec,
        T5: Codec,
        T6: Codec,
        T7: Codec,
        T8: Codec,
        T9: Codec,
        T10: Codec,
        T11: Codec,
        T12: Codec,
        T13: Codec,
        T14: Codec,
        T15: Codec,
        T16: Codec
    ]: GenericType[(T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16)] =
        GenericType
            .of(new ScalaTypeToken[(T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16)] {}
                .where(new ScalaTypeParameter[T1] {}, summon[Codec[T1]].getJavaType().getType())
                .where(new ScalaTypeParameter[T2] {}, summon[Codec[T2]].getJavaType().getType())
                .where(new ScalaTypeParameter[T3] {}, summon[Codec[T3]].getJavaType().getType())
                .where(new ScalaTypeParameter[T4] {}, summon[Codec[T4]].getJavaType().getType())
                .where(new ScalaTypeParameter[T5] {}, summon[Codec[T5]].getJavaType().getType())
                .where(new ScalaTypeParameter[T6] {}, summon[Codec[T6]].getJavaType().getType())
                .where(new ScalaTypeParameter[T7] {}, summon[Codec[T7]].getJavaType().getType())
                .where(new ScalaTypeParameter[T8] {}, summon[Codec[T8]].getJavaType().getType())
                .where(new ScalaTypeParameter[T9] {}, summon[Codec[T9]].getJavaType().getType())
                .where(new ScalaTypeParameter[T10] {}, summon[Codec[T10]].getJavaType().getType())
                .where(new ScalaTypeParameter[T11] {}, summon[Codec[T11]].getJavaType().getType())
                .where(new ScalaTypeParameter[T12] {}, summon[Codec[T12]].getJavaType().getType())
                .where(new ScalaTypeParameter[T13] {}, summon[Codec[T13]].getJavaType().getType())
                .where(new ScalaTypeParameter[T14] {}, summon[Codec[T14]].getJavaType().getType())
                .where(new ScalaTypeParameter[T15] {}, summon[Codec[T15]].getJavaType().getType())
                .where(new ScalaTypeParameter[T16] {}, summon[Codec[T16]].getJavaType().getType())
                .getType())
            .asInstanceOf[GenericType[(T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16)]]

    def gt17[
        T1: Codec,
        T2: Codec,
        T3: Codec,
        T4: Codec,
        T5: Codec,
        T6: Codec,
        T7: Codec,
        T8: Codec,
        T9: Codec,
        T10: Codec,
        T11: Codec,
        T12: Codec,
        T13: Codec,
        T14: Codec,
        T15: Codec,
        T16: Codec,
        T17: Codec
    ]: GenericType[(T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17)] =
        GenericType
            .of(new ScalaTypeToken[(T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17)] {}
                .where(new ScalaTypeParameter[T1] {}, summon[Codec[T1]].getJavaType().getType())
                .where(new ScalaTypeParameter[T2] {}, summon[Codec[T2]].getJavaType().getType())
                .where(new ScalaTypeParameter[T3] {}, summon[Codec[T3]].getJavaType().getType())
                .where(new ScalaTypeParameter[T4] {}, summon[Codec[T4]].getJavaType().getType())
                .where(new ScalaTypeParameter[T5] {}, summon[Codec[T5]].getJavaType().getType())
                .where(new ScalaTypeParameter[T6] {}, summon[Codec[T6]].getJavaType().getType())
                .where(new ScalaTypeParameter[T7] {}, summon[Codec[T7]].getJavaType().getType())
                .where(new ScalaTypeParameter[T8] {}, summon[Codec[T8]].getJavaType().getType())
                .where(new ScalaTypeParameter[T9] {}, summon[Codec[T9]].getJavaType().getType())
                .where(new ScalaTypeParameter[T10] {}, summon[Codec[T10]].getJavaType().getType())
                .where(new ScalaTypeParameter[T11] {}, summon[Codec[T11]].getJavaType().getType())
                .where(new ScalaTypeParameter[T12] {}, summon[Codec[T12]].getJavaType().getType())
                .where(new ScalaTypeParameter[T13] {}, summon[Codec[T13]].getJavaType().getType())
                .where(new ScalaTypeParameter[T14] {}, summon[Codec[T14]].getJavaType().getType())
                .where(new ScalaTypeParameter[T15] {}, summon[Codec[T15]].getJavaType().getType())
                .where(new ScalaTypeParameter[T16] {}, summon[Codec[T16]].getJavaType().getType())
                .where(new ScalaTypeParameter[T17] {}, summon[Codec[T17]].getJavaType().getType())
                .getType())
            .asInstanceOf[GenericType[(T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17)]]

    def gt18[
        T1: Codec,
        T2: Codec,
        T3: Codec,
        T4: Codec,
        T5: Codec,
        T6: Codec,
        T7: Codec,
        T8: Codec,
        T9: Codec,
        T10: Codec,
        T11: Codec,
        T12: Codec,
        T13: Codec,
        T14: Codec,
        T15: Codec,
        T16: Codec,
        T17: Codec,
        T18: Codec
    ]: GenericType[(T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18)] =
        GenericType
            .of(new ScalaTypeToken[(T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18)] {}
                .where(new ScalaTypeParameter[T1] {}, summon[Codec[T1]].getJavaType().getType())
                .where(new ScalaTypeParameter[T2] {}, summon[Codec[T2]].getJavaType().getType())
                .where(new ScalaTypeParameter[T3] {}, summon[Codec[T3]].getJavaType().getType())
                .where(new ScalaTypeParameter[T4] {}, summon[Codec[T4]].getJavaType().getType())
                .where(new ScalaTypeParameter[T5] {}, summon[Codec[T5]].getJavaType().getType())
                .where(new ScalaTypeParameter[T6] {}, summon[Codec[T6]].getJavaType().getType())
                .where(new ScalaTypeParameter[T7] {}, summon[Codec[T7]].getJavaType().getType())
                .where(new ScalaTypeParameter[T8] {}, summon[Codec[T8]].getJavaType().getType())
                .where(new ScalaTypeParameter[T9] {}, summon[Codec[T9]].getJavaType().getType())
                .where(new ScalaTypeParameter[T10] {}, summon[Codec[T10]].getJavaType().getType())
                .where(new ScalaTypeParameter[T11] {}, summon[Codec[T11]].getJavaType().getType())
                .where(new ScalaTypeParameter[T12] {}, summon[Codec[T12]].getJavaType().getType())
                .where(new ScalaTypeParameter[T13] {}, summon[Codec[T13]].getJavaType().getType())
                .where(new ScalaTypeParameter[T14] {}, summon[Codec[T14]].getJavaType().getType())
                .where(new ScalaTypeParameter[T15] {}, summon[Codec[T15]].getJavaType().getType())
                .where(new ScalaTypeParameter[T16] {}, summon[Codec[T16]].getJavaType().getType())
                .where(new ScalaTypeParameter[T17] {}, summon[Codec[T17]].getJavaType().getType())
                .where(new ScalaTypeParameter[T18] {}, summon[Codec[T18]].getJavaType().getType())
                .getType())
            .asInstanceOf[GenericType[(
                T1,
                T2,
                T3,
                T4,
                T5,
                T6,
                T7,
                T8,
                T9,
                T10,
                T11,
                T12,
                T13,
                T14,
                T15,
                T16,
                T17,
                T18
            )]]

    def gt19[
        T1: Codec,
        T2: Codec,
        T3: Codec,
        T4: Codec,
        T5: Codec,
        T6: Codec,
        T7: Codec,
        T8: Codec,
        T9: Codec,
        T10: Codec,
        T11: Codec,
        T12: Codec,
        T13: Codec,
        T14: Codec,
        T15: Codec,
        T16: Codec,
        T17: Codec,
        T18: Codec,
        T19: Codec
    ]: GenericType[(T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19)] =
        GenericType
            .of(new ScalaTypeToken[(
                T1,
                T2,
                T3,
                T4,
                T5,
                T6,
                T7,
                T8,
                T9,
                T10,
                T11,
                T12,
                T13,
                T14,
                T15,
                T16,
                T17,
                T18,
                T19
            )] {}
                .where(new ScalaTypeParameter[T1] {}, summon[Codec[T1]].getJavaType().getType())
                .where(new ScalaTypeParameter[T2] {}, summon[Codec[T2]].getJavaType().getType())
                .where(new ScalaTypeParameter[T3] {}, summon[Codec[T3]].getJavaType().getType())
                .where(new ScalaTypeParameter[T4] {}, summon[Codec[T4]].getJavaType().getType())
                .where(new ScalaTypeParameter[T5] {}, summon[Codec[T5]].getJavaType().getType())
                .where(new ScalaTypeParameter[T6] {}, summon[Codec[T6]].getJavaType().getType())
                .where(new ScalaTypeParameter[T7] {}, summon[Codec[T7]].getJavaType().getType())
                .where(new ScalaTypeParameter[T8] {}, summon[Codec[T8]].getJavaType().getType())
                .where(new ScalaTypeParameter[T9] {}, summon[Codec[T9]].getJavaType().getType())
                .where(new ScalaTypeParameter[T10] {}, summon[Codec[T10]].getJavaType().getType())
                .where(new ScalaTypeParameter[T11] {}, summon[Codec[T11]].getJavaType().getType())
                .where(new ScalaTypeParameter[T12] {}, summon[Codec[T12]].getJavaType().getType())
                .where(new ScalaTypeParameter[T13] {}, summon[Codec[T13]].getJavaType().getType())
                .where(new ScalaTypeParameter[T14] {}, summon[Codec[T14]].getJavaType().getType())
                .where(new ScalaTypeParameter[T15] {}, summon[Codec[T15]].getJavaType().getType())
                .where(new ScalaTypeParameter[T16] {}, summon[Codec[T16]].getJavaType().getType())
                .where(new ScalaTypeParameter[T17] {}, summon[Codec[T17]].getJavaType().getType())
                .where(new ScalaTypeParameter[T18] {}, summon[Codec[T18]].getJavaType().getType())
                .where(new ScalaTypeParameter[T19] {}, summon[Codec[T19]].getJavaType().getType())
                .getType())
            .asInstanceOf[GenericType[(
                T1,
                T2,
                T3,
                T4,
                T5,
                T6,
                T7,
                T8,
                T9,
                T10,
                T11,
                T12,
                T13,
                T14,
                T15,
                T16,
                T17,
                T18,
                T19
            )]]

    def gt20[
        T1: Codec,
        T2: Codec,
        T3: Codec,
        T4: Codec,
        T5: Codec,
        T6: Codec,
        T7: Codec,
        T8: Codec,
        T9: Codec,
        T10: Codec,
        T11: Codec,
        T12: Codec,
        T13: Codec,
        T14: Codec,
        T15: Codec,
        T16: Codec,
        T17: Codec,
        T18: Codec,
        T19: Codec,
        T20: Codec
    ]: GenericType[(T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, T20)] =
        GenericType
            .of(new ScalaTypeToken[(
                T1,
                T2,
                T3,
                T4,
                T5,
                T6,
                T7,
                T8,
                T9,
                T10,
                T11,
                T12,
                T13,
                T14,
                T15,
                T16,
                T17,
                T18,
                T19,
                T20
            )] {}
                .where(new ScalaTypeParameter[T1] {}, summon[Codec[T1]].getJavaType().getType())
                .where(new ScalaTypeParameter[T2] {}, summon[Codec[T2]].getJavaType().getType())
                .where(new ScalaTypeParameter[T3] {}, summon[Codec[T3]].getJavaType().getType())
                .where(new ScalaTypeParameter[T4] {}, summon[Codec[T4]].getJavaType().getType())
                .where(new ScalaTypeParameter[T5] {}, summon[Codec[T5]].getJavaType().getType())
                .where(new ScalaTypeParameter[T6] {}, summon[Codec[T6]].getJavaType().getType())
                .where(new ScalaTypeParameter[T7] {}, summon[Codec[T7]].getJavaType().getType())
                .where(new ScalaTypeParameter[T8] {}, summon[Codec[T8]].getJavaType().getType())
                .where(new ScalaTypeParameter[T9] {}, summon[Codec[T9]].getJavaType().getType())
                .where(new ScalaTypeParameter[T10] {}, summon[Codec[T10]].getJavaType().getType())
                .where(new ScalaTypeParameter[T11] {}, summon[Codec[T11]].getJavaType().getType())
                .where(new ScalaTypeParameter[T12] {}, summon[Codec[T12]].getJavaType().getType())
                .where(new ScalaTypeParameter[T13] {}, summon[Codec[T13]].getJavaType().getType())
                .where(new ScalaTypeParameter[T14] {}, summon[Codec[T14]].getJavaType().getType())
                .where(new ScalaTypeParameter[T15] {}, summon[Codec[T15]].getJavaType().getType())
                .where(new ScalaTypeParameter[T16] {}, summon[Codec[T16]].getJavaType().getType())
                .where(new ScalaTypeParameter[T17] {}, summon[Codec[T17]].getJavaType().getType())
                .where(new ScalaTypeParameter[T18] {}, summon[Codec[T18]].getJavaType().getType())
                .where(new ScalaTypeParameter[T19] {}, summon[Codec[T19]].getJavaType().getType())
                .where(new ScalaTypeParameter[T19] {}, summon[Codec[T20]].getJavaType().getType())
                .getType())
            .asInstanceOf[GenericType[(
                T1,
                T2,
                T3,
                T4,
                T5,
                T6,
                T7,
                T8,
                T9,
                T10,
                T11,
                T12,
                T13,
                T14,
                T15,
                T16,
                T17,
                T18,
                T19,
                T20
            )]]

    def gt21[
        T1: Codec,
        T2: Codec,
        T3: Codec,
        T4: Codec,
        T5: Codec,
        T6: Codec,
        T7: Codec,
        T8: Codec,
        T9: Codec,
        T10: Codec,
        T11: Codec,
        T12: Codec,
        T13: Codec,
        T14: Codec,
        T15: Codec,
        T16: Codec,
        T17: Codec,
        T18: Codec,
        T19: Codec,
        T20: Codec,
        T21: Codec
    ]: GenericType[(T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, T20, T21)] =
        GenericType
            .of(new ScalaTypeToken[(
                T1,
                T2,
                T3,
                T4,
                T5,
                T6,
                T7,
                T8,
                T9,
                T10,
                T11,
                T12,
                T13,
                T14,
                T15,
                T16,
                T17,
                T18,
                T19,
                T20,
                T21
            )] {}
                .where(new ScalaTypeParameter[T1] {}, summon[Codec[T1]].getJavaType().getType())
                .where(new ScalaTypeParameter[T2] {}, summon[Codec[T2]].getJavaType().getType())
                .where(new ScalaTypeParameter[T3] {}, summon[Codec[T3]].getJavaType().getType())
                .where(new ScalaTypeParameter[T4] {}, summon[Codec[T4]].getJavaType().getType())
                .where(new ScalaTypeParameter[T5] {}, summon[Codec[T5]].getJavaType().getType())
                .where(new ScalaTypeParameter[T6] {}, summon[Codec[T6]].getJavaType().getType())
                .where(new ScalaTypeParameter[T7] {}, summon[Codec[T7]].getJavaType().getType())
                .where(new ScalaTypeParameter[T8] {}, summon[Codec[T8]].getJavaType().getType())
                .where(new ScalaTypeParameter[T9] {}, summon[Codec[T9]].getJavaType().getType())
                .where(new ScalaTypeParameter[T10] {}, summon[Codec[T10]].getJavaType().getType())
                .where(new ScalaTypeParameter[T11] {}, summon[Codec[T11]].getJavaType().getType())
                .where(new ScalaTypeParameter[T12] {}, summon[Codec[T12]].getJavaType().getType())
                .where(new ScalaTypeParameter[T13] {}, summon[Codec[T13]].getJavaType().getType())
                .where(new ScalaTypeParameter[T14] {}, summon[Codec[T14]].getJavaType().getType())
                .where(new ScalaTypeParameter[T15] {}, summon[Codec[T15]].getJavaType().getType())
                .where(new ScalaTypeParameter[T16] {}, summon[Codec[T16]].getJavaType().getType())
                .where(new ScalaTypeParameter[T17] {}, summon[Codec[T17]].getJavaType().getType())
                .where(new ScalaTypeParameter[T18] {}, summon[Codec[T18]].getJavaType().getType())
                .where(new ScalaTypeParameter[T19] {}, summon[Codec[T19]].getJavaType().getType())
                .where(new ScalaTypeParameter[T19] {}, summon[Codec[T20]].getJavaType().getType())
                .where(new ScalaTypeParameter[T19] {}, summon[Codec[T21]].getJavaType().getType())
                .getType())
            .asInstanceOf[GenericType[(
                T1,
                T2,
                T3,
                T4,
                T5,
                T6,
                T7,
                T8,
                T9,
                T10,
                T11,
                T12,
                T13,
                T14,
                T15,
                T16,
                T17,
                T18,
                T19,
                T20,
                T21
            )]]

    def gt22[
        T1: Codec,
        T2: Codec,
        T3: Codec,
        T4: Codec,
        T5: Codec,
        T6: Codec,
        T7: Codec,
        T8: Codec,
        T9: Codec,
        T10: Codec,
        T11: Codec,
        T12: Codec,
        T13: Codec,
        T14: Codec,
        T15: Codec,
        T16: Codec,
        T17: Codec,
        T18: Codec,
        T19: Codec,
        T20: Codec,
        T21: Codec,
        T22: Codec
    ]: GenericType[(
        T1,
        T2,
        T3,
        T4,
        T5,
        T6,
        T7,
        T8,
        T9,
        T10,
        T11,
        T12,
        T13,
        T14,
        T15,
        T16,
        T17,
        T18,
        T19,
        T20,
        T21,
        T22
    )] =
        GenericType
            .of(new ScalaTypeToken[(
                T1,
                T2,
                T3,
                T4,
                T5,
                T6,
                T7,
                T8,
                T9,
                T10,
                T11,
                T12,
                T13,
                T14,
                T15,
                T16,
                T17,
                T18,
                T19,
                T20,
                T21,
                T22
            )] {}
                .where(new ScalaTypeParameter[T1] {}, summon[Codec[T1]].getJavaType().getType())
                .where(new ScalaTypeParameter[T2] {}, summon[Codec[T2]].getJavaType().getType())
                .where(new ScalaTypeParameter[T3] {}, summon[Codec[T3]].getJavaType().getType())
                .where(new ScalaTypeParameter[T4] {}, summon[Codec[T4]].getJavaType().getType())
                .where(new ScalaTypeParameter[T5] {}, summon[Codec[T5]].getJavaType().getType())
                .where(new ScalaTypeParameter[T6] {}, summon[Codec[T6]].getJavaType().getType())
                .where(new ScalaTypeParameter[T7] {}, summon[Codec[T7]].getJavaType().getType())
                .where(new ScalaTypeParameter[T8] {}, summon[Codec[T8]].getJavaType().getType())
                .where(new ScalaTypeParameter[T9] {}, summon[Codec[T9]].getJavaType().getType())
                .where(new ScalaTypeParameter[T10] {}, summon[Codec[T10]].getJavaType().getType())
                .where(new ScalaTypeParameter[T11] {}, summon[Codec[T11]].getJavaType().getType())
                .where(new ScalaTypeParameter[T12] {}, summon[Codec[T12]].getJavaType().getType())
                .where(new ScalaTypeParameter[T13] {}, summon[Codec[T13]].getJavaType().getType())
                .where(new ScalaTypeParameter[T14] {}, summon[Codec[T14]].getJavaType().getType())
                .where(new ScalaTypeParameter[T15] {}, summon[Codec[T15]].getJavaType().getType())
                .where(new ScalaTypeParameter[T16] {}, summon[Codec[T16]].getJavaType().getType())
                .where(new ScalaTypeParameter[T17] {}, summon[Codec[T17]].getJavaType().getType())
                .where(new ScalaTypeParameter[T18] {}, summon[Codec[T18]].getJavaType().getType())
                .where(new ScalaTypeParameter[T19] {}, summon[Codec[T19]].getJavaType().getType())
                .where(new ScalaTypeParameter[T20] {}, summon[Codec[T20]].getJavaType().getType())
                .where(new ScalaTypeParameter[T21] {}, summon[Codec[T21]].getJavaType().getType())
                .where(new ScalaTypeParameter[T22] {}, summon[Codec[T22]].getJavaType().getType())
                .getType())
            .asInstanceOf[GenericType[(
                T1,
                T2,
                T3,
                T4,
                T5,
                T6,
                T7,
                T8,
                T9,
                T10,
                T11,
                T12,
                T13,
                T14,
                T15,
                T16,
                T17,
                T18,
                T19,
                T20,
                T21,
                T22
            )]]

    inline def tupleGenericType[T <: Tuple](using m: Mirror.ProductOf[T]): GenericType[T] =
        inline erasedValue[T] match
            case _: (elem1 *: elem2 *: EmptyTuple) =>
                gt2[elem1, elem2](using summonInline[Codec[elem1]], summonInline[Codec[elem2]])
                    .asInstanceOf[GenericType[T]]

            case _: (elem1 *: elem2 *: elem3 *: EmptyTuple) =>
                gt3[elem1, elem2, elem3](
                  using summonInline[Codec[elem1]],
                  summonInline[Codec[elem2]],
                  summonInline[Codec[elem3]]
                )
                    .asInstanceOf[GenericType[T]]

            case _: (elem1 *: elem2 *: elem3 *: elem4 *: EmptyTuple) =>
                gt4[elem1, elem2, elem3, elem4](
                  using summonInline[Codec[elem1]],
                  summonInline[Codec[elem2]],
                  summonInline[Codec[elem3]],
                  summonInline[Codec[elem4]]
                )
                    .asInstanceOf[GenericType[T]]

            case _: (elem1 *: elem2 *: elem3 *: elem4 *: elem5 *: EmptyTuple) =>
                gt5[elem1, elem2, elem3, elem4, elem5](
                  using summonInline[Codec[elem1]],
                  summonInline[Codec[elem2]],
                  summonInline[Codec[elem3]],
                  summonInline[Codec[elem4]],
                  summonInline[Codec[elem5]]
                )
                    .asInstanceOf[GenericType[T]]

            case _: (elem1 *: elem2 *: elem3 *: elem4 *: elem5 *: elem6 *: elem7 *: EmptyTuple) =>
                gt7[elem1, elem2, elem3, elem4, elem5, elem6, elem7](
                  using summonInline[Codec[elem1]],
                  summonInline[Codec[elem2]],
                  summonInline[Codec[elem3]],
                  summonInline[Codec[elem4]],
                  summonInline[Codec[elem5]],
                  summonInline[Codec[elem6]],
                  summonInline[Codec[elem7]]
                )
                    .asInstanceOf[GenericType[T]]

            case _: (elem1 *: elem2 *: elem3 *: elem4 *: elem5 *: elem6 *: elem7 *: elem8 *: EmptyTuple) =>
                gt8[elem1, elem2, elem3, elem4, elem5, elem6, elem7, elem8](
                  using summonInline[Codec[elem1]],
                  summonInline[Codec[elem2]],
                  summonInline[Codec[elem3]],
                  summonInline[Codec[elem4]],
                  summonInline[Codec[elem5]],
                  summonInline[Codec[elem6]],
                  summonInline[Codec[elem7]],
                  summonInline[Codec[elem8]]
                )
                    .asInstanceOf[GenericType[T]]

            case _: (elem1 *: elem2 *: elem3 *: elem4 *: elem5 *: elem6 *: elem7 *: elem8 *: elem9 *: EmptyTuple) =>
                gt9[elem1, elem2, elem3, elem4, elem5, elem6, elem7, elem8, elem9](
                  using summonInline[Codec[elem1]],
                  summonInline[Codec[elem2]],
                  summonInline[Codec[elem3]],
                  summonInline[Codec[elem4]],
                  summonInline[Codec[elem5]],
                  summonInline[Codec[elem6]],
                  summonInline[Codec[elem7]],
                  summonInline[Codec[elem8]],
                  summonInline[Codec[elem9]]
                )
                    .asInstanceOf[GenericType[T]]

            case _: (elem1 *: elem2 *: elem3 *: elem4 *: elem5 *: elem6 *: elem7 *: elem8 *: elem9 *: elem10 *: EmptyTuple) =>
                gt10[elem1, elem2, elem3, elem4, elem5, elem6, elem7, elem8, elem9, elem10](
                  using summonInline[Codec[elem1]],
                  summonInline[Codec[elem2]],
                  summonInline[Codec[elem3]],
                  summonInline[Codec[elem4]],
                  summonInline[Codec[elem5]],
                  summonInline[Codec[elem6]],
                  summonInline[Codec[elem7]],
                  summonInline[Codec[elem8]],
                  summonInline[Codec[elem9]],
                  summonInline[Codec[elem10]]
                )
                    .asInstanceOf[GenericType[T]]

            case _: (elem1 *: elem2 *: elem3 *: elem4 *: elem5 *: elem6 *: elem7 *: elem8 *: elem9 *: elem10 *: elem11 *: EmptyTuple) =>
                gt11[elem1, elem2, elem3, elem4, elem5, elem6, elem7, elem8, elem9, elem10, elem11](
                  using summonInline[Codec[elem1]],
                  summonInline[Codec[elem2]],
                  summonInline[Codec[elem3]],
                  summonInline[Codec[elem4]],
                  summonInline[Codec[elem5]],
                  summonInline[Codec[elem6]],
                  summonInline[Codec[elem7]],
                  summonInline[Codec[elem8]],
                  summonInline[Codec[elem9]],
                  summonInline[Codec[elem10]],
                  summonInline[Codec[elem11]]
                )
                    .asInstanceOf[GenericType[T]]

            case _: (elem1 *: elem2 *: elem3 *: elem4 *: elem5 *: elem6 *: elem7 *: elem8 *: elem9 *: elem10 *: elem11 *: elem12 *: EmptyTuple) =>
                gt12[elem1, elem2, elem3, elem4, elem5, elem6, elem7, elem8, elem9, elem10, elem11, elem12](
                  using summonInline[Codec[elem1]],
                  summonInline[Codec[elem2]],
                  summonInline[Codec[elem3]],
                  summonInline[Codec[elem4]],
                  summonInline[Codec[elem5]],
                  summonInline[Codec[elem6]],
                  summonInline[Codec[elem7]],
                  summonInline[Codec[elem8]],
                  summonInline[Codec[elem9]],
                  summonInline[Codec[elem10]],
                  summonInline[Codec[elem11]],
                  summonInline[Codec[elem12]]
                )
                    .asInstanceOf[GenericType[T]]

            case _: (elem1 *: elem2 *: elem3 *: elem4 *: elem5 *: elem6 *: elem7 *: elem8 *: elem9 *: elem10 *: elem11 *: elem12 *: elem13 *: EmptyTuple) =>
                gt13[elem1, elem2, elem3, elem4, elem5, elem6, elem7, elem8, elem9, elem10, elem11, elem12, elem13](
                  using summonInline[Codec[elem1]],
                  summonInline[Codec[elem2]],
                  summonInline[Codec[elem3]],
                  summonInline[Codec[elem4]],
                  summonInline[Codec[elem5]],
                  summonInline[Codec[elem6]],
                  summonInline[Codec[elem7]],
                  summonInline[Codec[elem8]],
                  summonInline[Codec[elem9]],
                  summonInline[Codec[elem10]],
                  summonInline[Codec[elem11]],
                  summonInline[Codec[elem12]],
                  summonInline[Codec[elem13]]
                )
                    .asInstanceOf[GenericType[T]]

            case _: (elem1 *: elem2 *: elem3 *: elem4 *: elem5 *: elem6 *: elem7 *: elem8 *: elem9 *: elem10 *: elem11 *: elem12 *: elem13 *: elem14 *: EmptyTuple) =>
                gt14[
                  elem1,
                  elem2,
                  elem3,
                  elem4,
                  elem5,
                  elem6,
                  elem7,
                  elem8,
                  elem9,
                  elem10,
                  elem11,
                  elem12,
                  elem13,
                  elem14
                ](
                  using summonInline[Codec[elem1]],
                  summonInline[Codec[elem2]],
                  summonInline[Codec[elem3]],
                  summonInline[Codec[elem4]],
                  summonInline[Codec[elem5]],
                  summonInline[Codec[elem6]],
                  summonInline[Codec[elem7]],
                  summonInline[Codec[elem8]],
                  summonInline[Codec[elem9]],
                  summonInline[Codec[elem10]],
                  summonInline[Codec[elem11]],
                  summonInline[Codec[elem12]],
                  summonInline[Codec[elem13]],
                  summonInline[Codec[elem14]]
                )
                    .asInstanceOf[GenericType[T]]

            case _: (elem1 *: elem2 *: elem3 *: elem4 *: elem5 *: elem6 *: elem7 *: elem8 *: elem9 *: elem10 *: elem11 *: elem12 *: elem13 *: elem14 *: elem15 *: EmptyTuple) =>
                gt15[
                  elem1,
                  elem2,
                  elem3,
                  elem4,
                  elem5,
                  elem6,
                  elem7,
                  elem8,
                  elem9,
                  elem10,
                  elem11,
                  elem12,
                  elem13,
                  elem14,
                  elem15
                ](
                  using summonInline[Codec[elem1]],
                  summonInline[Codec[elem2]],
                  summonInline[Codec[elem3]],
                  summonInline[Codec[elem4]],
                  summonInline[Codec[elem5]],
                  summonInline[Codec[elem6]],
                  summonInline[Codec[elem7]],
                  summonInline[Codec[elem8]],
                  summonInline[Codec[elem9]],
                  summonInline[Codec[elem10]],
                  summonInline[Codec[elem11]],
                  summonInline[Codec[elem12]],
                  summonInline[Codec[elem13]],
                  summonInline[Codec[elem14]],
                  summonInline[Codec[elem15]]
                )
                    .asInstanceOf[GenericType[T]]

            case _: (elem1 *: elem2 *: elem3 *: elem4 *: elem5 *: elem6 *: elem7 *: elem8 *: elem9 *: elem10 *: elem11 *: elem12 *: elem13 *: elem14 *: elem15 *: elem16 *: EmptyTuple) =>
                gt16[
                  elem1,
                  elem2,
                  elem3,
                  elem4,
                  elem5,
                  elem6,
                  elem7,
                  elem8,
                  elem9,
                  elem10,
                  elem11,
                  elem12,
                  elem13,
                  elem14,
                  elem15,
                  elem16
                ](
                  using summonInline[Codec[elem1]],
                  summonInline[Codec[elem2]],
                  summonInline[Codec[elem3]],
                  summonInline[Codec[elem4]],
                  summonInline[Codec[elem5]],
                  summonInline[Codec[elem6]],
                  summonInline[Codec[elem7]],
                  summonInline[Codec[elem8]],
                  summonInline[Codec[elem9]],
                  summonInline[Codec[elem10]],
                  summonInline[Codec[elem11]],
                  summonInline[Codec[elem12]],
                  summonInline[Codec[elem13]],
                  summonInline[Codec[elem14]],
                  summonInline[Codec[elem15]],
                  summonInline[Codec[elem16]]
                )
                    .asInstanceOf[GenericType[T]]

            case _: (elem1 *: elem2 *: elem3 *: elem4 *: elem5 *: elem6 *: elem7 *: elem8 *: elem9 *: elem10 *: elem11 *: elem12 *: elem13 *: elem14 *: elem15 *: elem16 *: elem17 *: EmptyTuple) =>
                gt17[
                  elem1,
                  elem2,
                  elem3,
                  elem4,
                  elem5,
                  elem6,
                  elem7,
                  elem8,
                  elem9,
                  elem10,
                  elem11,
                  elem12,
                  elem13,
                  elem14,
                  elem15,
                  elem16,
                  elem17
                ](
                  using summonInline[Codec[elem1]],
                  summonInline[Codec[elem2]],
                  summonInline[Codec[elem3]],
                  summonInline[Codec[elem4]],
                  summonInline[Codec[elem5]],
                  summonInline[Codec[elem6]],
                  summonInline[Codec[elem7]],
                  summonInline[Codec[elem8]],
                  summonInline[Codec[elem9]],
                  summonInline[Codec[elem10]],
                  summonInline[Codec[elem11]],
                  summonInline[Codec[elem12]],
                  summonInline[Codec[elem13]],
                  summonInline[Codec[elem14]],
                  summonInline[Codec[elem15]],
                  summonInline[Codec[elem16]],
                  summonInline[Codec[elem17]]
                )
                    .asInstanceOf[GenericType[T]]

            case _: (elem1 *: elem2 *: elem3 *: elem4 *: elem5 *: elem6 *: elem7 *: elem8 *: elem9 *: elem10 *: elem11 *: elem12 *: elem13 *: elem14 *: elem15 *: elem16 *: elem17 *: elem18 *: EmptyTuple) =>
                gt18[
                  elem1,
                  elem2,
                  elem3,
                  elem4,
                  elem5,
                  elem6,
                  elem7,
                  elem8,
                  elem9,
                  elem10,
                  elem11,
                  elem12,
                  elem13,
                  elem14,
                  elem15,
                  elem16,
                  elem17,
                  elem18
                ](
                  using summonInline[Codec[elem1]],
                  summonInline[Codec[elem2]],
                  summonInline[Codec[elem3]],
                  summonInline[Codec[elem4]],
                  summonInline[Codec[elem5]],
                  summonInline[Codec[elem6]],
                  summonInline[Codec[elem7]],
                  summonInline[Codec[elem8]],
                  summonInline[Codec[elem9]],
                  summonInline[Codec[elem10]],
                  summonInline[Codec[elem11]],
                  summonInline[Codec[elem12]],
                  summonInline[Codec[elem13]],
                  summonInline[Codec[elem14]],
                  summonInline[Codec[elem15]],
                  summonInline[Codec[elem16]],
                  summonInline[Codec[elem17]],
                  summonInline[Codec[elem18]]
                )
                    .asInstanceOf[GenericType[T]]

            case _: (elem1 *: elem2 *: elem3 *: elem4 *: elem5 *: elem6 *: elem7 *: elem8 *: elem9 *: elem10 *: elem11 *: elem12 *: elem13 *: elem14 *: elem15 *: elem16 *: elem17 *: elem18 *: elem19 *: EmptyTuple) =>
                gt19[
                  elem1,
                  elem2,
                  elem3,
                  elem4,
                  elem5,
                  elem6,
                  elem7,
                  elem8,
                  elem9,
                  elem10,
                  elem11,
                  elem12,
                  elem13,
                  elem14,
                  elem15,
                  elem16,
                  elem17,
                  elem18,
                  elem19
                ](
                  using summonInline[Codec[elem1]],
                  summonInline[Codec[elem2]],
                  summonInline[Codec[elem3]],
                  summonInline[Codec[elem4]],
                  summonInline[Codec[elem5]],
                  summonInline[Codec[elem6]],
                  summonInline[Codec[elem7]],
                  summonInline[Codec[elem8]],
                  summonInline[Codec[elem9]],
                  summonInline[Codec[elem10]],
                  summonInline[Codec[elem11]],
                  summonInline[Codec[elem12]],
                  summonInline[Codec[elem13]],
                  summonInline[Codec[elem14]],
                  summonInline[Codec[elem15]],
                  summonInline[Codec[elem16]],
                  summonInline[Codec[elem17]],
                  summonInline[Codec[elem18]],
                  summonInline[Codec[elem19]]
                )
                    .asInstanceOf[GenericType[T]]

            case _: (elem1 *: elem2 *: elem3 *: elem4 *: elem5 *: elem6 *: elem7 *: elem8 *: elem9 *: elem10 *: elem11 *: elem12 *: elem13 *: elem14 *: elem15 *: elem16 *: elem17 *: elem18 *: elem19 *: elem20 *: EmptyTuple) =>
                gt20[
                  elem1,
                  elem2,
                  elem3,
                  elem4,
                  elem5,
                  elem6,
                  elem7,
                  elem8,
                  elem9,
                  elem10,
                  elem11,
                  elem12,
                  elem13,
                  elem14,
                  elem15,
                  elem16,
                  elem17,
                  elem18,
                  elem19,
                  elem20
                ](
                  using summonInline[Codec[elem1]],
                  summonInline[Codec[elem2]],
                  summonInline[Codec[elem3]],
                  summonInline[Codec[elem4]],
                  summonInline[Codec[elem5]],
                  summonInline[Codec[elem6]],
                  summonInline[Codec[elem7]],
                  summonInline[Codec[elem8]],
                  summonInline[Codec[elem9]],
                  summonInline[Codec[elem10]],
                  summonInline[Codec[elem11]],
                  summonInline[Codec[elem12]],
                  summonInline[Codec[elem13]],
                  summonInline[Codec[elem14]],
                  summonInline[Codec[elem15]],
                  summonInline[Codec[elem16]],
                  summonInline[Codec[elem17]],
                  summonInline[Codec[elem18]],
                  summonInline[Codec[elem19]],
                  summonInline[Codec[elem20]]
                )
                    .asInstanceOf[GenericType[T]]

            case _: (elem1 *: elem2 *: elem3 *: elem4 *: elem5 *: elem6 *: elem7 *: elem8 *: elem9 *: elem10 *: elem11 *: elem12 *: elem13 *: elem14 *: elem15 *: elem16 *: elem17 *: elem18 *: elem19 *: elem20 *: elem21 *: EmptyTuple) =>
                gt21[
                  elem1,
                  elem2,
                  elem3,
                  elem4,
                  elem5,
                  elem6,
                  elem7,
                  elem8,
                  elem9,
                  elem10,
                  elem11,
                  elem12,
                  elem13,
                  elem14,
                  elem15,
                  elem16,
                  elem17,
                  elem18,
                  elem19,
                  elem20,
                  elem21
                ](
                  using summonInline[Codec[elem1]],
                  summonInline[Codec[elem2]],
                  summonInline[Codec[elem3]],
                  summonInline[Codec[elem4]],
                  summonInline[Codec[elem5]],
                  summonInline[Codec[elem6]],
                  summonInline[Codec[elem7]],
                  summonInline[Codec[elem8]],
                  summonInline[Codec[elem9]],
                  summonInline[Codec[elem10]],
                  summonInline[Codec[elem11]],
                  summonInline[Codec[elem12]],
                  summonInline[Codec[elem13]],
                  summonInline[Codec[elem14]],
                  summonInline[Codec[elem15]],
                  summonInline[Codec[elem16]],
                  summonInline[Codec[elem17]],
                  summonInline[Codec[elem18]],
                  summonInline[Codec[elem19]],
                  summonInline[Codec[elem20]],
                  summonInline[Codec[elem21]]
                )
                    .asInstanceOf[GenericType[T]]

            case _: (elem1 *: elem2 *: elem3 *: elem4 *: elem5 *: elem6 *: elem7 *: elem8 *: elem9 *: elem10 *: elem11 *: elem12 *: elem13 *: elem14 *: elem15 *: elem16 *: elem17 *: elem18 *: elem19 *: elem20 *: elem21 *: elem22 *: EmptyTuple) =>
                gt22[
                  elem1,
                  elem2,
                  elem3,
                  elem4,
                  elem5,
                  elem6,
                  elem7,
                  elem8,
                  elem9,
                  elem10,
                  elem11,
                  elem12,
                  elem13,
                  elem14,
                  elem15,
                  elem16,
                  elem17,
                  elem18,
                  elem19,
                  elem20,
                  elem21,
                  elem22
                ](
                  using summonInline[Codec[elem1]],
                  summonInline[Codec[elem2]],
                  summonInline[Codec[elem3]],
                  summonInline[Codec[elem4]],
                  summonInline[Codec[elem5]],
                  summonInline[Codec[elem6]],
                  summonInline[Codec[elem7]],
                  summonInline[Codec[elem8]],
                  summonInline[Codec[elem9]],
                  summonInline[Codec[elem10]],
                  summonInline[Codec[elem11]],
                  summonInline[Codec[elem12]],
                  summonInline[Codec[elem13]],
                  summonInline[Codec[elem14]],
                  summonInline[Codec[elem15]],
                  summonInline[Codec[elem16]],
                  summonInline[Codec[elem17]],
                  summonInline[Codec[elem18]],
                  summonInline[Codec[elem19]],
                  summonInline[Codec[elem20]],
                  summonInline[Codec[elem21]],
                  summonInline[Codec[elem22]]
                )
                    .asInstanceOf[GenericType[T]]
    // format:on

end TupleCodecDerivation
