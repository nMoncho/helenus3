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

package net.nmoncho.helenus.monix

import scala.annotation.targetName
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration
import scala.jdk.FutureConverters.*

import _root_.monix.eval.Task
import _root_.monix.execution.Ack
import _root_.monix.reactive.Consumer
import _root_.monix.reactive.Observable
import _root_.monix.reactive.Observer
import com.datastax.oss.driver.api.core.CqlSession
import com.datastax.oss.driver.api.core.cql.BatchStatement
import com.datastax.oss.driver.api.core.cql.BatchType
import net.nmoncho.helenus.api.cql.Pager
import net.nmoncho.helenus.api.cql.ScalaPreparedStatement
import net.nmoncho.helenus.api.cql.WrappedBoundStatement
import net.nmoncho.helenus.internal.cql.*

extension [Out](wbs: WrappedBoundStatement[Out])
    /** An [[Observable]] reading from Cassandra */
    def asObservable()(using CqlSession): Observable[Out] =
        Observable.fromReactivePublisher(wbs.executeReactive())
end extension

extension [Out](pstmt: ScalaPreparedStatementUnit[Out])
    /** An [[Observable]] reading from Cassandra */
    def asObservable()(using CqlSession): Observable[Out] =
        Observable.fromReactivePublisher(pstmt.executeReactive())
end extension

extension [T1, Out](pstmt: ScalaPreparedStatement1[T1, Out])
    /** An [[Observable]] reading from Cassandra */
    def asObservable(t1: T1)(using CqlSession): Observable[Out] =
        Observable.fromReactivePublisher(pstmt.executeReactive(t1))
end extension

extension [Out](pager: Pager[Out])
    /** An [[Observable]] reading from Cassandra
      *
      * @param pageSize how many rows to fetch
      */
    def asObservable(pageSize: Int)(using CqlSession): Observable[(Pager[Out], Out)] =
        Observable.fromReactivePublisher(pager.executeReactive(pageSize))
end extension

extension [In, Out](pstmt: ScalaPreparedStatement[In, Out])
    /** Creates a [[Consumer]] that will consume data from an [[Observable]]
      *
      * This is meant to be used for writing or updating to Cassandra
      */
    def asConsumer()(using session: CqlSession): Consumer[In, Unit] =
        Consumer.create[In, Unit] { (_, _, callback) =>
            new Observer.Sync[In]:

                override def onNext(elem: In): Ack =
                    session.execute(pstmt.tupled(elem))
                    Ack.Continue

                override def onError(ex: Throwable): Unit = callback.onError(ex)

                override def onComplete(): Unit = callback.onSuccess(())
        }
end extension

extension [In](obs: Observable[In])
    def batchedConsumer[K](
        pstmt: ScalaPreparedStatement[In, ?],
        groupingKey: In => K,
        maxBatchSize: Int,
        maxBatchWait: FiniteDuration,
        batchType: BatchType
    )(using session: CqlSession): Task[Unit] =
        obs
            .bufferTimedAndCounted(maxBatchWait, maxBatchSize)
            .map(value => Observable.pure(value.groupBy(groupingKey).values.flatten))
            .flatten
            .consumeWith(
              Consumer.create[Iterable[In], Unit] { (_, _, callback) =>
                  import scala.jdk.CollectionConverters.*

                  new Observer.Sync[Iterable[In]]:

                      override def onNext(elem: Iterable[In]): Ack =
                          val batch          = elem.map(pstmt.tupled)
                          val batchStatement = BatchStatement.newInstance(batchType).addAll(batch.asJava)
                          session.execute(batchStatement)

                          Ack.Continue
                      end onNext

                      override def onError(ex: Throwable): Unit = callback.onError(ex)

                      override def onComplete(): Unit = callback.onSuccess(())
                  end new
              }
            )
end extension


// format: off
// $COVERAGE-OFF$
extension [T1, T2, T3, Out](pstmt: ScalaPreparedStatement2[T1, T2, Out])
    /** An [[Observable]] reading from Cassandra */
    def asObservable(t1: T1, t2: T2)(using CqlSession): Observable[Out] = {
        Observable.fromReactivePublisher(pstmt.executeReactive(t1, t2))
    }
end extension

