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

package net.nmoncho.helenus
package api
package cql

import scala.deriving.Mirror
import scala.reflect.ClassTag

import com.datastax.oss.driver.api.core.`type`.codec.TypeCodec
import com.datastax.oss.driver.api.core.cql.BoundStatement
import com.datastax.oss.driver.api.core.cql.PreparedStatement
import com.datastax.oss.driver.api.core.cql.Row
import net.nmoncho.helenus.api.`type`.codec.Codec
import net.nmoncho.helenus.internal.Labelling

/** Defines the contract of how Helenus can map a type [[T]] into and from the database
  *
  * @tparam A type to get from and to a row
  */
trait Mapping[A] extends RowMapper[A]:

    /** Creates a function that will take a [[T]] and will produce a [[BoundStatement]]
      *
      * @param pstmt [[PreparedStatement]] that produces the [[BoundStatement]]
      * @return binder function
      */
    def apply(pstmt: PreparedStatement): A => BoundStatement

    /** Creates a function that will take a [[T]] and will produce a [[ScalaBoundStatement]]
      *
      * @param pstmt [[ScalaPreparedStatement]] that produces the [[ScalaBoundStatement]]
      * @return binder function
      */
    def apply[Out](pstmt: ScalaPreparedStatement[A, Out]): A => ScalaBoundStatement[Out]

    /** Creates a new [[Mapping]] instance which also handles Computed Columns
      *
      * A Computed Column is one that gets inserted into a row, but it's not retrieved with a [[RowMapper]]
      *
      * @param column column name. Must have the same name as in the database.
      * @param compute how to compute the column value
      * @param codec how to encode the column
      * @tparam Col column type
      * @return new [[Mapping]] instance
      */
    def withComputedColumn[Col](column: String, compute: A => Col)(
        using codec: Codec[Col]
    ): Mapping[A]

end Mapping

