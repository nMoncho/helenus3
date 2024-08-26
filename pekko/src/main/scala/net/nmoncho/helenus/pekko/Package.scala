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

package net.nmoncho.helenus.pekko

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import com.datastax.oss.driver.api.core.CqlSession
import com.datastax.oss.driver.api.core.cql.BatchStatement
import net.nmoncho.helenus.api.cql.ScalaPreparedStatement
import net.nmoncho.helenus.api.cql.WrappedBoundStatement
import net.nmoncho.helenus.internal.cql.*
import net.nmoncho.helenus.internal.cql.ScalaPreparedStatementUnit
import org.apache.pekko.Done
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.connectors.cassandra.CassandraWriteSettings
import org.apache.pekko.stream.connectors.cassandra.scaladsl.CassandraSession
import org.apache.pekko.stream.scaladsl.*
import org.reactivestreams.Publisher

implicit def toExtension(implicit session: CassandraSession): Future[CqlSession] =
    session.underlying()

private def source[Out](
    pub: CqlSession => Publisher[Out]
)(using session: CassandraSession): Source[Out, NotUsed] =
    Source
        .future(session.underlying())
        .flatMapConcat(s => Source.fromPublisher(pub(s)))

private def futureSource[Out](f: Future[Source[Out, ?]]): Source[Out, NotUsed] =
    Source
        .futureSource(f)
        .mapMaterializedValue(_ => NotUsed)

extension [In, Out](pstmt: ScalaPreparedStatement[In, Out])
    /** A `Flow` writing to Cassandra for every stream element.
      * The element to be persisted is emitted unchanged.
      *
      * @param writeSettings   settings to configure the write operation
      * @param session         implicit Cassandra session from `CassandraSessionRegistry`
      */
    def asWriteFlow(
        writeSettings: CassandraWriteSettings
    )(using session: CassandraSession): Flow[In, In, NotUsed] =
        Flow
            .lazyFlow { () =>
                Flow[In]
                    .mapAsync(writeSettings.parallelism) { element =>
                        session
                            .executeWrite(pstmt.tupled(element))
                            .map(_ => element)(using ExecutionContext.parasitic)
                    }
            }
            .mapMaterializedValue(_ => NotUsed)

    def asWriteFlowWithContext[Ctx](
        writeSettings: CassandraWriteSettings
    )(
        using session: CassandraSession
    ): FlowWithContext[In, Ctx, In, Ctx, NotUsed] =
        FlowWithContext.fromTuples {
            Flow
                .lazyFlow { () =>
                    Flow[(In, Ctx)].mapAsync(writeSettings.parallelism) { case tuple @ (element, _) =>
                        session
                            .executeWrite(pstmt.tupled(element))
                            .map(_ => tuple)(ExecutionContext.parasitic)
                    }
                }
                .mapMaterializedValue(_ => NotUsed)
        }

    /** Creates a `Flow` that uses [[com.datastax.oss.driver.api.core.cql.BatchStatement]] and groups the
      * elements internally into batches using the `writeSettings` and per `groupingKey`.
      * Use this when most of the elements in the stream share the same partition key.
      *
      * Cassandra batches that share the same partition key will only
      * resolve to one write internally in Cassandra, boosting write performance.
      *
      * "A LOGGED batch to a single partition will be converted to an UNLOGGED batch as an optimization."
      * ([[https://cassandra.apache.org/doc/latest/cql/dml.html#batch Batch CQL]])
      *
      * Be aware that this stage does NOT preserve the upstream order.
      *
      * @param writeSettings   settings to configure the batching and the write operation
      * @param groupingKey     groups the elements to go into the same batch
      * @param session         implicit Cassandra session from `CassandraSessionRegistry`
      * @tparam K extracted key type for grouping into batches
      */
    def asWriteFlowBatched[K](
        writeSettings: CassandraWriteSettings,
        groupingKey: In => K
    )(using session: CassandraSession): Flow[In, In, NotUsed] =
        import scala.jdk.CollectionConverters.*

        Flow
            .lazyFlow { () =>
                Flow[In]
                    .groupedWithin(writeSettings.maxBatchSize, writeSettings.maxBatchWait)
                    .map(_.groupBy(groupingKey).values.toList)
                    .mapConcat(identity)
                    .mapAsyncUnordered(writeSettings.parallelism) { list =>
                        val boundStatements = list.map(pstmt.tupled)
                        val batchStatement =
                            BatchStatement.newInstance(writeSettings.batchType).addAll(boundStatements.asJava)
                        session.executeWriteBatch(batchStatement).map(_ => list)(ExecutionContext.parasitic)
                    }
                    .mapConcat(_.toList)
            }
            .mapMaterializedValue(_ => NotUsed)
    end asWriteFlowBatched

    /** A `Sink` writing to Cassandra for every stream element.
      *
      * Unlike [[asWriteFlow]], stream elements are ignored after being persisted.
      *
      * @param writeSettings settings to configure the write operation
      * @param session       implicit Cassandra session from `CassandraSessionRegistry`
      */
    def asWriteSink(
        writeSettings: CassandraWriteSettings
    )(using CassandraSession): Sink[In, Future[Done]] =
        asWriteFlow(writeSettings)
            .toMat(Sink.ignore)(Keep.right)

    /** Creates a `Sink` that uses [[com.datastax.oss.driver.api.core.cql.BatchStatement]] and groups the
      * elements internally into batches using the `writeSettings` and per `groupingKey`.
      * Use this when most of the elements in the stream share the same partition key.
      *
      * Cassandra batches that share the same partition key will only
      * resolve to one write internally in Cassandra, boosting write performance.
      *
      * "A LOGGED batch to a single partition will be converted to an UNLOGGED batch as an optimization."
      * ([[https://cassandra.apache.org/doc/latest/cql/dml.html#batch Batch CQL]])
      *
      * Be aware that this stage does NOT preserve the upstream order.
      *
      * @param writeSettings settings to configure the batching and the write operation
      * @param groupingKey   groups the elements to go into the same batch
      * @param session       implicit Cassandra session from `CassandraSessionRegistry`
      * @tparam K extracted key type for grouping into batches
      */
    def asWriteSinkBatched[K](
        writeSettings: CassandraWriteSettings,
        groupingKey: In => K
    )(using CassandraSession): Sink[In, Future[Done]] =
        asWriteFlowBatched(writeSettings, groupingKey)
            .toMat(Sink.ignore)(Keep.right)

