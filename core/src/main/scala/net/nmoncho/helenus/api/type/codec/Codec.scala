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

package net.nmoncho.helenus.api.`type`.codec

import java.net.InetAddress
import java.nio.ByteBuffer
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

import scala.collection.immutable.SortedMap
import scala.collection.immutable.SortedSet
import scala.collection.mutable as mutablecoll
import scala.reflect.ClassTag

import com.datastax.dse.driver.api.core.data.geometry.LineString
import com.datastax.dse.driver.api.core.data.geometry.Point
import com.datastax.dse.driver.api.core.data.geometry.Polygon
import com.datastax.dse.driver.api.core.data.time.DateRange
import com.datastax.oss.driver.api.core.ProtocolVersion
import com.datastax.oss.driver.api.core.`type`.DataType
import com.datastax.oss.driver.api.core.`type`.codec.TypeCodec
import com.datastax.oss.driver.api.core.`type`.reflect.GenericType
import net.nmoncho.helenus.internal.codec.*
import net.nmoncho.helenus.internal.codec.TupleCodecDerivation
import net.nmoncho.helenus.internal.codec.collection.*

trait Codec[T] extends TypeCodec[T]

end Codec

object Codec extends TupleCodecDerivation:

    def apply[T](using codec: Codec[T]): Codec[T] = codec

    def wrap[T](underlying: TypeCodec[T]): Codec[T] = underlying match
        case codec: Codec[?] => underlying.asInstanceOf[Codec[T]]
        case _ => new Codec[T]:
                override def decode(bytes: ByteBuffer, protocolVersion: ProtocolVersion): T =
                    underlying.decode(bytes, protocolVersion)

                override def encode(value: T, protocolVersion: ProtocolVersion): ByteBuffer =
                    underlying.encode(value, protocolVersion)

                override def getCqlType(): DataType =
                    underlying.getCqlType()

                override def getJavaType(): GenericType[T] =
                    underlying.getJavaType()

                override def format(value: T): String =
                    underlying.format(value)

                override def parse(value: String): T =
                    underlying.parse(value)

        // TODO add other methods like `accept`
    end wrap

    /** Creates a new mapping codec providing support for [[Outer]] based on an existing codec for [[Inner]].
      *
      * @param toOuter how to map from [[Inner]] to [[Outer]].
      * @param toInner how to map from [[Outer]] to [[Inner]].
      * @param codec The inner codec to use to handle instances of [[Inner]]; must not be null.
      * @param tag [[Outer]] ClassTag
      */
    def mappingCodec[Inner, Outer](
        toOuter: Inner => Outer,
        toInner: Outer => Inner
    )(using codec: Codec[Inner], tag: ClassTag[Outer]): Codec[Outer] = wrap(
      new MappingCodec[Inner, Outer](
        codec,
        GenericType.of(tag.runtimeClass.asInstanceOf[Class[Outer]])
      ):
          override def innerToOuter(value: Inner): Outer = toOuter(value)

          override def outerToInner(value: Outer): Inner = toInner(value)
    )

    given Codec[BigDecimal] = wrap(Codecs.bigDecimalCodec)
    given Codec[BigInt]     = wrap(Codecs.bigIntCodec)
    given Codec[Boolean]    = wrap(Codecs.booleanCodec)
    given Codec[Byte]       = wrap(Codecs.byteCodec)
    given Codec[Double]     = wrap(Codecs.doubleCodec)
    given Codec[Float]      = wrap(Codecs.floatCodec)
    given Codec[Int]        = wrap(Codecs.intCodec)
    given Codec[Long]       = wrap(Codecs.longCodec)
    given Codec[Short]      = wrap(Codecs.shortCodec)
    given Codec[String]     = wrap(Codecs.stringCodec)

    given Codec[UUID] = wrap(Codecs.uuidCodec)

    given Codec[Instant]   = wrap(Codecs.instantCodec)
    given Codec[LocalDate] = wrap(Codecs.localDateCodec)
    given Codec[LocalTime] = wrap(Codecs.localTimeCodec)

    given Codec[ByteBuffer] = wrap(Codecs.byteBufferCodec)

    given Codec[InetAddress] = wrap(Codecs.inetAddressCodec)

    given Codec[LineString] = wrap(Codecs.lineStringCodec)
    given Codec[Point]      = wrap(Codecs.pointCodec)
    given Codec[Polygon]    = wrap(Codecs.polygonCodec)
    given Codec[DateRange]  = wrap(Codecs.dateRangeCodec)

    given [A, B](using left: Codec[A], right: Codec[B]): Codec[Either[A, B]] = wrap(EitherCodec(left, right))

    given [T](using inner: Codec[T]): Codec[Option[T]] = wrap(OptionCodec(inner))
    given [T](using inner: Codec[T]): Codec[Seq[T]]    = wrap(SeqCodec.frozen(inner))
    given [T](using inner: Codec[T]): Codec[List[T]]   = wrap(ListCodec.frozen(inner))
    given [T](using inner: Codec[T]): Codec[Vector[T]] = wrap(VectorCodec.frozen(inner))
    given [T](using inner: Codec[T]): Codec[Set[T]]    = wrap(SetCodec.frozen(inner))

    given [T: Ordering](using inner: Codec[T]): Codec[SortedSet[T]] = wrap(SortedSetCodec.frozen(inner))

    given [K, V](using keyInner: Codec[K], valueInner: Codec[V]): Codec[Map[K, V]] =
        wrap(MapCodec.frozen(keyInner, valueInner))
    given [K: Ordering, V](using keyInner: Codec[K], valueInner: Codec[V]): Codec[SortedMap[K, V]] =
        wrap(SortedMapCodec.frozen(keyInner, valueInner))

    given [T](using inner: Codec[T]): Codec[mutablecoll.Buffer[T]] = wrap(collection.mutable.BufferCodec.frozen(inner))
    given [T](using inner: Codec[T]): Codec[mutablecoll.IndexedSeq[T]] =
        wrap(collection.mutable.IndexedSeqCodec.frozen(inner))
    given mutableSet[T](using inner: Codec[T]): Codec[mutablecoll.Set[T]] = wrap(mutable.SetCodec.frozen(inner))
    given mutableMap[K, V](using keyInner: Codec[K], valueInner: Codec[V]): Codec[mutablecoll.Map[K, V]] =
        wrap(collection.mutable.MapCodec.frozen(keyInner, valueInner))

end Codec
