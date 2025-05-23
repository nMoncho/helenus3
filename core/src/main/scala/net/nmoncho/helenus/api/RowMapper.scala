/*
 * Copyright 2021 the original author or authors
 *
 * SPDX-License-Identifier: MIT
 */

package net.nmoncho.helenus.api

import com.datastax.oss.driver.api.core.cql.Row
import net.nmoncho.helenus.api.`type`.codec.Codec
import net.nmoncho.helenus.internal.DerivedCaseClassRowMapper
import net.nmoncho.helenus.internal.DerivedRowMapper
import net.nmoncho.helenus.internal.macros.RenamedDerivedRowMapper
import org.slf4j.LoggerFactory

trait RowMapper[T] extends Serializable:
    def apply(row: Row): T

object RowMapper extends DerivedCaseClassRowMapper:

    type ColumnName = String

    val identity: RowMapper[Row] = (row: Row) => row

    given RowMapper[Row] = identity

    given [T](using derived: DerivedRowMapper[T]): RowMapper[T] = derived

    def apply[T](using mapper: DerivedRowMapper[T]): RowMapper[T] = mapper

    /** Derives a [[RowMapper]] considering the specified name mapping.
      *
      * @param first first mapping from field to column
      * @param rest  rest mapping from field to column
      * @tparam T target type
      */
    inline def deriveRenamed[A <: Product](
        inline first: A => (Any, String),
        inline rest: A => (Any, String)*
    ): RowMapper[A] =
        ${ RenamedDerivedRowMapper.renamedImpl[A]('first, 'rest) }

    /** Knows how to extract a column from a [[Row]] into a Scala type [[A]]
      * @tparam A target type
      */
    trait ColumnMapper[A] extends Serializable:
        def apply(columnName: ColumnName, row: Row): A

    object ColumnMapper:
        private val log = LoggerFactory.getLogger(classOf[ColumnMapper[?]])

        given [T](using codec: Codec[T]): ColumnMapper[T] = default[T]

        def default[A](using codec: Codec[A]): ColumnMapper[A] = new ColumnMapper[A]:
            override def apply(columnName: ColumnName, row: Row): A =
                row.get(columnName, codec)

        /** Creates a [[ColumnMapper]] that maps an [[Either]] to different columns
          *
          * @param leftColumnName column name where [[Left]] is stored
          * @param rightColumnName column name where [[Right]] is stored
          * @param leftCodec codec for [[Left]] value
          * @param rightCodec codec for [[Right]] value
          * @tparam A [[Left]] type
          * @tparam B [[Right]] type
          * @return [[ColumnMapper]] for an [[Either]]
          */
        def either[A, B](leftColumnName: String, rightColumnName: String)(
            using leftCodec: Codec[A],
            rightCodec: Codec[B]
        ): ColumnMapper[Either[A, B]] = new ColumnMapper[Either[A, B]]:
            def apply(ignored: ColumnName, row: Row): Either[A, B] =
                if row.isNull(leftColumnName) && !row.isNull(rightColumnName) then
                    Right(row.get[B](rightColumnName, rightCodec))
                else if !row.isNull(leftColumnName) && row.isNull(rightColumnName) then
                    Left(row.get[A](leftColumnName, leftCodec))
                else
                    log.warn(
                      "Both columns [{}] and [{}] where not null, defaulting to Right",
                      leftColumnName,
                      rightColumnName: Any
                    )
                    Right(row.get[B](rightColumnName, rightCodec))
            end apply

    end ColumnMapper

end RowMapper