extension [T1, T2, T3, Out](pstmt: ScalaPreparedStatement3[T1, T2, T3, Out])
    /** An [[Observable]] reading from Cassandra */
    def asObservable(t1: T1, t2: T2, t3: T3)(using CqlSession): Observable[Out] = {
        Observable.fromReactivePublisher(pstmt.executeReactive(t1, t2, t3))
    }
end extension

extension [T1, T2, T3, T4, Out](pstmt: ScalaPreparedStatement4[T1, T2, T3, T4, Out])
    /** An [[Observable]] reading from Cassandra */
    def asObservable(t1: T1, t2: T2, t3: T3, t4: T4)(using CqlSession): Observable[Out] = {
        Observable.fromReactivePublisher(pstmt.executeReactive(t1, t2, t3, t4))
    }
end extension

extension [T1, T2, T3, T4, T5, Out](pstmt: ScalaPreparedStatement5[T1, T2, T3, T4, T5, Out])
    /** An [[Observable]] reading from Cassandra */
    def asObservable(t1: T1, t2: T2, t3: T3, t4: T4, t5: T5)(using CqlSession): Observable[Out] = {
        Observable.fromReactivePublisher(pstmt.executeReactive(t1, t2, t3, t4, t5))
    }
end extension

extension [T1, T2, T3, T4, T5, T6, Out](pstmt: ScalaPreparedStatement6[T1, T2, T3, T4, T5, T6, Out])
    /** An [[Observable]] reading from Cassandra */
    def asObservable(t1: T1, t2: T2, t3: T3, t4: T4, t5: T5, t6: T6)(using CqlSession): Observable[Out] = {
        Observable.fromReactivePublisher(pstmt.executeReactive(t1, t2, t3, t4, t5, t6))
    }
end extension

extension [T1, T2, T3, T4, T5, T6, T7, Out](pstmt: ScalaPreparedStatement7[T1, T2, T3, T4, T5, T6, T7, Out])
    /** An [[Observable]] reading from Cassandra */
    def asObservable(t1: T1, t2: T2, t3: T3, t4: T4, t5: T5, t6: T6, t7: T7)(using CqlSession): Observable[Out] = {
        Observable.fromReactivePublisher(pstmt.executeReactive(t1, t2, t3, t4, t5, t6, t7))
    }
end extension

extension [T1, T2, T3, T4, T5, T6, T7, T8, Out](pstmt: ScalaPreparedStatement8[T1, T2, T3, T4, T5, T6, T7, T8, Out])
    /** An [[Observable]] reading from Cassandra */
    def asObservable(t1: T1, t2: T2, t3: T3, t4: T4, t5: T5, t6: T6, t7: T7, t8: T8)(using CqlSession): Observable[Out] = {
        Observable.fromReactivePublisher(pstmt.executeReactive(t1, t2, t3, t4, t5, t6, t7, t8))
    }
end extension

extension [T1, T2, T3, T4, T5, T6, T7, T8, T9, Out](pstmt: ScalaPreparedStatement9[T1, T2, T3, T4, T5, T6, T7, T8, T9, Out])
    /** An [[Observable]] reading from Cassandra */
    def asObservable(t1: T1, t2: T2, t3: T3, t4: T4, t5: T5, t6: T6, t7: T7, t8: T8, t9: T9)(using CqlSession): Observable[Out] = {
        Observable.fromReactivePublisher(pstmt.executeReactive(t1, t2, t3, t4, t5, t6, t7, t8, t9))
    }
end extension

extension [T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, Out](pstmt: ScalaPreparedStatement10[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, Out])
    /** An [[Observable]] reading from Cassandra */
    def asObservable(t1: T1, t2: T2, t3: T3, t4: T4, t5: T5, t6: T6, t7: T7, t8: T8, t9: T9, t10: T10)(using CqlSession): Observable[Out] = {
        Observable.fromReactivePublisher(pstmt.executeReactive(t1, t2, t3, t4, t5, t6, t7, t8, t9, t10))
    }
end extension

extension [T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, Out](pstmt: ScalaPreparedStatement11[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, Out])
    /** An [[Observable]] reading from Cassandra */
    def asObservable(t1: T1, t2: T2, t3: T3, t4: T4, t5: T5, t6: T6, t7: T7, t8: T8, t9: T9, t10: T10, t11: T11)(using CqlSession): Observable[Out] = {
        Observable.fromReactivePublisher(pstmt.executeReactive(t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11))
    }