end extension

extension [In, Out](futurePstmt: Future[ScalaPreparedStatement[In, Out]])
    /** A `Flow` writing to Cassandra for every stream element.
      * The element to be persisted is emitted unchanged.
      *
      * @param writeSettings settings to configure the write operation
      * @param session       implicit Cassandra session from `CassandraSessionRegistry`
      */
    def asWriteFlow(
        writeSettings: CassandraWriteSettings
    )(using session: CassandraSession, ec: ExecutionContext): Flow[In, In, NotUsed] =
        Flow
            .lazyFlow { () =>
                Flow[In]
                    .mapAsync(writeSettings.parallelism) { element =>
                        for
                            pstmt <- futurePstmt
                            _ <- session.executeWrite(pstmt.tupled(element))
                        yield element
                    }
            }
            .mapMaterializedValue(_ => NotUsed)

    def asWriteFlowWithContext[Ctx](
        writeSettings: CassandraWriteSettings
    )(
        using session: CassandraSession,
        ec: ExecutionContext
    ): FlowWithContext[In, Ctx, In, Ctx, NotUsed] =
        FlowWithContext.fromTuples {
            Flow
                .lazyFlow { () =>
                    Flow[(In, Ctx)].mapAsync(writeSettings.parallelism) { case tuple @ (element, _) =>
                        for
                            pstmt <- futurePstmt
                            _ <- session.executeWrite(pstmt.tupled(element))
                        yield tuple
                    }
                }
                .mapMaterializedValue(_ => NotUsed)
        }

    /** Creates a `Flow` that uses [[com.datastax.oss.driver.api.core.cql.BatchStatement]] and groups the
      * elements internally into batches using the `writeSettings` and per `groupingKey`.
      * Use this when most of the elements in the stream share the same partition key.
      *
      * Cassandra batches that share the same partition key will only
      * resolve to one write internally in Cassandra, boosting write performance.
      *
      * "A LOGGED batch to a single partition will be converted to an UNLOGGED batch as an optimization."
      * ([[https://cassandra.apache.org/doc/latest/cql/dml.html#batch Batch CQL]])
      *
      * Be aware that this stage does NOT preserve the upstream order.
      *
      * @param writeSettings settings to configure the batching and the write operation
      * @param groupingKey   groups the elements to go into the same batch
      * @param session       implicit Cassandra session from `CassandraSessionRegistry`
      * @tparam K extracted key type for grouping into batches
      */
    def asWriteFlowBatched[K](
        writeSettings: CassandraWriteSettings,
        groupingKey: In => K
    )(using session: CassandraSession, ec: ExecutionContext): Flow[In, In, NotUsed] =
        import scala.jdk.CollectionConverters.*

        Flow
            .lazyFlow { () =>
                Flow[In]
                    .groupedWithin(writeSettings.maxBatchSize, writeSettings.maxBatchWait)
                    .map(_.groupBy(groupingKey).values.toList)
                    .mapConcat(identity)
                    .mapAsyncUnordered(writeSettings.parallelism) { list =>
                        for
                            boundStatements <- Future.traverse(list)(element =>
                                futurePstmt.map(_.tupled(element))
                            )
                            batchStatement =
                                BatchStatement.newInstance(writeSettings.batchType).addAll(boundStatements.asJava)
                            execution <- session.executeWriteBatch(batchStatement).map(_ => list)(ec)
                        yield execution
                    }
                    .mapConcat(_.toList)
            }
            .mapMaterializedValue(_ => NotUsed)
    end asWriteFlowBatched

    /** A `Sink` writing to Cassandra for every stream element.
      *
      * Unlike [[asWriteFlow]], stream elements are ignored after being persisted.
      *
      * @param writeSettings settings to configure the write operation
      * @param session       implicit Cassandra session from `CassandraSessionRegistry`
      */
    def asWriteSink(writeSettings: CassandraWriteSettings)(
        using CassandraSession,
        ExecutionContext
    ): Sink[In, Future[Done]] =
        asWriteFlow(writeSettings)
            .toMat(Sink.ignore)(Keep.right)

    /** Creates a `Sink` that uses [[com.datastax.oss.driver.api.core.cql.BatchStatement]] and groups the
      * elements internally into batches using the `writeSettings` and per `groupingKey`.
      * Use this when most of the elements in the stream share the same partition key.
      *
      * Cassandra batches that share the same partition key will only
      * resolve to one write internally in Cassandra, boosting write performance.
      *
      * "A LOGGED batch to a single partition will be converted to an UNLOGGED batch as an optimization."
      * ([[https://cassandra.apache.org/doc/latest/cql/dml.html#batch Batch CQL]])
      *
      * Be aware that this stage does NOT preserve the upstream order.
      *
      * @param writeSettings settings to configure the batching and the write operation
      * @param groupingKey   groups the elements to go into the same batch
      * @param session       implicit Cassandra session from `CassandraSessionRegistry`
      * @tparam K extracted key type for grouping into batches
      */
    def asWriteSinkBatched[K](
        writeSettings: CassandraWriteSettings,
        groupingKey: In => K
    )(using CassandraSession, ExecutionContext): Sink[In, Future[Done]] =
        asWriteFlowBatched(writeSettings, groupingKey)
            .toMat(Sink.ignore)(Keep.right)

