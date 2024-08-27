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

import com.datastax.oss.driver.api.core.`type`.codec.TypeCodec
import com.datastax.oss.driver.api.core.cql.BoundStatement
import com.datastax.oss.driver.api.core.cql.PreparedStatement

/** Defines the contract of how Helenus can map a type [[T]] into and from the database
  *
  * @tparam T type to get from and to a row
  */
trait Mapping[T <: Tuple] extends RowMapper[T]:

    /** Creates a function that will take a [[T]] and will produce a [[BoundStatement]]
      *
      * @param pstmt [[PreparedStatement]] that produces the [[BoundStatement]]
      * @return binder function
      */
    def apply(pstmt: PreparedStatement): T => BoundStatement

    /** Creates a function that will take a [[T]] and will produce a [[ScalaBoundStatement]]
      *
      * @param pstmt [[ScalaPreparedStatement]] that produces the [[ScalaBoundStatement]]
      * @return binder function
      */
    def apply[Out](pstmt: ScalaPreparedStatement[T, Out]): T => ScalaBoundStatement[Out]

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
    def withComputedColumn[Col](column: String, compute: T => Col)(
        using codec: TypeCodec[Col]
    ): Mapping[T]

end Mapping