end extension

extension [T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, Out](pstmt: ScalaPreparedStatement12[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, Out])
    /** An [[Observable]] reading from Cassandra */
    def asObservable(t1: T1, t2: T2, t3: T3, t4: T4, t5: T5, t6: T6, t7: T7, t8: T8, t9: T9, t10: T10, t11: T11, t12: T12)(using CqlSession): Observable[Out] = {
        Observable.fromReactivePublisher(pstmt.executeReactive(t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12))
    }
end extension

extension [T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, Out](pstmt: ScalaPreparedStatement13[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, Out])
    /** An [[Observable]] reading from Cassandra */
    def asObservable(t1: T1, t2: T2, t3: T3, t4: T4, t5: T5, t6: T6, t7: T7, t8: T8, t9: T9, t10: T10, t11: T11, t12: T12, t13: T13)(using CqlSession): Observable[Out] = {
        Observable.fromReactivePublisher(pstmt.executeReactive(t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13))
    }
end extension

extension [T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, Out](pstmt: ScalaPreparedStatement14[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, Out])
    /** An [[Observable]] reading from Cassandra */
    def asObservable(t1: T1, t2: T2, t3: T3, t4: T4, t5: T5, t6: T6, t7: T7, t8: T8, t9: T9, t10: T10, t11: T11, t12: T12, t13: T13, t14: T14)(using CqlSession): Observable[Out] = {
        Observable.fromReactivePublisher(pstmt.executeReactive(t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14))
    }
end extension

extension [T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, Out](pstmt: ScalaPreparedStatement15[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, Out])
    /** An [[Observable]] reading from Cassandra */
    def asObservable(t1: T1, t2: T2, t3: T3, t4: T4, t5: T5, t6: T6, t7: T7, t8: T8, t9: T9, t10: T10, t11: T11, t12: T12, t13: T13, t14: T14, t15: T15)(using CqlSession): Observable[Out] = {
        Observable.fromReactivePublisher(pstmt.executeReactive(t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14, t15))
    }
end extension

extension [T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, Out](pstmt: ScalaPreparedStatement16[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, Out])
    /** An [[Observable]] reading from Cassandra */
    def asObservable(t1: T1, t2: T2, t3: T3, t4: T4, t5: T5, t6: T6, t7: T7, t8: T8, t9: T9, t10: T10, t11: T11, t12: T12, t13: T13, t14: T14, t15: T15, t16: T16)(using CqlSession): Observable[Out] = {
        Observable.fromReactivePublisher(pstmt.executeReactive(t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14, t15, t16))
    }
end extension

extension [T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, Out](pstmt: ScalaPreparedStatement17[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, Out])
    /** An [[Observable]] reading from Cassandra */
    def asObservable(t1: T1, t2: T2, t3: T3, t4: T4, t5: T5, t6: T6, t7: T7, t8: T8, t9: T9, t10: T10, t11: T11, t12: T12, t13: T13, t14: T14, t15: T15, t16: T16, t17: T17)(using CqlSession): Observable[Out] = {
        Observable.fromReactivePublisher(pstmt.executeReactive(t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14, t15, t16, t17))
    }
end extension

extension [T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, Out](pstmt: ScalaPreparedStatement18[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, Out])
    /** An [[Observable]] reading from Cassandra */
    def asObservable(t1: T1, t2: T2, t3: T3, t4: T4, t5: T5, t6: T6, t7: T7, t8: T8, t9: T9, t10: T10, t11: T11, t12: T12, t13: T13, t14: T14, t15: T15, t16: T16, t17: T17, t18: T18)(using CqlSession): Observable[Out] = {
        Observable.fromReactivePublisher(pstmt.executeReactive(t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14, t15, t16, t17, t18))
    }
end extension

extension [T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, Out](pstmt: ScalaPreparedStatement19[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, Out])
    /** An [[Observable]] reading from Cassandra */
    def asObservable(t1: T1, t2: T2, t3: T3, t4: T4, t5: T5, t6: T6, t7: T7, t8: T8, t9: T9, t10: T10, t11: T11, t12: T12, t13: T13, t14: T14, t15: T15, t16: T16, t17: T17, t18: T18, t19: T19)(using CqlSession): Observable[Out] = {
        Observable.fromReactivePublisher(pstmt.executeReactive(t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14, t15, t16, t17, t18, t19))
    }