end extension

extension [Out](pstmt: ScalaPreparedStatementUnit[Out])
    /** A [[Source]] reading from Cassandra
      */
    def asReadSource()(using CassandraSession): Source[Out, NotUsed] =
        source(implicit s => pstmt.executeReactive())

end extension

extension [Out](wbs: WrappedBoundStatement[Out])
    /** A [[Source]] reading from Cassandra
      */
    def asReadSource()(using CassandraSession): Source[Out, NotUsed] =
        source(implicit s => wbs.executeReactive())

end extension

extension [In, Out](pstmt: ScalaPreparedStatement1[In, Out])
    /** A [[Source]] reading from Cassandra
      *
      * @param in query parameters
      */
    def asReadSource(in: In)(using CassandraSession): Source[Out, NotUsed] =
        source(implicit s => pstmt.executeReactive(in))

end extension

// format: off
extension [T1, T2, Out](pstmt: ScalaPreparedStatement2[T1, T2, Out])
    def asReadSource(t1: T1, t2: T2)(using CassandraSession): Source[Out, NotUsed] =
      source(implicit cqlSession => pstmt.executeReactive(t1, t2))
end extension

extension [T1, T2, T3, Out](pstmt: ScalaPreparedStatement3[T1, T2, T3, Out])
    def asReadSource(t1: T1, t2: T2, t3: T3)(using CassandraSession): Source[Out, NotUsed] =
      source(implicit cqlSession => pstmt.executeReactive(t1, t2, t3))
