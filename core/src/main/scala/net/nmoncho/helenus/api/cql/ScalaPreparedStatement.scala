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

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

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

    case class CQLQuery(query: String, session: CqlSession) extends SyncCQLQuery with AsyncCQLQuery

    trait SyncCQLQuery:
        def query: String
        def session: CqlSession

        // format: off

        /** Prepares a query without parameters
          *
          * @return BoundStatement that can be called like a function
          */
        def prepareUnit: ScalaPreparedStatementUnit[Row] =
            new ScalaPreparedStatementUnit[Row](session.prepare(query), RowMapper.identity, StatementOptions.default)

        /** Prepares a query that will take 1 query parameter, which can be invoked like:
          * {{{
          *   import net.nmoncho.helenus._
          *
          *   val pstmt = "SELECT * FROM users WHERE id = ?".toCQL.prepare[String]
          *   val bstmt = pstmt("bob")
          * }}}
          *
          * @return BoundStatement that can be called like a function
          */
        def prepare[T1](implicit t1: Codec[T1]): ScalaPreparedStatement1[T1, Row] =
            new ScalaPreparedStatement1[T1, Row](session.prepare(query), RowMapper.identity, StatementOptions.default, t1)

        /** Prepares a query that will take 2 query parameter, which can be invoked like:
        * {{{
        *   import net.nmoncho.helenus._
        *
        *   val pstmt = "SELECT * FROM users WHERE id = ? and age = ?".toCQL.prepare[String]
        *   val bstmt = pstmt("bob", 42)
        * }}}
        *
        * @return BoundStatement that can be called like a function
        */
        def prepare[T1, T2](implicit t1: Codec[T1], t2: Codec[T2]): ScalaPreparedStatement2[T1, T2, Row] =
            new ScalaPreparedStatement2[T1, T2, Row](session.prepare(query), RowMapper.identity, StatementOptions.default, t1, t2)

        /** Prepares a query that will take 3 query parameter
         *
         * @return BoundStatement that can be called like a function
         */
        def prepare[T1, T2, T3](implicit t1: Codec[T1], t2: Codec[T2], t3: Codec[T3]): ScalaPreparedStatement3[T1, T2, T3, Row] =
            new ScalaPreparedStatement3[T1, T2, T3, Row](session.prepare(query), RowMapper.identity, StatementOptions.default, t1, t2, t3)

        /** Prepares a query that will take 4 query parameter
         *
         * @return BoundStatement that can be called like a function
         */
        def prepare[T1, T2, T3, T4](implicit t1: Codec[T1], t2: Codec[T2], t3: Codec[T3], t4: Codec[T4]): ScalaPreparedStatement4[T1, T2, T3, T4, Row] =
            new ScalaPreparedStatement4[T1, T2, T3, T4, Row](session.prepare(query), RowMapper.identity, StatementOptions.default, t1, t2, t3, t4)

        /** Prepares a query that will take 5 query parameter
         *
         * @return BoundStatement that can be called like a function
         */
        def prepare[T1, T2, T3, T4, T5](implicit t1: Codec[T1], t2: Codec[T2], t3: Codec[T3], t4: Codec[T4], t5: Codec[T5]): ScalaPreparedStatement5[T1, T2, T3, T4, T5, Row] =
            new ScalaPreparedStatement5[T1, T2, T3, T4, T5, Row](session.prepare(query), RowMapper.identity, StatementOptions.default, t1, t2, t3, t4, t5)

        /** Prepares a query that will take 6 query parameter
         *
         * @return BoundStatement that can be called like a function
         */
        def prepare[T1, T2, T3, T4, T5, T6](implicit t1: Codec[T1], t2: Codec[T2], t3: Codec[T3], t4: Codec[T4], t5: Codec[T5], t6: Codec[T6]): ScalaPreparedStatement6[T1, T2, T3, T4, T5, T6, Row] =
            new ScalaPreparedStatement6[T1, T2, T3, T4, T5, T6, Row](session.prepare(query), RowMapper.identity, StatementOptions.default, t1, t2, t3, t4, t5, t6)

        /** Prepares a query that will take 7 query parameter
         *
         * @return BoundStatement that can be called like a function
         */
        def prepare[T1, T2, T3, T4, T5, T6, T7](implicit t1: Codec[T1], t2: Codec[T2], t3: Codec[T3], t4: Codec[T4], t5: Codec[T5], t6: Codec[T6], t7: Codec[T7]): ScalaPreparedStatement7[T1, T2, T3, T4, T5, T6, T7, Row] =
            new ScalaPreparedStatement7[T1, T2, T3, T4, T5, T6, T7, Row](session.prepare(query), RowMapper.identity, StatementOptions.default, t1, t2, t3, t4, t5, t6, t7)

        /** Prepares a query that will take 8 query parameter
         *
         * @return BoundStatement that can be called like a function
         */
        def prepare[T1, T2, T3, T4, T5, T6, T7, T8](implicit t1: Codec[T1], t2: Codec[T2], t3: Codec[T3], t4: Codec[T4], t5: Codec[T5], t6: Codec[T6], t7: Codec[T7], t8: Codec[T8]): ScalaPreparedStatement8[T1, T2, T3, T4, T5, T6, T7, T8, Row] =
            new ScalaPreparedStatement8[T1, T2, T3, T4, T5, T6, T7, T8, Row](session.prepare(query), RowMapper.identity, StatementOptions.default, t1, t2, t3, t4, t5, t6, t7, t8)

        /** Prepares a query that will take 9 query parameter
         *
         * @return BoundStatement that can be called like a function
         */
        def prepare[T1, T2, T3, T4, T5, T6, T7, T8, T9](implicit t1: Codec[T1], t2: Codec[T2], t3: Codec[T3], t4: Codec[T4], t5: Codec[T5], t6: Codec[T6], t7: Codec[T7], t8: Codec[T8], t9: Codec[T9]): ScalaPreparedStatement9[T1, T2, T3, T4, T5, T6, T7, T8, T9, Row] =
            new ScalaPreparedStatement9[T1, T2, T3, T4, T5, T6, T7, T8, T9, Row](session.prepare(query), RowMapper.identity, StatementOptions.default, t1, t2, t3, t4, t5, t6, t7, t8, t9)

        /** Prepares a query that will take 10 query parameter
         *
         * @return BoundStatement that can be called like a function
         */
        def prepare[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10](implicit t1: Codec[T1], t2: Codec[T2], t3: Codec[T3], t4: Codec[T4], t5: Codec[T5], t6: Codec[T6], t7: Codec[T7], t8: Codec[T8], t9: Codec[T9], t10: Codec[T10]): ScalaPreparedStatement10[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, Row] =
            new ScalaPreparedStatement10[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, Row](session.prepare(query), RowMapper.identity, StatementOptions.default, t1, t2, t3, t4, t5, t6, t7, t8, t9, t10)

        /** Prepares a query that will take 11 query parameter
         *
         * @return BoundStatement that can be called like a function
         */
        def prepare[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11](implicit t1: Codec[T1], t2: Codec[T2], t3: Codec[T3], t4: Codec[T4], t5: Codec[T5], t6: Codec[T6], t7: Codec[T7], t8: Codec[T8], t9: Codec[T9], t10: Codec[T10], t11: Codec[T11]): ScalaPreparedStatement11[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, Row] =
            new ScalaPreparedStatement11[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, Row](session.prepare(query), RowMapper.identity, StatementOptions.default, t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11)

        /** Prepares a query that will take 12 query parameter
         *
         * @return BoundStatement that can be called like a function
         */
        def prepare[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12](implicit t1: Codec[T1], t2: Codec[T2], t3: Codec[T3], t4: Codec[T4], t5: Codec[T5], t6: Codec[T6], t7: Codec[T7], t8: Codec[T8], t9: Codec[T9], t10: Codec[T10], t11: Codec[T11], t12: Codec[T12]): ScalaPreparedStatement12[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, Row] =
            new ScalaPreparedStatement12[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, Row](session.prepare(query), RowMapper.identity, StatementOptions.default, t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12)

        /** Prepares a query that will take 13 query parameter
         *
         * @return BoundStatement that can be called like a function
         */
        def prepare[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13](implicit t1: Codec[T1], t2: Codec[T2], t3: Codec[T3], t4: Codec[T4], t5: Codec[T5], t6: Codec[T6], t7: Codec[T7], t8: Codec[T8], t9: Codec[T9], t10: Codec[T10], t11: Codec[T11], t12: Codec[T12], t13: Codec[T13]): ScalaPreparedStatement13[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, Row] =
            new ScalaPreparedStatement13[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, Row](session.prepare(query), RowMapper.identity, StatementOptions.default, t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13)

        /** Prepares a query that will take 14 query parameter
         *
         * @return BoundStatement that can be called like a function
         */
        def prepare[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14](implicit t1: Codec[T1], t2: Codec[T2], t3: Codec[T3], t4: Codec[T4], t5: Codec[T5], t6: Codec[T6], t7: Codec[T7], t8: Codec[T8], t9: Codec[T9], t10: Codec[T10], t11: Codec[T11], t12: Codec[T12], t13: Codec[T13], t14: Codec[T14]): ScalaPreparedStatement14[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, Row] =
            new ScalaPreparedStatement14[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, Row](session.prepare(query), RowMapper.identity, StatementOptions.default, t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14)

        /** Prepares a query that will take 15 query parameter
         *
         * @return BoundStatement that can be called like a function
         */
        def prepare[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15](implicit t1: Codec[T1], t2: Codec[T2], t3: Codec[T3], t4: Codec[T4], t5: Codec[T5], t6: Codec[T6], t7: Codec[T7], t8: Codec[T8], t9: Codec[T9], t10: Codec[T10], t11: Codec[T11], t12: Codec[T12], t13: Codec[T13], t14: Codec[T14], t15: Codec[T15]): ScalaPreparedStatement15[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, Row] =
            new ScalaPreparedStatement15[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, Row](session.prepare(query), RowMapper.identity, StatementOptions.default, t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14, t15)

        /** Prepares a query that will take 16 query parameter
         *
         * @return BoundStatement that can be called like a function
         */
        def prepare[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16](implicit t1: Codec[T1], t2: Codec[T2], t3: Codec[T3], t4: Codec[T4], t5: Codec[T5], t6: Codec[T6], t7: Codec[T7], t8: Codec[T8], t9: Codec[T9], t10: Codec[T10], t11: Codec[T11], t12: Codec[T12], t13: Codec[T13], t14: Codec[T14], t15: Codec[T15], t16: Codec[T16]): ScalaPreparedStatement16[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, Row] =
            new ScalaPreparedStatement16[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, Row](session.prepare(query), RowMapper.identity, StatementOptions.default, t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14, t15, t16)

        /** Prepares a query that will take 17 query parameter
         *
         * @return BoundStatement that can be called like a function
         */
        def prepare[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17](implicit t1: Codec[T1], t2: Codec[T2], t3: Codec[T3], t4: Codec[T4], t5: Codec[T5], t6: Codec[T6], t7: Codec[T7], t8: Codec[T8], t9: Codec[T9], t10: Codec[T10], t11: Codec[T11], t12: Codec[T12], t13: Codec[T13], t14: Codec[T14], t15: Codec[T15], t16: Codec[T16], t17: Codec[T17]): ScalaPreparedStatement17[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, Row] =
            new ScalaPreparedStatement17[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, Row](session.prepare(query), RowMapper.identity, StatementOptions.default, t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14, t15, t16, t17)

        /** Prepares a query that will take 18 query parameter
         *
         * @return BoundStatement that can be called like a function
         */
        def prepare[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18](implicit t1: Codec[T1], t2: Codec[T2], t3: Codec[T3], t4: Codec[T4], t5: Codec[T5], t6: Codec[T6], t7: Codec[T7], t8: Codec[T8], t9: Codec[T9], t10: Codec[T10], t11: Codec[T11], t12: Codec[T12], t13: Codec[T13], t14: Codec[T14], t15: Codec[T15], t16: Codec[T16], t17: Codec[T17], t18: Codec[T18]): ScalaPreparedStatement18[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, Row] =
            new ScalaPreparedStatement18[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, Row](session.prepare(query), RowMapper.identity, StatementOptions.default, t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14, t15, t16, t17, t18)

        /** Prepares a query that will take 19 query parameter
         *
         * @return BoundStatement that can be called like a function
         */
        def prepare[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19](implicit t1: Codec[T1], t2: Codec[T2], t3: Codec[T3], t4: Codec[T4], t5: Codec[T5], t6: Codec[T6], t7: Codec[T7], t8: Codec[T8], t9: Codec[T9], t10: Codec[T10], t11: Codec[T11], t12: Codec[T12], t13: Codec[T13], t14: Codec[T14], t15: Codec[T15], t16: Codec[T16], t17: Codec[T17], t18: Codec[T18], t19: Codec[T19]): ScalaPreparedStatement19[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, Row] =
            new ScalaPreparedStatement19[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, Row](session.prepare(query), RowMapper.identity, StatementOptions.default, t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14, t15, t16, t17, t18, t19)

        /** Prepares a query that will take 20 query parameter
         *
         * @return BoundStatement that can be called like a function
         */
        def prepare[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, T20](implicit t1: Codec[T1], t2: Codec[T2], t3: Codec[T3], t4: Codec[T4], t5: Codec[T5], t6: Codec[T6], t7: Codec[T7], t8: Codec[T8], t9: Codec[T9], t10: Codec[T10], t11: Codec[T11], t12: Codec[T12], t13: Codec[T13], t14: Codec[T14], t15: Codec[T15], t16: Codec[T16], t17: Codec[T17], t18: Codec[T18], t19: Codec[T19], t20: Codec[T20]): ScalaPreparedStatement20[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, T20, Row] =
            new ScalaPreparedStatement20[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, T20, Row](session.prepare(query), RowMapper.identity, StatementOptions.default, t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14, t15, t16, t17, t18, t19, t20)

        /** Prepares a query that will take 21 query parameter
         *
         * @return BoundStatement that can be called like a function
         */
        def prepare[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, T20, T21](implicit t1: Codec[T1], t2: Codec[T2], t3: Codec[T3], t4: Codec[T4], t5: Codec[T5], t6: Codec[T6], t7: Codec[T7], t8: Codec[T8], t9: Codec[T9], t10: Codec[T10], t11: Codec[T11], t12: Codec[T12], t13: Codec[T13], t14: Codec[T14], t15: Codec[T15], t16: Codec[T16], t17: Codec[T17], t18: Codec[T18], t19: Codec[T19], t20: Codec[T20], t21: Codec[T21]): ScalaPreparedStatement21[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, T20, T21, Row] =
            new ScalaPreparedStatement21[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, T20, T21, Row](session.prepare(query), RowMapper.identity, StatementOptions.default, t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14, t15, t16, t17, t18, t19, t20, t21)

        /** Prepares a query that will take 22 query parameter
         *
         * @return BoundStatement that can be called like a function
         */
        def prepare[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, T20, T21, T22](implicit t1: Codec[T1], t2: Codec[T2], t3: Codec[T3], t4: Codec[T4], t5: Codec[T5], t6: Codec[T6], t7: Codec[T7], t8: Codec[T8], t9: Codec[T9], t10: Codec[T10], t11: Codec[T11], t12: Codec[T12], t13: Codec[T13], t14: Codec[T14], t15: Codec[T15], t16: Codec[T16], t17: Codec[T17], t18: Codec[T18], t19: Codec[T19], t20: Codec[T20], t21: Codec[T21], t22: Codec[T22]): ScalaPreparedStatement22[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, T20, T21, T22, Row] =
            new ScalaPreparedStatement22[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, T20, T21, T22, Row](session.prepare(query), RowMapper.identity, StatementOptions.default, t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14, t15, t16, t17, t18, t19, t20, t21, t22)

        // format: on
    end SyncCQLQuery

    trait AsyncCQLQuery:
        def query: String
        def session: CqlSession

        import scala.jdk.FutureConverters.CompletionStageOps

        // format: off

        /** Prepares asynchronously a query that takes no query parameter
          *
          * @return BoundStatement that can be called like a function
          */
        def prepareUnitAsync(implicit ec: ExecutionContext): Future[ScalaPreparedStatementUnit[Row]] =
            session.prepareAsync(query).asScala
                .map(pstmt => new ScalaPreparedStatementUnit[Row](pstmt, RowMapper.identity, StatementOptions.default))

        /** Prepares asynchronously a query that will take 1 query parameter
          *
          * @return BoundStatement that can be called like a function
          */
        def prepareAsync[T1](implicit ec: ExecutionContext, t1: Codec[T1]): Future[ScalaPreparedStatement1[T1, Row]] =
            session.prepareAsync(query).asScala
                .map(pstmt => new ScalaPreparedStatement1[T1, Row](pstmt, RowMapper.identity, StatementOptions.default, t1))

        /** Prepares asynchronously a query that will take 2 query parameter
          *
          * @return BoundStatement that can be called like a function
          */
        def prepareAsync[T1, T2](implicit ec: ExecutionContext, t1: Codec[T1], t2: Codec[T2]): Future[ScalaPreparedStatement2[T1, T2, Row]] =
            session.prepareAsync(query).asScala
                .map(pstmt => new ScalaPreparedStatement2[T1, T2, Row](pstmt, RowMapper.identity, StatementOptions.default, t1, t2))

        /** Prepares asynchronously a query that will take 3 query parameter
          *
          * @return BoundStatement that can be called like a function
          */
        def prepareAsync[T1, T2, T3](implicit ec: ExecutionContext, t1: Codec[T1], t2: Codec[T2], t3: Codec[T3]): Future[ScalaPreparedStatement3[T1, T2, T3, Row]] =
            session.prepareAsync(query).asScala
                .map(pstmt => new ScalaPreparedStatement3[T1, T2, T3, Row](pstmt, RowMapper.identity, StatementOptions.default, t1, t2, t3))

        /** Prepares asynchronously a query that will take 4 query parameter
          *
          * @return BoundStatement that can be called like a function
          */
        def prepareAsync[T1, T2, T3, T4](implicit ec: ExecutionContext, t1: Codec[T1], t2: Codec[T2], t3: Codec[T3], t4: Codec[T4]): Future[ScalaPreparedStatement4[T1, T2, T3, T4, Row]] =
            session.prepareAsync(query).asScala
                .map(pstmt => new ScalaPreparedStatement4[T1, T2, T3, T4, Row](pstmt, RowMapper.identity, StatementOptions.default, t1, t2, t3, t4))

        /** Prepares asynchronously a query that will take 5 query parameter
          *
          * @return BoundStatement that can be called like a function
          */
        def prepareAsync[T1, T2, T3, T4, T5](implicit ec: ExecutionContext, t1: Codec[T1], t2: Codec[T2], t3: Codec[T3], t4: Codec[T4], t5: Codec[T5]): Future[ScalaPreparedStatement5[T1, T2, T3, T4, T5, Row]] =
            session.prepareAsync(query).asScala
                .map(pstmt => new ScalaPreparedStatement5[T1, T2, T3, T4, T5, Row](pstmt, RowMapper.identity, StatementOptions.default, t1, t2, t3, t4, t5))

        /** Prepares asynchronously a query that will take 6 query parameter
          *
          * @return BoundStatement that can be called like a function
          */
        def prepareAsync[T1, T2, T3, T4, T5, T6](implicit ec: ExecutionContext, t1: Codec[T1], t2: Codec[T2], t3: Codec[T3], t4: Codec[T4], t5: Codec[T5], t6: Codec[T6]): Future[ScalaPreparedStatement6[T1, T2, T3, T4, T5, T6, Row]] =
            session.prepareAsync(query).asScala
                .map(pstmt => new ScalaPreparedStatement6[T1, T2, T3, T4, T5, T6, Row](pstmt, RowMapper.identity, StatementOptions.default, t1, t2, t3, t4, t5, t6))

        /** Prepares asynchronously a query that will take 7 query parameter
          *
          * @return BoundStatement that can be called like a function
          */
        def prepareAsync[T1, T2, T3, T4, T5, T6, T7](implicit ec: ExecutionContext, t1: Codec[T1], t2: Codec[T2], t3: Codec[T3], t4: Codec[T4], t5: Codec[T5], t6: Codec[T6], t7: Codec[T7]): Future[ScalaPreparedStatement7[T1, T2, T3, T4, T5, T6, T7, Row]] =
            session.prepareAsync(query).asScala
                .map(pstmt => new ScalaPreparedStatement7[T1, T2, T3, T4, T5, T6, T7, Row](pstmt, RowMapper.identity, StatementOptions.default, t1, t2, t3, t4, t5, t6, t7))

        /** Prepares asynchronously a query that will take 8 query parameter
          *
          * @return BoundStatement that can be called like a function
          */
        def prepareAsync[T1, T2, T3, T4, T5, T6, T7, T8](implicit ec: ExecutionContext, t1: Codec[T1], t2: Codec[T2], t3: Codec[T3], t4: Codec[T4], t5: Codec[T5], t6: Codec[T6], t7: Codec[T7], t8: Codec[T8]): Future[ScalaPreparedStatement8[T1, T2, T3, T4, T5, T6, T7, T8, Row]] =
            session.prepareAsync(query).asScala
                .map(pstmt => new ScalaPreparedStatement8[T1, T2, T3, T4, T5, T6, T7, T8, Row](pstmt, RowMapper.identity, StatementOptions.default, t1, t2, t3, t4, t5, t6, t7, t8))

        /** Prepares asynchronously a query that will take 9 query parameter
          *
          * @return BoundStatement that can be called like a function
          */
        def prepareAsync[T1, T2, T3, T4, T5, T6, T7, T8, T9](implicit ec: ExecutionContext, t1: Codec[T1], t2: Codec[T2], t3: Codec[T3], t4: Codec[T4], t5: Codec[T5], t6: Codec[T6], t7: Codec[T7], t8: Codec[T8], t9: Codec[T9]): Future[ScalaPreparedStatement9[T1, T2, T3, T4, T5, T6, T7, T8, T9, Row]] =
            session.prepareAsync(query).asScala
                .map(pstmt => new ScalaPreparedStatement9[T1, T2, T3, T4, T5, T6, T7, T8, T9, Row](pstmt, RowMapper.identity, StatementOptions.default, t1, t2, t3, t4, t5, t6, t7, t8, t9))

        /** Prepares asynchronously a query that will take 10 query parameter
          *
          * @return BoundStatement that can be called like a function
          */
        def prepareAsync[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10](implicit ec: ExecutionContext, t1: Codec[T1], t2: Codec[T2], t3: Codec[T3], t4: Codec[T4], t5: Codec[T5], t6: Codec[T6], t7: Codec[T7], t8: Codec[T8], t9: Codec[T9], t10: Codec[T10]): Future[ScalaPreparedStatement10[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, Row]] =
            session.prepareAsync(query).asScala
                .map(pstmt => new ScalaPreparedStatement10[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, Row](pstmt, RowMapper.identity, StatementOptions.default, t1, t2, t3, t4, t5, t6, t7, t8, t9, t10))

        /** Prepares asynchronously a query that will take 11 query parameter
          *
          * @return BoundStatement that can be called like a function
          */
        def prepareAsync[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11](implicit ec: ExecutionContext, t1: Codec[T1], t2: Codec[T2], t3: Codec[T3], t4: Codec[T4], t5: Codec[T5], t6: Codec[T6], t7: Codec[T7], t8: Codec[T8], t9: Codec[T9], t10: Codec[T10], t11: Codec[T11]): Future[ScalaPreparedStatement11[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, Row]] =
            session.prepareAsync(query).asScala
                .map(pstmt => new ScalaPreparedStatement11[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, Row](pstmt, RowMapper.identity, StatementOptions.default, t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11))

        /** Prepares asynchronously a query that will take 12 query parameter
          *
          * @return BoundStatement that can be called like a function
          */
        def prepareAsync[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12](implicit ec: ExecutionContext, t1: Codec[T1], t2: Codec[T2], t3: Codec[T3], t4: Codec[T4], t5: Codec[T5], t6: Codec[T6], t7: Codec[T7], t8: Codec[T8], t9: Codec[T9], t10: Codec[T10], t11: Codec[T11], t12: Codec[T12]): Future[ScalaPreparedStatement12[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, Row]] =
            session.prepareAsync(query).asScala
                .map(pstmt => new ScalaPreparedStatement12[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, Row](pstmt, RowMapper.identity, StatementOptions.default, t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12))

        /** Prepares asynchronously a query that will take 13 query parameter
          *
          * @return BoundStatement that can be called like a function
          */
        def prepareAsync[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13](implicit ec: ExecutionContext, t1: Codec[T1], t2: Codec[T2], t3: Codec[T3], t4: Codec[T4], t5: Codec[T5], t6: Codec[T6], t7: Codec[T7], t8: Codec[T8], t9: Codec[T9], t10: Codec[T10], t11: Codec[T11], t12: Codec[T12], t13: Codec[T13]): Future[ScalaPreparedStatement13[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, Row]] =
            session.prepareAsync(query).asScala
                .map(pstmt => new ScalaPreparedStatement13[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, Row](pstmt, RowMapper.identity, StatementOptions.default, t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13))

        /** Prepares asynchronously a query that will take 14 query parameter
          *
          * @return BoundStatement that can be called like a function
          */
        def prepareAsync[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14](implicit ec: ExecutionContext, t1: Codec[T1], t2: Codec[T2], t3: Codec[T3], t4: Codec[T4], t5: Codec[T5], t6: Codec[T6], t7: Codec[T7], t8: Codec[T8], t9: Codec[T9], t10: Codec[T10], t11: Codec[T11], t12: Codec[T12], t13: Codec[T13], t14: Codec[T14]): Future[ScalaPreparedStatement14[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, Row]] =
            session.prepareAsync(query).asScala
                .map(pstmt => new ScalaPreparedStatement14[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, Row](pstmt, RowMapper.identity, StatementOptions.default, t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14))

        /** Prepares asynchronously a query that will take 15 query parameter
          *
          * @return BoundStatement that can be called like a function
          */
        def prepareAsync[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15](implicit ec: ExecutionContext, t1: Codec[T1], t2: Codec[T2], t3: Codec[T3], t4: Codec[T4], t5: Codec[T5], t6: Codec[T6], t7: Codec[T7], t8: Codec[T8], t9: Codec[T9], t10: Codec[T10], t11: Codec[T11], t12: Codec[T12], t13: Codec[T13], t14: Codec[T14], t15: Codec[T15]): Future[ScalaPreparedStatement15[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, Row]] =
            session.prepareAsync(query).asScala
                .map(pstmt => new ScalaPreparedStatement15[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, Row](pstmt, RowMapper.identity, StatementOptions.default, t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14, t15))

        /** Prepares asynchronously a query that will take 16 query parameter
          *
          * @return BoundStatement that can be called like a function
          */
        def prepareAsync[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16](implicit ec: ExecutionContext, t1: Codec[T1], t2: Codec[T2], t3: Codec[T3], t4: Codec[T4], t5: Codec[T5], t6: Codec[T6], t7: Codec[T7], t8: Codec[T8], t9: Codec[T9], t10: Codec[T10], t11: Codec[T11], t12: Codec[T12], t13: Codec[T13], t14: Codec[T14], t15: Codec[T15], t16: Codec[T16]): Future[ScalaPreparedStatement16[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, Row]] =
            session.prepareAsync(query).asScala
                .map(pstmt => new ScalaPreparedStatement16[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, Row](pstmt, RowMapper.identity, StatementOptions.default, t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14, t15, t16))

        /** Prepares asynchronously a query that will take 17 query parameter
          *
          * @return BoundStatement that can be called like a function
          */
        def prepareAsync[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17](implicit ec: ExecutionContext, t1: Codec[T1], t2: Codec[T2], t3: Codec[T3], t4: Codec[T4], t5: Codec[T5], t6: Codec[T6], t7: Codec[T7], t8: Codec[T8], t9: Codec[T9], t10: Codec[T10], t11: Codec[T11], t12: Codec[T12], t13: Codec[T13], t14: Codec[T14], t15: Codec[T15], t16: Codec[T16], t17: Codec[T17]): Future[ScalaPreparedStatement17[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, Row]] =
            session.prepareAsync(query).asScala
                .map(pstmt => new ScalaPreparedStatement17[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, Row](pstmt, RowMapper.identity, StatementOptions.default, t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14, t15, t16, t17))

        /** Prepares asynchronously a query that will take 18 query parameter
          *
          * @return BoundStatement that can be called like a function
          */
        def prepareAsync[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18](implicit ec: ExecutionContext, t1: Codec[T1], t2: Codec[T2], t3: Codec[T3], t4: Codec[T4], t5: Codec[T5], t6: Codec[T6], t7: Codec[T7], t8: Codec[T8], t9: Codec[T9], t10: Codec[T10], t11: Codec[T11], t12: Codec[T12], t13: Codec[T13], t14: Codec[T14], t15: Codec[T15], t16: Codec[T16], t17: Codec[T17], t18: Codec[T18]): Future[ScalaPreparedStatement18[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, Row]] =
            session.prepareAsync(query).asScala
                .map(pstmt => new ScalaPreparedStatement18[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, Row](pstmt, RowMapper.identity, StatementOptions.default, t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14, t15, t16, t17, t18))

        /** Prepares asynchronously a query that will take 19 query parameter
          *
          * @return BoundStatement that can be called like a function
          */
        def prepareAsync[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19](implicit ec: ExecutionContext, t1: Codec[T1], t2: Codec[T2], t3: Codec[T3], t4: Codec[T4], t5: Codec[T5], t6: Codec[T6], t7: Codec[T7], t8: Codec[T8], t9: Codec[T9], t10: Codec[T10], t11: Codec[T11], t12: Codec[T12], t13: Codec[T13], t14: Codec[T14], t15: Codec[T15], t16: Codec[T16], t17: Codec[T17], t18: Codec[T18], t19: Codec[T19]): Future[ScalaPreparedStatement19[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, Row]] =
            session.prepareAsync(query).asScala
                .map(pstmt => new ScalaPreparedStatement19[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, Row](pstmt, RowMapper.identity, StatementOptions.default, t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14, t15, t16, t17, t18, t19))

        /** Prepares asynchronously a query that will take 20 query parameter
          *
          * @return BoundStatement that can be called like a function
          */
        def prepareAsync[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, T20](implicit ec: ExecutionContext, t1: Codec[T1], t2: Codec[T2], t3: Codec[T3], t4: Codec[T4], t5: Codec[T5], t6: Codec[T6], t7: Codec[T7], t8: Codec[T8], t9: Codec[T9], t10: Codec[T10], t11: Codec[T11], t12: Codec[T12], t13: Codec[T13], t14: Codec[T14], t15: Codec[T15], t16: Codec[T16], t17: Codec[T17], t18: Codec[T18], t19: Codec[T19], t20: Codec[T20]): Future[ScalaPreparedStatement20[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, T20, Row]] =
            session.prepareAsync(query).asScala
                .map(pstmt => new ScalaPreparedStatement20[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, T20, Row](pstmt, RowMapper.identity, StatementOptions.default, t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14, t15, t16, t17, t18, t19, t20))

        /** Prepares asynchronously a query that will take 21 query parameter
          *
          * @return BoundStatement that can be called like a function
          */
        def prepareAsync[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, T20, T21](implicit ec: ExecutionContext, t1: Codec[T1], t2: Codec[T2], t3: Codec[T3], t4: Codec[T4], t5: Codec[T5], t6: Codec[T6], t7: Codec[T7], t8: Codec[T8], t9: Codec[T9], t10: Codec[T10], t11: Codec[T11], t12: Codec[T12], t13: Codec[T13], t14: Codec[T14], t15: Codec[T15], t16: Codec[T16], t17: Codec[T17], t18: Codec[T18], t19: Codec[T19], t20: Codec[T20], t21: Codec[T21]): Future[ScalaPreparedStatement21[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, T20, T21, Row]] =
            session.prepareAsync(query).asScala
                .map(pstmt => new ScalaPreparedStatement21[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, T20, T21, Row](pstmt, RowMapper.identity, StatementOptions.default, t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14, t15, t16, t17, t18, t19, t20, t21))

        /** Prepares asynchronously a query that will take 22 query parameter
          *
          * @return BoundStatement that can be called like a function
          */
        def prepareAsync[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, T20, T21, T22](implicit ec: ExecutionContext, t1: Codec[T1], t2: Codec[T2], t3: Codec[T3], t4: Codec[T4], t5: Codec[T5], t6: Codec[T6], t7: Codec[T7], t8: Codec[T8], t9: Codec[T9], t10: Codec[T10], t11: Codec[T11], t12: Codec[T12], t13: Codec[T13], t14: Codec[T14], t15: Codec[T15], t16: Codec[T16], t17: Codec[T17], t18: Codec[T18], t19: Codec[T19], t20: Codec[T20], t21: Codec[T21], t22: Codec[T22]): Future[ScalaPreparedStatement22[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, T20, T21, T22, Row]] =
            session.prepareAsync(query).asScala
                .map(pstmt => new ScalaPreparedStatement22[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, T20, T21, T22, Row](pstmt, RowMapper.identity, StatementOptions.default, t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14, t15, t16, t17, t18, t19, t20, t21, t22))

        // format: on
    end AsyncCQLQuery

end ScalaPreparedStatement