end extension

extension [T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, T20, Out](pstmt: ScalaPreparedStatement20[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, T20, Out])
    /** An [[Observable]] reading from Cassandra */
    def asObservable(t1: T1, t2: T2, t3: T3, t4: T4, t5: T5, t6: T6, t7: T7, t8: T8, t9: T9, t10: T10, t11: T11, t12: T12, t13: T13, t14: T14, t15: T15, t16: T16, t17: T17, t18: T18, t19: T19, t20: T20)(using CqlSession): Observable[Out] = {
        Observable.fromReactivePublisher(pstmt.executeReactive(t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14, t15, t16, t17, t18, t19, t20))
    }
end extension

extension [T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, T20, T21, Out](pstmt: ScalaPreparedStatement21[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, T20, T21, Out])
    /** An [[Observable]] reading from Cassandra */
    def asObservable(t1: T1, t2: T2, t3: T3, t4: T4, t5: T5, t6: T6, t7: T7, t8: T8, t9: T9, t10: T10, t11: T11, t12: T12, t13: T13, t14: T14, t15: T15, t16: T16, t17: T17, t18: T18, t19: T19, t20: T20, t21: T21)(using CqlSession): Observable[Out] = {
        Observable.fromReactivePublisher(pstmt.executeReactive(t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14, t15, t16, t17, t18, t19, t20, t21))
    }
end extension

extension [T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, T20, T21, T22, Out](pstmt: ScalaPreparedStatement22[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, T20, T21, T22, Out])
    /** An [[Observable]] reading from Cassandra */
    def asObservable(t1: T1, t2: T2, t3: T3, t4: T4, t5: T5, t6: T6, t7: T7, t8: T8, t9: T9, t10: T10, t11: T11, t12: T12, t13: T13, t14: T14, t15: T15, t16: T16, t17: T17, t18: T18, t19: T19, t20: T20, t21: T21, t22: T22)(using CqlSession): Observable[Out] = {
        Observable.fromReactivePublisher(pstmt.executeReactive(t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14, t15, t16, t17, t18, t19, t20, t21, t22))
    }
end extension
// $COVERAGE-ON$
// format: on

extension [Out](wbs: Future[WrappedBoundStatement[Out]])
    /** An [[Observable]] reading from Cassandra */
    @targetName("future_scala_wrapped_statement_as_observable")
    def asObservable()(using CqlSession): Observable[Out] =
        Observable
            .fromFuture(wbs)
            .flatMap(w => Observable.fromReactivePublisher(w.executeReactive()))
end extension

extension [Out](pstmt: Future[ScalaPreparedStatementUnit[Out]])
    /** An [[Observable]] reading from Cassandra */
    @targetName("future_scala_pstmt_unit_as_observable")
    def asObservable()(
        using CqlSession
    ): Observable[Out] =
        Observable
            .fromFuture(pstmt)
            .flatMap(p => Observable.fromReactivePublisher(p.executeReactive()))
end extension

extension [T1, Out](pstmt: Future[ScalaPreparedStatement1[T1, Out]])
    def asObservable(t1: T1)(using CqlSession): Observable[Out] =
        Observable
            .fromFuture(pstmt)
            .flatMap(p => Observable.fromReactivePublisher(p.executeReactive(t1)))
end extension

extension [Out](pager: Future[Pager[Out]])
    /** An [[Observable]] reading from Cassandra
      *
      * @param pageSize how many rows to fetch
      */
    def asObservable(pageSize: Int)(
        using CqlSession
    ): Observable[(Pager[Out], Out)] =
        Observable
            .fromFuture(pager)
            .flatMap(p => Observable.fromReactivePublisher(p.executeReactive(pageSize)))
end extension

extension [In, Out](futPstmt: Future[ScalaPreparedStatement[In, Out]])
    /** Creates a [[Consumer]] that will consume data from an [[Observable]]
      *
      * This is meant to be used for writing or updating to Cassandra
      */
    def asConsumer()(using session: CqlSession): Consumer[In, Unit] =
        Consumer.create[In, Unit] { (scheduler, _, callback) =>
            new Observer[In]:
                private implicit val ec: ExecutionContext = scheduler

                override def onNext(elem: In): Future[Ack] =
                    for
                        pstmt <- futPstmt
                        _ <- session.executeAsync(pstmt.tupled(elem)).asScala
                    yield Ack.Continue

                override def onError(ex: Throwable): Unit = callback.onError(ex)

                override def onComplete(): Unit = callback.onSuccess(())
        }