end extension

extension [T1, T2, T3, T4, Out](pstmt: ScalaPreparedStatement4[T1, T2, T3, T4, Out])
    def asReadSource(t1: T1, t2: T2, t3: T3, t4: T4)(using CassandraSession): Source[Out, NotUsed] =
      source(implicit cqlSession => pstmt.executeReactive(t1, t2, t3, t4))
end extension

extension [T1, T2, T3, T4, T5, Out](pstmt: ScalaPreparedStatement5[T1, T2, T3, T4, T5, Out])
    def asReadSource(t1: T1, t2: T2, t3: T3, t4: T4, t5: T5)(using CassandraSession): Source[Out, NotUsed] =
      source(implicit cqlSession => pstmt.executeReactive(t1, t2, t3, t4, t5))
end extension

extension [T1, T2, T3, T4, T5, T6, Out](pstmt: ScalaPreparedStatement6[T1, T2, T3, T4, T5, T6, Out])
    def asReadSource(t1: T1, t2: T2, t3: T3, t4: T4, t5: T5, t6: T6)(using CassandraSession): Source[Out, NotUsed] =
      source(implicit cqlSession => pstmt.executeReactive(t1, t2, t3, t4, t5, t6))
end extension

extension [T1, T2, T3, T4, T5, T6, T7, Out](pstmt: ScalaPreparedStatement7[T1, T2, T3, T4, T5, T6, T7, Out])
    def asReadSource(t1: T1, t2: T2, t3: T3, t4: T4, t5: T5, t6: T6, t7: T7)(using CassandraSession): Source[Out, NotUsed] =
      source(implicit cqlSession => pstmt.executeReactive(t1, t2, t3, t4, t5, t6, t7))
end extension

extension [T1, T2, T3, T4, T5, T6, T7, T8, Out](pstmt: ScalaPreparedStatement8[T1, T2, T3, T4, T5, T6, T7, T8, Out])
    def asReadSource(t1: T1, t2: T2, t3: T3, t4: T4, t5: T5, t6: T6, t7: T7, t8: T8)(using CassandraSession): Source[Out, NotUsed] =
      source(implicit cqlSession => pstmt.executeReactive(t1, t2, t3, t4, t5, t6, t7, t8))
end extension

extension [T1, T2, T3, T4, T5, T6, T7, T8, T9, Out](pstmt: ScalaPreparedStatement9[T1, T2, T3, T4, T5, T6, T7, T8, T9, Out])
    def asReadSource(t1: T1, t2: T2, t3: T3, t4: T4, t5: T5, t6: T6, t7: T7, t8: T8, t9: T9)(using CassandraSession): Source[Out, NotUsed] =
      source(implicit cqlSession => pstmt.executeReactive(t1, t2, t3, t4, t5, t6, t7, t8, t9))
end extension

extension [T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, Out](pstmt: ScalaPreparedStatement10[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, Out])
    def asReadSource(t1: T1, t2: T2, t3: T3, t4: T4, t5: T5, t6: T6, t7: T7, t8: T8, t9: T9, t10: T10)(using CassandraSession): Source[Out, NotUsed] =
      source(implicit cqlSession => pstmt.executeReactive(t1, t2, t3, t4, t5, t6, t7, t8, t9, t10))
end extension

extension [T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, Out](pstmt: ScalaPreparedStatement11[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, Out])
    def asReadSource(t1: T1, t2: T2, t3: T3, t4: T4, t5: T5, t6: T6, t7: T7, t8: T8, t9: T9, t10: T10, t11: T11)(using CassandraSession): Source[Out, NotUsed] =
      source(implicit cqlSession => pstmt.executeReactive(t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11))
end extension

