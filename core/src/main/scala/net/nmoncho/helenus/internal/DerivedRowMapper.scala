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

package net.nmoncho.helenus.internal

import scala.compiletime.constValue
import scala.compiletime.erasedValue
import scala.compiletime.error
import scala.compiletime.summonInline
import scala.deriving.Mirror
import scala.util.NotGiven

import com.datastax.oss.driver.api.core.cql.Row
import net.nmoncho.helenus.api.ColumnNamingScheme
import net.nmoncho.helenus.api.DefaultColumnNamingScheme
import net.nmoncho.helenus.api.RowMapper
import net.nmoncho.helenus.api.RowMapper.ColumnMapper
import net.nmoncho.helenus.api.`type`.codec.Codec

trait DerivedRowMapper[T] extends RowMapper[T]

object DerivedRowMapper:

    given tupleRowMapper[T <: Tuple: DerivedTupleRowMapper]: DerivedRowMapper[T] = summon[DerivedTupleRowMapper[T]]

    /** Derives a [[RowMapper]] from a [[TypeCodec]] when [[T]] isn't a `Product`
      */
    given simpleRowMapper[T](
        using ev: NotGiven[T <:< Product],
        codec: Codec[T]
    ): DerivedRowMapper[T] with
        def apply(row: Row): T = row.get(0, codec)
    end simpleRowMapper

end DerivedRowMapper

trait DerivedTupleRowMapper[T] extends DerivedRowMapper[T]:
    private[internal] def apply(idx: Int, row: Row): T

    override def apply(row: Row): T = apply(0, row)

end DerivedTupleRowMapper

object DerivedTupleRowMapper:

    given lastTupleElement[H](using codec: Codec[H]): DerivedTupleRowMapper[H *: EmptyTuple] with
        override def apply(idx: Int, row: Row): H *: EmptyTuple =
            row.get(idx, codec) *: EmptyTuple
    end lastTupleElement

    given headTupleElement[H, T <: Tuple: DerivedTupleRowMapper](
        using codec: Codec[H],
        tail: DerivedTupleRowMapper[T]
    ): DerivedTupleRowMapper[H *: T] with
        override def apply(idx: Int, row: Row): H *: T =
            row.get(idx, codec) *: tail(idx + 1, row)
    end headTupleElement

end DerivedTupleRowMapper

trait DerivedCaseClassRowMapper:

    inline def summonInstances[Elems](fieldNames: Seq[String]): DerivedRowMapper[Elems] =
        inline erasedValue[Elems] match
            case _: (elem *: EmptyTuple) =>
                val colDecoder = summonInline[ColumnMapper[elem]]

                new DerivedRowMapper[Elems]:
                    override def apply(row: Row): Elems =
                        (colDecoder(fieldNames.head, row) *: EmptyTuple).asInstanceOf[Elems]

            case _: (elem *: elems) =>
                val colDecoder = summonInline[ColumnMapper[elem]]
                val tail       = summonInstances[elems](fieldNames.tail)

                new DerivedRowMapper[Elems]:
                    override def apply(row: Row): Elems =
                        (colDecoder(fieldNames.head, row) *: tail(row)).asInstanceOf

            case EmptyTuple =>
                error("Empty Case Classes are not supported")

    end summonInstances

    inline def derived[T <: Product](
        using m: Mirror.ProductOf[T],
        l: Labelling[T],
        namingScheme: ColumnNamingScheme = DefaultColumnNamingScheme
    ): DerivedRowMapper[T] =
        lazy val elementsRowMapper = summonInstances[m.MirroredElemTypes](l.elemLabels.map(namingScheme.map))

        new DerivedRowMapper[T]:
            override def apply(row: Row): T = m.fromTuple(elementsRowMapper(row))
    end derived

end DerivedCaseClassRowMapper