end extension


// format: off
// $COVERAGE-OFF$
extension [T1, T2, T3, Out](pstmt: Future[ScalaPreparedStatement2[T1, T2, Out]])
    /** An [[Observable]] reading from Cassandra */
    def asObservable(t1: T1, t2: T2)(using CqlSession): Observable[Out] = {
        Observable.fromFuture(pstmt)
            .flatMap(p => Observable.fromReactivePublisher(p.executeReactive(t1, t2)))
    }
end extension

extension [T1, T2, T3, Out](pstmt: Future[ScalaPreparedStatement3[T1, T2, T3, Out]])
    /** An [[Observable]] reading from Cassandra */
    def asObservable(t1: T1, t2: T2, t3: T3)(using CqlSession): Observable[Out] = {
        Observable.fromFuture(pstmt)
            .flatMap(p => Observable.fromReactivePublisher(p.executeReactive(t1, t2, t3)))
    }
end extension

extension [T1, T2, T3, T4, Out](pstmt: Future[ScalaPreparedStatement4[T1, T2, T3, T4, Out]])
    /** An [[Observable]] reading from Cassandra */
    def asObservable(t1: T1, t2: T2, t3: T3, t4: T4)(using CqlSession): Observable[Out] = {
        Observable.fromFuture(pstmt)
            .flatMap(p => Observable.fromReactivePublisher(p.executeReactive(t1, t2, t3, t4)))
    }
end extension

extension [T1, T2, T3, T4, T5, Out](pstmt: Future[ScalaPreparedStatement5[T1, T2, T3, T4, T5, Out]])
    /** An [[Observable]] reading from Cassandra */
    def asObservable(t1: T1, t2: T2, t3: T3, t4: T4, t5: T5)(using CqlSession): Observable[Out] = {
        Observable.fromFuture(pstmt)
            .flatMap(p => Observable.fromReactivePublisher(p.executeReactive(t1, t2, t3, t4, t5)))
    }
end extension

extension [T1, T2, T3, T4, T5, T6, Out](pstmt: Future[ScalaPreparedStatement6[T1, T2, T3, T4, T5, T6, Out]])
    /** An [[Observable]] reading from Cassandra */
    def asObservable(t1: T1, t2: T2, t3: T3, t4: T4, t5: T5, t6: T6)(using CqlSession): Observable[Out] = {
        Observable.fromFuture(pstmt)
            .flatMap(p => Observable.fromReactivePublisher(p.executeReactive(t1, t2, t3, t4, t5, t6)))
    }
end extension

extension [T1, T2, T3, T4, T5, T6, T7, Out](pstmt: Future[ScalaPreparedStatement7[T1, T2, T3, T4, T5, T6, T7, Out]])
    /** An [[Observable]] reading from Cassandra */
    def asObservable(t1: T1, t2: T2, t3: T3, t4: T4, t5: T5, t6: T6, t7: T7)(using CqlSession): Observable[Out] = {
        Observable.fromFuture(pstmt)
            .flatMap(p => Observable.fromReactivePublisher(p.executeReactive(t1, t2, t3, t4, t5, t6, t7)))
    }
end extension

extension [T1, T2, T3, T4, T5, T6, T7, T8, Out](pstmt: Future[ScalaPreparedStatement8[T1, T2, T3, T4, T5, T6, T7, T8, Out]])
    /** An [[Observable]] reading from Cassandra */
    def asObservable(t1: T1, t2: T2, t3: T3, t4: T4, t5: T5, t6: T6, t7: T7, t8: T8)(using CqlSession): Observable[Out] = {
        Observable.fromFuture(pstmt)
            .flatMap(p => Observable.fromReactivePublisher(p.executeReactive(t1, t2, t3, t4, t5, t6, t7, t8)))
    }
end extension

extension [T1, T2, T3, T4, T5, T6, T7, T8, T9, Out](pstmt: Future[ScalaPreparedStatement9[T1, T2, T3, T4, T5, T6, T7, T8, T9, Out]])
    /** An [[Observable]] reading from Cassandra */
    def asObservable(t1: T1, t2: T2, t3: T3, t4: T4, t5: T5, t6: T6, t7: T7, t8: T8, t9: T9)(using CqlSession): Observable[Out] = {
        Observable.fromFuture(pstmt)
            .flatMap(p => Observable.fromReactivePublisher(p.executeReactive(t1, t2, t3, t4, t5, t6, t7, t8, t9)))
    }