extension [T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, Out](pstmt: ScalaPreparedStatement12[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, Out])
    def asReadSource(t1: T1, t2: T2, t3: T3, t4: T4, t5: T5, t6: T6, t7: T7, t8: T8, t9: T9, t10: T10, t11: T11, t12: T12)(using CassandraSession): Source[Out, NotUsed] =
      source(implicit cqlSession => pstmt.executeReactive(t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12))
end extension

extension [T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, Out](pstmt: ScalaPreparedStatement13[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, Out])
    def asReadSource(t1: T1, t2: T2, t3: T3, t4: T4, t5: T5, t6: T6, t7: T7, t8: T8, t9: T9, t10: T10, t11: T11, t12: T12, t13: T13)(using CassandraSession): Source[Out, NotUsed] =
      source(implicit cqlSession => pstmt.executeReactive(t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13))
end extension

extension [T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, Out](pstmt: ScalaPreparedStatement14[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, Out])
    def asReadSource(t1: T1, t2: T2, t3: T3, t4: T4, t5: T5, t6: T6, t7: T7, t8: T8, t9: T9, t10: T10, t11: T11, t12: T12, t13: T13, t14: T14)(using CassandraSession): Source[Out, NotUsed] =
      source(implicit cqlSession => pstmt.executeReactive(t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14))
end extension

extension [T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, Out](pstmt: ScalaPreparedStatement15[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, Out])
    def asReadSource(t1: T1, t2: T2, t3: T3, t4: T4, t5: T5, t6: T6, t7: T7, t8: T8, t9: T9, t10: T10, t11: T11, t12: T12, t13: T13, t14: T14, t15: T15)(using CassandraSession): Source[Out, NotUsed] =
      source(implicit cqlSession => pstmt.executeReactive(t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14, t15))
end extension

extension [T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, Out](pstmt: ScalaPreparedStatement16[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, Out])
    def asReadSource(t1: T1, t2: T2, t3: T3, t4: T4, t5: T5, t6: T6, t7: T7, t8: T8, t9: T9, t10: T10, t11: T11, t12: T12, t13: T13, t14: T14, t15: T15, t16: T16)(using CassandraSession): Source[Out, NotUsed] =
      source(implicit cqlSession => pstmt.executeReactive(t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14, t15, t16))
end extension

extension [T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, Out](pstmt: ScalaPreparedStatement17[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, Out])
    def asReadSource(t1: T1, t2: T2, t3: T3, t4: T4, t5: T5, t6: T6, t7: T7, t8: T8, t9: T9, t10: T10, t11: T11, t12: T12, t13: T13, t14: T14, t15: T15, t16: T16, t17: T17)(using CassandraSession): Source[Out, NotUsed] =
      source(implicit cqlSession => pstmt.executeReactive(t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14, t15, t16, t17))
end extension

extension [T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, Out](pstmt: ScalaPreparedStatement18[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, Out])
    def asReadSource(t1: T1, t2: T2, t3: T3, t4: T4, t5: T5, t6: T6, t7: T7, t8: T8, t9: T9, t10: T10, t11: T11, t12: T12, t13: T13, t14: T14, t15: T15, t16: T16, t17: T17, t18: T18)(using CassandraSession): Source[Out, NotUsed] =
      source(implicit cqlSession => pstmt.executeReactive(t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14, t15, t16, t17, t18))
end extension

extension [T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, Out](pstmt: ScalaPreparedStatement19[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, Out])
    def asReadSource(t1: T1, t2: T2, t3: T3, t4: T4, t5: T5, t6: T6, t7: T7, t8: T8, t9: T9, t10: T10, t11: T11, t12: T12, t13: T13, t14: T14, t15: T15, t16: T16, t17: T17, t18: T18, t19: T19)(using CassandraSession): Source[Out, NotUsed] =
      source(implicit cqlSession => pstmt.executeReactive(t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14, t15, t16, t17, t18, t19))
end extension

extension [T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, T20, Out](pstmt: ScalaPreparedStatement20[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, T20, Out])
    def asReadSource(t1: T1, t2: T2, t3: T3, t4: T4, t5: T5, t6: T6, t7: T7, t8: T8, t9: T9, t10: T10, t11: T11, t12: T12, t13: T13, t14: T14, t15: T15, t16: T16, t17: T17, t18: T18, t19: T19, t20: T20)(using CassandraSession): Source[Out, NotUsed] =
      source(implicit cqlSession => pstmt.executeReactive(t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14, t15, t16, t17, t18, t19, t20))