object Mapping:

    type Binder[T, Out] = (ScalaBoundStatement[Out], T) => ScalaBoundStatement[Out]

    /** A [[BindParameterCollector]] allows to bind a parameter [[T]] to a [[PreparedStatement]]
      *
      * @tparam T type to bind to the [[PreparedStatement]]
      */
    sealed trait BindParameterCollector[T]:

        /** Produces a [[Binder]] function that will bind the parameter if it's defined in
          * the [[PreparedStatement]] or just return the same [[BoundStatement]]
          *
          * @param pstmt to bind
          * @return binder function
          */
        def apply[Out](pstmt: ScalaPreparedStatement[T, Out]): Binder[T, Out]

        /** Checks if the [[PreparedStatement]] uses the [[column]] defined by this [[BindParameterCollector]], and
          * accumulates the results in an accumulator
          *
          * @param pstmt statement to inspect
          * @param accumulated accumulated used columns so far
          * @return accumulated columns, plus this [[column]] if the [[PreparedStatement]] uses it
          */
        def usedParameters(pstmt: PreparedStatement, accumulated: Set[String]): Set[String]

        protected def column: String

        def columns: Set[String]

        /** Checks if the [[PreparedStatement]] uses the [[column]] defined by this [[BindParameterCollector]]
          *
          * Unlike [[usedParameters]], this doesn't accumulate used columns, it just checks this one.
          *
          * @param pstmt statement to inspect
          * @return
          */
        def contains(pstmt: PreparedStatement): Boolean = pstmt.getVariableDefinitions.contains(column)
    end BindParameterCollector

    sealed trait FieldCollector[T] extends BindParameterCollector[T]:
        def apply(row: Row): T

    import scala.compiletime.*

    inline def summonInstances[Elems <: Tuple](fields: Seq[String]): FieldCollector[Elems] =
        inline erasedValue[Elems] match
            case _: (elem *: EmptyTuple) =>
                deriveLast[elem](fields).asInstanceOf[FieldCollector[Elems]]

            case _: (elem *: elems) =>
                deriveHList[elem, elems](fields).asInstanceOf[FieldCollector[Elems]]
        end match
    end summonInstances

    inline def deriveLast[H](fields: Seq[String]): FieldCollector[H *: EmptyTuple] =
        val codec = summonInline[Codec[H]]

        new FieldCollector[H *: EmptyTuple]:
            override protected val column: String = fields.head
            override val columns: Set[String]     = Set(column)

            override def usedParameters(pstmt: PreparedStatement, accumulated: Set[String]): Set[String] =
                if contains(pstmt) then
                    verifyColumnType(pstmt, column, codec)

                    accumulated + column
                else
                    accumulated

            override def apply(row: Row): H *: EmptyTuple =
                row.get(column, codec) *: EmptyTuple

            override def apply[Out](pstmt: ScalaPreparedStatement[H *: EmptyTuple, Out]): Binder[H *: EmptyTuple, Out] =
                if contains(pstmt) && pstmt.options.ignoreNullFields then
                    (bstmt, t) => setIfDefined(bstmt, column, t.head, codec)
                else if contains(pstmt) then
                    (bstmt, t) =>
                        bstmt.set(column, t.head.asInstanceOf[H], codec).asInstanceOf[ScalaBoundStatement[Out]]
                else
                    (bstmt, _) =>
                        log.debug("Ignoring missing column [{}] from query [{}]", column, pstmt.getQuery: Any)
                        bstmt
        end new
    end deriveLast

    inline def deriveHList[H, T <: Tuple](fields: Seq[String]): FieldCollector[H *: T] =
        val codec         = summonInline[Codec[H]]
        val tailCollector = summonInstances[T](fields.tail)

        new FieldCollector[H *: T]:
            override protected val column: String = fields.head
            override val columns: Set[String]     = tailCollector.columns + column

            override def usedParameters(pstmt: PreparedStatement, accumulated: Set[String]): Set[String] =
                if contains(pstmt) then
                    verifyColumnType(pstmt, column, codec)

                    tailCollector.usedParameters(pstmt, accumulated + column)
                else
                    tailCollector.usedParameters(pstmt, accumulated)

            override def apply(row: Row): H *: T =
                row.get(column, codec) *: tailCollector(row)

            override def apply[Out](pstmt: ScalaPreparedStatement[H *: T, Out]): Binder[H *: T, Out] =
                val tail = tailCollector(pstmt.asInstanceOf[ScalaPreparedStatement[T, Out]])

                if contains(pstmt) && pstmt.options.ignoreNullFields then
                    (bstmt, t) => tail(setIfDefined(bstmt, column, t.head, codec), t.tail)
                else if contains(pstmt) then
                    (bstmt, t) =>
                        tail(
                          bstmt.set(column, t.head.asInstanceOf[H], codec).asInstanceOf[ScalaBoundStatement[Out]],
                          t.tail
                        )
                else
                    (bstmt, _) =>
                        log.debug("Ignoring missing column [{}] from query [{}]", column, pstmt.getQuery: Any)
                        bstmt
                end if
            end apply
        end new
    end deriveHList

    inline def derived[A <: Product](
        using mirror: Mirror.ProductOf[A],
        labelling: Labelling[A],
        tag: ClassTag[A],
        namingScheme: ColumnNamingScheme = DefaultColumnNamingScheme
    ): Mapping[A] =
        val elementsCollector = summonInstances[mirror.MirroredElemTypes](labelling.elemLabels.map(namingScheme.map))

        val collector = new FieldCollector[A]:
            override protected val column: String = "<not-used>"
            override val columns: Set[String]     = elementsCollector.columns

            override def usedParameters(pstmt: PreparedStatement, accumulated: Set[String]): Set[String] =
                elementsCollector.usedParameters(pstmt, accumulated)

            override def apply(row: Row): A =
                mirror.fromTuple(elementsCollector(row))

            override def apply[Out](pstmt: ScalaPreparedStatement[A, Out]): Binder[A, Out] =
                val binder =
                    elementsCollector(pstmt.asInstanceOf[ScalaPreparedStatement[mirror.MirroredElemTypes, Out]])

                (bstmt, a) => binder(bstmt, Tuple.fromProductTyped(a))
            end apply

        new DefaultCaseClassDerivedMapping[A](collector, Map.empty)
    end derived

    /** Sets the value to the [[BoundStatement]] only if not-null to avoid tombstones
      */
    private inline def setIfDefined[T, Out](
        bstmt: ScalaBoundStatement[Out],
        column: String,
        value: T,
        codec: TypeCodec[T]
    ): ScalaBoundStatement[Out] =
        if value == null || value == None then bstmt
        else bstmt.set(column, value, codec).asInstanceOf[ScalaBoundStatement[Out]]

    /** Verifies if a [[TypeCodec]] can <em>accept</em> (or handle) a column defined in a [[PreparedStatement]]
      *
      * Logs a warning if it can't
      */
    private def verifyColumnType(
        pstmt: PreparedStatement,
        column: String,
        codec: TypeCodec[?]
    ): Unit =
        val columnDefinition = pstmt.getVariableDefinitions.get(column)
        if !codec.accepts(columnDefinition.getType) then
            log.warn(
              "Invalid PreparedStatement expected parameter with type {} for name {} but got type {}",
              columnDefinition.getType.toString,
              column,
              codec.getCqlType.toString
            )
        end if
    end verifyColumnType

    /** Creates [[BindParameterCollector]] for a computed column.
      *
      * Computed columns will not be considered when mapping a case class with a [[RowMapper]]. If that's desired
      * then the column should be promoted to a case class field.
      *
      * @param columnName name of the computed column
      * @param compute how to compute a value
      * @param codec how to encode the computed column value
      * @tparam T where does the value come from
      * @tparam Col computed column type
      * @return a <em>flat</em> [[BindParameterCollector]] for a computed column
      */
    private def computedColumnCollector[T, Col](
        columnName: String,
        compute: T => Col,
        codec: TypeCodec[Col]
    ): BindParameterCollector[T] = new BindParameterCollector[T]:
        override protected val column: String = columnName

        override val columns: Set[String] = Set(column)

        override def usedParameters(pstmt: PreparedStatement, accumulated: Set[String]): Set[String] =
            if contains(pstmt) then
                verifyColumnType(pstmt, column, codec)

                accumulated + column
            else
                accumulated

        override def apply[Out](pstmt: ScalaPreparedStatement[T, Out]): Binder[T, Out] =
            if contains(pstmt) && pstmt.options.ignoreNullFields then
                (bstmt, t) =>
                    setIfDefined(bstmt, column, compute(t), codec)
            else if contains(pstmt) then
                (bstmt, t) =>
                    bstmt.set(column, compute(t), codec).asInstanceOf[ScalaBoundStatement[Out]]
            else
                (bstmt, _) =>
                    log.debug("Ignoring missing column [{}] from query [{}]", column, pstmt.getQuery: Any)
                    bstmt

    class DefaultCaseClassDerivedMapping[A](
        collector: FieldCollector[A],
        computedColumns: Map[String, BindParameterCollector[A]]
    ) extends Mapping[A]:

        override def apply(row: Row): A =
            collector(row)

        override def apply(pstmt: PreparedStatement): A => BoundStatement =
            apply(pstmt.asInstanceOf[ScalaPreparedStatement[Unit, Row]])

        override def apply[Out](pstmt: ScalaPreparedStatement[A, Out]): A => ScalaBoundStatement[Out] =
            val collected = collector(pstmt)
            val requiredComputed = computedColumns.collect {
                case (_, computed) if computed.contains(pstmt) => computed(pstmt)
            }

            (a: A) =>
                requiredComputed.foldLeft(
                  collected(pstmt.bind().asInstanceOf[ScalaBoundStatement[Out]], a)
                )((bstmt, compute) => compute(bstmt, a))

        end apply

        override def withComputedColumn[Col](column: String, compute: A => Col)(
            using codec: Codec[Col]
        ): Mapping[A] =
            if computedColumns.contains(column) then
                log.warn("Column [{}] is already defined for Adapter and will be overridden", column)

            new DefaultCaseClassDerivedMapping[A](
              collector,
              computedColumns + (column -> computedColumnCollector(column, compute, codec))
            )
        end withComputedColumn

    end DefaultCaseClassDerivedMapping

end Mapping