end extension

extension [T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, Out](pstmt: Future[ScalaPreparedStatement10[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, Out]])
    /** An [[Observable]] reading from Cassandra */
    def asObservable(t1: T1, t2: T2, t3: T3, t4: T4, t5: T5, t6: T6, t7: T7, t8: T8, t9: T9, t10: T10)(using CqlSession): Observable[Out] = {
        Observable.fromFuture(pstmt)
            .flatMap(p => Observable.fromReactivePublisher(p.executeReactive(t1, t2, t3, t4, t5, t6, t7, t8, t9, t10)))
    }
end extension

extension [T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, Out](pstmt: Future[ScalaPreparedStatement11[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, Out]])
    /** An [[Observable]] reading from Cassandra */
    def asObservable(t1: T1, t2: T2, t3: T3, t4: T4, t5: T5, t6: T6, t7: T7, t8: T8, t9: T9, t10: T10, t11: T11)(using CqlSession): Observable[Out] = {
        Observable.fromFuture(pstmt)
            .flatMap(p => Observable.fromReactivePublisher(p.executeReactive(t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11)))
    }
end extension

extension [T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, Out](pstmt: Future[ScalaPreparedStatement12[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, Out]])
    /** An [[Observable]] reading from Cassandra */
    def asObservable(t1: T1, t2: T2, t3: T3, t4: T4, t5: T5, t6: T6, t7: T7, t8: T8, t9: T9, t10: T10, t11: T11, t12: T12)(using CqlSession): Observable[Out] = {
        Observable.fromFuture(pstmt)
            .flatMap(p => Observable.fromReactivePublisher(p.executeReactive(t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12)))
    }
end extension

extension [T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, Out](pstmt: Future[ScalaPreparedStatement13[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, Out]])
    /** An [[Observable]] reading from Cassandra */
    def asObservable(t1: T1, t2: T2, t3: T3, t4: T4, t5: T5, t6: T6, t7: T7, t8: T8, t9: T9, t10: T10, t11: T11, t12: T12, t13: T13)(using CqlSession): Observable[Out] = {
        Observable.fromFuture(pstmt)
            .flatMap(p => Observable.fromReactivePublisher(p.executeReactive(t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13)))
    }
end extension

extension [T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, Out](pstmt: Future[ScalaPreparedStatement14[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, Out]])
    /** An [[Observable]] reading from Cassandra */
    def asObservable(t1: T1, t2: T2, t3: T3, t4: T4, t5: T5, t6: T6, t7: T7, t8: T8, t9: T9, t10: T10, t11: T11, t12: T12, t13: T13, t14: T14)(using CqlSession): Observable[Out] = {
        Observable.fromFuture(pstmt)
            .flatMap(p => Observable.fromReactivePublisher(p.executeReactive(t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14)))
    }
end extension

extension [T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, Out](pstmt: Future[ScalaPreparedStatement15[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, Out]])
    /** An [[Observable]] reading from Cassandra */
    def asObservable(t1: T1, t2: T2, t3: T3, t4: T4, t5: T5, t6: T6, t7: T7, t8: T8, t9: T9, t10: T10, t11: T11, t12: T12, t13: T13, t14: T14, t15: T15)(using CqlSession): Observable[Out] = {
        Observable.fromFuture(pstmt)
            .flatMap(p => Observable.fromReactivePublisher(p.executeReactive(t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14, t15)))
    }
end extension

extension [T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, Out](pstmt: Future[ScalaPreparedStatement16[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, Out]])
    /** An [[Observable]] reading from Cassandra */
    def asObservable(t1: T1, t2: T2, t3: T3, t4: T4, t5: T5, t6: T6, t7: T7, t8: T8, t9: T9, t10: T10, t11: T11, t12: T12, t13: T13, t14: T14, t15: T15, t16: T16)(using CqlSession): Observable[Out] = {
        Observable.fromFuture(pstmt)
            .flatMap(p => Observable.fromReactivePublisher(p.executeReactive(t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14, t15, t16)))
    }
end extension