end extension

extension [T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, T20, T21, Out](pstmt: ScalaPreparedStatement21[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, T20, T21, Out])
    def asReadSource(t1: T1, t2: T2, t3: T3, t4: T4, t5: T5, t6: T6, t7: T7, t8: T8, t9: T9, t10: T10, t11: T11, t12: T12, t13: T13, t14: T14, t15: T15, t16: T16, t17: T17, t18: T18, t19: T19, t20: T20, t21: T21)(using CassandraSession): Source[Out, NotUsed] =
      source(implicit cqlSession => pstmt.executeReactive(t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14, t15, t16, t17, t18, t19, t20, t21))
end extension

extension [T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, T20, T21, T22, Out](pstmt: ScalaPreparedStatement22[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, T20, T21, T22, Out])
    def asReadSource(t1: T1, t2: T2, t3: T3, t4: T4, t5: T5, t6: T6, t7: T7, t8: T8, t9: T9, t10: T10, t11: T11, t12: T12, t13: T13, t14: T14, t15: T15, t16: T16, t17: T17, t18: T18, t19: T19, t20: T20, t21: T21, t22: T22)(using CassandraSession): Source[Out, NotUsed] =
      source(implicit cqlSession => pstmt.executeReactive(t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14, t15, t16, t17, t18, t19, t20, t21, t22))
end extension
// format: on

extension [Out](pstmt: Future[ScalaPreparedStatementUnit[Out]])
    /** A [[Source]] reading from Cassandra
      */
    def asReadSource()(using CassandraSession, ExecutionContext): Source[Out, NotUsed] =
        futureSource(pstmt.map(_.asReadSource()))

end extension

extension [In, Out](pstmt: Future[ScalaPreparedStatement1[In, Out]])
    /** A [[Source]] reading from Cassandra
      *
      * @param in query parameters
      */
    def asReadSource(in: In)(using CassandraSession, ExecutionContext): Source[Out, NotUsed] =
        futureSource(pstmt.map(_.asReadSource(in)))
end extension

// format: off
extension [T1, T2, Out](pstmt: Future[ScalaPreparedStatement2[T1, T2, Out]])
    def asReadSource(t1: T1, t2: T2)(using CassandraSession, ExecutionContext): Source[Out, NotUsed] =
      futureSource(pstmt.map(_.asReadSource(t1, t2)))
end extension

extension [T1, T2, T3, Out](pstmt: Future[ScalaPreparedStatement3[T1, T2, T3, Out]])
    def asReadSource(t1: T1, t2: T2, t3: T3)(using CassandraSession, ExecutionContext): Source[Out, NotUsed] =
      futureSource(pstmt.map(_.asReadSource(t1, t2, t3)))
end extension

extension [T1, T2, T3, T4, Out](pstmt: Future[ScalaPreparedStatement4[T1, T2, T3, T4, Out]])
    def asReadSource(t1: T1, t2: T2, t3: T3, t4: T4)(using CassandraSession, ExecutionContext): Source[Out, NotUsed] =
      futureSource(pstmt.map(_.asReadSource(t1, t2, t3, t4)))
end extension

extension [T1, T2, T3, T4, T5, Out](pstmt: Future[ScalaPreparedStatement5[T1, T2, T3, T4, T5, Out]])
    def asReadSource(t1: T1, t2: T2, t3: T3, t4: T4, t5: T5)(using CassandraSession, ExecutionContext): Source[Out, NotUsed] =
      futureSource(pstmt.map(_.asReadSource(t1, t2, t3, t4, t5)))
end extension

extension [T1, T2, T3, T4, T5, T6, Out](pstmt: Future[ScalaPreparedStatement6[T1, T2, T3, T4, T5, T6, Out]])
    def asReadSource(t1: T1, t2: T2, t3: T3, t4: T4, t5: T5, t6: T6)(using CassandraSession, ExecutionContext): Source[Out, NotUsed] =
      futureSource(pstmt.map(_.asReadSource(t1, t2, t3, t4, t5, t6)))
end extension

