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

package net.nmoncho.helenus.api.cql

import java.nio.ByteBuffer
import java.util

import com.datastax.oss.driver.api.core.CqlSession
import com.datastax.oss.driver.api.core.`type`.codec.TypeCodec
import com.datastax.oss.driver.api.core.cql.BoundStatement
import com.datastax.oss.driver.api.core.cql.BoundStatementBuilder
import com.datastax.oss.driver.api.core.cql.ColumnDefinitions
import com.datastax.oss.driver.api.core.cql.PreparedStatement
import com.datastax.oss.driver.api.core.cql.Row
import net.nmoncho.helenus.api.RowMapper
import net.nmoncho.helenus.api.`type`.codec.Codec
import net.nmoncho.helenus.internal.cql.*
import org.slf4j.LoggerFactory

abstract class ScalaPreparedStatement[In, Out](pstmt: PreparedStatement, mapper: RowMapper[Out])
    extends PreparedStatement with Options[In, Out]:

    type AsOut[T] <: ScalaPreparedStatement[?, T]

    protected given RowMapper[Out] = mapper

    // Since this is no longer exposed to users, we can use the tupled `apply` function
    def tupled: In => BoundStatement

    /** Maps the result from this [[PreparedStatement]] with a different [[Out2]]
      * as long as there is an implicit [[RowMapper]] and [[Out]] is [[Row]] (this is
      * meant to avoid calling `as` twice)
      */
    def as[Out2](implicit ev: Out =:= Row, mapper: RowMapper[Out2]): AsOut[Out2]

    /** Maps the result from this [[PreparedStatement]] with a different [[Out2]]
      * with an explicit [[RowMapper]] as long as [[Out]] is [[Row]] (this is
      * meant to avoid calling `as` twice)
      */
    def as[Out2](mapper: RowMapper[Out2])(implicit ev: Out =:= Row): AsOut[Out2] =
        as[Out2](ev, mapper)

    /** Verifies that this [[ScalaPreparedStatement]] has the same amount of bind parameters (e.g. '?') as the amount
      * used on the '.prepare' call. It will also verify that these parameters have the same type as the specified in
      * the '.prepare' call.
      *
      * If this check fails, a warning will be logged.
      *
      * For example:
      *   {{{"SELECT * FROM hotels WHERE id = ?".prepare[Int, String] }}}
      *
      * Will issue two warnings, one for the amount of parameters, and another for the parameter type ('id' has type TEXT)
      *
      * @param codecs codecs used in this [[ScalaPreparedStatement]]
      */
    protected def verifyArity(codecs: TypeCodec[?]*): Unit =
        import ScalaPreparedStatement.*

        import scala.jdk.CollectionConverters.* // Don't remove me

        val expectedArity = codecs.size
        val actualParams  = getVariableDefinitions
        val actualArity   = actualParams.size()

        if expectedArity != actualArity then
            log.error(
              "Invalid PreparedStatement [{}] expects {} bind parameters but defined {}. Double check its definition when calling the 'prepare' method",
              getQuery.toString,
              actualArity.toString,
              expectedArity.toString
            )
        end if

        actualParams.iterator().asScala.zip(codecs.iterator).zipWithIndex.foreach { case ((param, codec), idx) =>
            if !codec.accepts(param.getType) then
                log.warn(
                  "Invalid PreparedStatement expected parameter with type {} at index {} but got type {}",
                  param.getType.toString,
                  idx.toString,
                  codec.getCqlType.toString
                )
        }
    end verifyArity

    // ----------------------------------------------------------------------------
    //  Wrapped `PreparedStatement` methods
    // ----------------------------------------------------------------------------

    override def getId: ByteBuffer = pstmt.getId

    override def getQuery: String = pstmt.getQuery

    override def getVariableDefinitions: ColumnDefinitions = pstmt.getVariableDefinitions

    override def getPartitionKeyIndices: util.List[Integer] = pstmt.getPartitionKeyIndices

    override def getResultMetadataId: ByteBuffer = pstmt.getResultMetadataId

    override def getResultSetDefinitions: ColumnDefinitions = pstmt.getResultSetDefinitions

    override def setResultMetadata(id: ByteBuffer, definitions: ColumnDefinitions): Unit =
        pstmt.setResultMetadata(id, definitions)

    override def bind(values: AnyRef*): BoundStatement =
        pstmt.bind(values*)

    override def boundStatementBuilder(values: AnyRef*): BoundStatementBuilder =
        pstmt.boundStatementBuilder(values*)

end ScalaPreparedStatement

object ScalaPreparedStatement:

    private val log = LoggerFactory.getLogger(classOf[ScalaPreparedStatement[?, ?]])

    case class CQLQuery(query: String, session: CqlSession)

    // trait SyncCQLQuery:
    //     def query: String
    //     def session: CqlSession

    //     def prepareUnit: ScalaPreparedStatement[Unit, Row] = ???

    //     def prepare[T1]: ScalaPreparedStatement[T1, Row] = ???

    //     def prepare[T1, T2]: ScalaPreparedStatement1[(T1, T2), Row] = ???
        
    //     def prepare[T1, T2, T3]: ScalaPreparedStatement2[(T1, T2, T3), Row] = ???
    
    // abstract class ScalaPreparedStatement1[A, B]
    // abstract class ScalaPreparedStatement2[A, B]

end ScalaPreparedStatement