extension [T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, Out](pstmt: Future[ScalaPreparedStatement17[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, Out]])
    /** An [[Observable]] reading from Cassandra */
    def asObservable(t1: T1, t2: T2, t3: T3, t4: T4, t5: T5, t6: T6, t7: T7, t8: T8, t9: T9, t10: T10, t11: T11, t12: T12, t13: T13, t14: T14, t15: T15, t16: T16, t17: T17)(using CqlSession): Observable[Out] = {
        Observable.fromFuture(pstmt)
            .flatMap(p => Observable.fromReactivePublisher(p.executeReactive(t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14, t15, t16, t17)))
    }
end extension

extension [T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, Out](pstmt: Future[ScalaPreparedStatement18[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, Out]])
    /** An [[Observable]] reading from Cassandra */
    def asObservable(t1: T1, t2: T2, t3: T3, t4: T4, t5: T5, t6: T6, t7: T7, t8: T8, t9: T9, t10: T10, t11: T11, t12: T12, t13: T13, t14: T14, t15: T15, t16: T16, t17: T17, t18: T18)(using CqlSession): Observable[Out] = {
        Observable.fromFuture(pstmt)
            .flatMap(p => Observable.fromReactivePublisher(p.executeReactive(t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14, t15, t16, t17, t18)))
    }
end extension

extension [T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, Out](pstmt: Future[ScalaPreparedStatement19[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, Out]])
    /** An [[Observable]] reading from Cassandra */
    def asObservable(t1: T1, t2: T2, t3: T3, t4: T4, t5: T5, t6: T6, t7: T7, t8: T8, t9: T9, t10: T10, t11: T11, t12: T12, t13: T13, t14: T14, t15: T15, t16: T16, t17: T17, t18: T18, t19: T19)(using CqlSession): Observable[Out] = {
        Observable.fromFuture(pstmt)
            .flatMap(p => Observable.fromReactivePublisher(p.executeReactive(t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14, t15, t16, t17, t18, t19)))
    }
end extension

extension [T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, T20, Out](pstmt: Future[ScalaPreparedStatement20[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, T20, Out]])
    /** An [[Observable]] reading from Cassandra */
    def asObservable(t1: T1, t2: T2, t3: T3, t4: T4, t5: T5, t6: T6, t7: T7, t8: T8, t9: T9, t10: T10, t11: T11, t12: T12, t13: T13, t14: T14, t15: T15, t16: T16, t17: T17, t18: T18, t19: T19, t20: T20)(using CqlSession): Observable[Out] = {
        Observable.fromFuture(pstmt)
            .flatMap(p => Observable.fromReactivePublisher(p.executeReactive(t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14, t15, t16, t17, t18, t19, t20)))
    }
end extension

extension [T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, T20, T21, Out](pstmt: Future[ScalaPreparedStatement21[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, T20, T21, Out]])
    /** An [[Observable]] reading from Cassandra */
    def asObservable(t1: T1, t2: T2, t3: T3, t4: T4, t5: T5, t6: T6, t7: T7, t8: T8, t9: T9, t10: T10, t11: T11, t12: T12, t13: T13, t14: T14, t15: T15, t16: T16, t17: T17, t18: T18, t19: T19, t20: T20, t21: T21)(using CqlSession): Observable[Out] = {
        Observable.fromFuture(pstmt)
            .flatMap(p => Observable.fromReactivePublisher(p.executeReactive(t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14, t15, t16, t17, t18, t19, t20, t21)))
    }
end extension

extension [T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, T20, T21, T22, Out](pstmt: Future[ScalaPreparedStatement22[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, T20, T21, T22, Out]])
    /** An [[Observable]] reading from Cassandra */
    def asObservable(t1: T1, t2: T2, t3: T3, t4: T4, t5: T5, t6: T6, t7: T7, t8: T8, t9: T9, t10: T10, t11: T11, t12: T12, t13: T13, t14: T14, t15: T15, t16: T16, t17: T17, t18: T18, t19: T19, t20: T20, t21: T21, t22: T22)(using CqlSession): Observable[Out] = {
        Observable.fromFuture(pstmt)
            .flatMap(p => Observable.fromReactivePublisher(p.executeReactive(t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14, t15, t16, t17, t18, t19, t20, t21, t22)))
    }
end extension
// $COVERAGE-ON$
// format: on