extension [T1, T2, T3, T4, T5, T6, T7, Out](pstmt: Future[ScalaPreparedStatement7[T1, T2, T3, T4, T5, T6, T7, Out]])
    def asReadSource(t1: T1, t2: T2, t3: T3, t4: T4, t5: T5, t6: T6, t7: T7)(using CassandraSession, ExecutionContext): Source[Out, NotUsed] =
      futureSource(pstmt.map(_.asReadSource(t1, t2, t3, t4, t5, t6, t7)))
end extension

extension [T1, T2, T3, T4, T5, T6, T7, T8, Out](pstmt: Future[ScalaPreparedStatement8[T1, T2, T3, T4, T5, T6, T7, T8, Out]])
    def asReadSource(t1: T1, t2: T2, t3: T3, t4: T4, t5: T5, t6: T6, t7: T7, t8: T8)(using CassandraSession, ExecutionContext): Source[Out, NotUsed] =
      futureSource(pstmt.map(_.asReadSource(t1, t2, t3, t4, t5, t6, t7, t8)))
end extension

extension [T1, T2, T3, T4, T5, T6, T7, T8, T9, Out](pstmt: Future[ScalaPreparedStatement9[T1, T2, T3, T4, T5, T6, T7, T8, T9, Out]])
    def asReadSource(t1: T1, t2: T2, t3: T3, t4: T4, t5: T5, t6: T6, t7: T7, t8: T8, t9: T9)(using CassandraSession, ExecutionContext): Source[Out, NotUsed] =
      futureSource(pstmt.map(_.asReadSource(t1, t2, t3, t4, t5, t6, t7, t8, t9)))
end extension

extension [T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, Out](pstmt: Future[ScalaPreparedStatement10[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, Out]])
    def asReadSource(t1: T1, t2: T2, t3: T3, t4: T4, t5: T5, t6: T6, t7: T7, t8: T8, t9: T9, t10: T10)(using CassandraSession, ExecutionContext): Source[Out, NotUsed] =
      futureSource(pstmt.map(_.asReadSource(t1, t2, t3, t4, t5, t6, t7, t8, t9, t10)))
end extension

extension [T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, Out](pstmt: Future[ScalaPreparedStatement11[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, Out]])
    def asReadSource(t1: T1, t2: T2, t3: T3, t4: T4, t5: T5, t6: T6, t7: T7, t8: T8, t9: T9, t10: T10, t11: T11)(using CassandraSession, ExecutionContext): Source[Out, NotUsed] =
      futureSource(pstmt.map(_.asReadSource(t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11)))
end extension

extension [T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, Out](pstmt: Future[ScalaPreparedStatement12[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, Out]])
    def asReadSource(t1: T1, t2: T2, t3: T3, t4: T4, t5: T5, t6: T6, t7: T7, t8: T8, t9: T9, t10: T10, t11: T11, t12: T12)(using CassandraSession, ExecutionContext): Source[Out, NotUsed] =
      futureSource(pstmt.map(_.asReadSource(t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12)))
end extension

extension [T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, Out](pstmt: Future[ScalaPreparedStatement13[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, Out]])
    def asReadSource(t1: T1, t2: T2, t3: T3, t4: T4, t5: T5, t6: T6, t7: T7, t8: T8, t9: T9, t10: T10, t11: T11, t12: T12, t13: T13)(using CassandraSession, ExecutionContext): Source[Out, NotUsed] =
      futureSource(pstmt.map(_.asReadSource(t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13)))
end extension

extension [T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, Out](pstmt: Future[ScalaPreparedStatement14[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, Out]])
    def asReadSource(t1: T1, t2: T2, t3: T3, t4: T4, t5: T5, t6: T6, t7: T7, t8: T8, t9: T9, t10: T10, t11: T11, t12: T12, t13: T13, t14: T14)(using CassandraSession, ExecutionContext): Source[Out, NotUsed] =
      futureSource(pstmt.map(_.asReadSource(t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14)))
end extension

extension [T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, Out](pstmt: Future[ScalaPreparedStatement15[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, Out]])
    def asReadSource(t1: T1, t2: T2, t3: T3, t4: T4, t5: T5, t6: T6, t7: T7, t8: T8, t9: T9, t10: T10, t11: T11, t12: T12, t13: T13, t14: T14, t15: T15)(using CassandraSession, ExecutionContext): Source[Out, NotUsed] =
      futureSource(pstmt.map(_.asReadSource(t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14, t15)))
end extension

extension [T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, Out](pstmt: Future[ScalaPreparedStatement16[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, Out]])
    def asReadSource(t1: T1, t2: T2, t3: T3, t4: T4, t5: T5, t6: T6, t7: T7, t8: T8, t9: T9, t10: T10, t11: T11, t12: T12, t13: T13, t14: T14, t15: T15, t16: T16)(using CassandraSession, ExecutionContext): Source[Out, NotUsed] =
      futureSource(pstmt.map(_.asReadSource(t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14, t15, t16)))
end extension

extension [T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, Out](pstmt: Future[ScalaPreparedStatement17[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, Out]])
    def asReadSource(t1: T1, t2: T2, t3: T3, t4: T4, t5: T5, t6: T6, t7: T7, t8: T8, t9: T9, t10: T10, t11: T11, t12: T12, t13: T13, t14: T14, t15: T15, t16: T16, t17: T17)(using CassandraSession, ExecutionContext): Source[Out, NotUsed] =
      futureSource(pstmt.map(_.asReadSource(t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14, t15, t16, t17)))
end extension

extension [T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, Out](pstmt: Future[ScalaPreparedStatement18[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, Out]])
    def asReadSource(t1: T1, t2: T2, t3: T3, t4: T4, t5: T5, t6: T6, t7: T7, t8: T8, t9: T9, t10: T10, t11: T11, t12: T12, t13: T13, t14: T14, t15: T15, t16: T16, t17: T17, t18: T18)(using CassandraSession, ExecutionContext): Source[Out, NotUsed] =
      futureSource(pstmt.map(_.asReadSource(t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14, t15, t16, t17, t18)))
end extension

extension [T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, Out](pstmt: Future[ScalaPreparedStatement19[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, Out]])
    def asReadSource(t1: T1, t2: T2, t3: T3, t4: T4, t5: T5, t6: T6, t7: T7, t8: T8, t9: T9, t10: T10, t11: T11, t12: T12, t13: T13, t14: T14, t15: T15, t16: T16, t17: T17, t18: T18, t19: T19)(using CassandraSession, ExecutionContext): Source[Out, NotUsed] =
      futureSource(pstmt.map(_.asReadSource(t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14, t15, t16, t17, t18, t19)))
end extension

extension [T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, T20, Out](pstmt: Future[ScalaPreparedStatement20[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, T20, Out]])
    def asReadSource(t1: T1, t2: T2, t3: T3, t4: T4, t5: T5, t6: T6, t7: T7, t8: T8, t9: T9, t10: T10, t11: T11, t12: T12, t13: T13, t14: T14, t15: T15, t16: T16, t17: T17, t18: T18, t19: T19, t20: T20)(using CassandraSession, ExecutionContext): Source[Out, NotUsed] =
      futureSource(pstmt.map(_.asReadSource(t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14, t15, t16, t17, t18, t19, t20)))
end extension

extension [T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, T20, T21, Out](pstmt: Future[ScalaPreparedStatement21[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, T20, T21, Out]])
    def asReadSource(t1: T1, t2: T2, t3: T3, t4: T4, t5: T5, t6: T6, t7: T7, t8: T8, t9: T9, t10: T10, t11: T11, t12: T12, t13: T13, t14: T14, t15: T15, t16: T16, t17: T17, t18: T18, t19: T19, t20: T20, t21: T21)(using CassandraSession, ExecutionContext): Source[Out, NotUsed] =
      futureSource(pstmt.map(_.asReadSource(t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14, t15, t16, t17, t18, t19, t20, t21)))
end extension

extension [T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, T20, T21, T22, Out](pstmt: Future[ScalaPreparedStatement22[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, T20, T21, T22, Out]])
    def asReadSource(t1: T1, t2: T2, t3: T3, t4: T4, t5: T5, t6: T6, t7: T7, t8: T8, t9: T9, t10: T10, t11: T11, t12: T12, t13: T13, t14: T14, t15: T15, t16: T16, t17: T17, t18: T18, t19: T19, t20: T20, t21: T21, t22: T22)(using CassandraSession, ExecutionContext): Source[Out, NotUsed] =
      futureSource(pstmt.map(_.asReadSource(t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14, t15, t16, t17, t18, t19, t20, t21, t22)))
end extension
// format: on
