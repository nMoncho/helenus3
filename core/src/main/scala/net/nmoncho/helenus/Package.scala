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

import scala.annotation.targetName
import scala.collection.Factory
import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration
import scala.language.implicitConversions
import scala.reflect.ClassTag
import scala.util.Failure
import scala.util.Success
import scala.util.Try

import com.datastax.dse.driver.api.core.cql.reactive.ReactiveResultSet
import com.datastax.oss.driver.api.core.CqlSession
import com.datastax.oss.driver.api.core.MappedAsyncPagingIterable
import com.datastax.oss.driver.api.core.PagingIterable
import com.datastax.oss.driver.api.core.`type`.codec.TypeCodec
import com.datastax.oss.driver.api.core.`type`.codec.registry.CodecRegistry
import com.datastax.oss.driver.api.core.`type`.codec.registry.MutableCodecRegistry
import com.datastax.oss.driver.api.core.`type`.reflect.GenericType
import com.datastax.oss.driver.api.core.config.DriverExecutionProfile
import com.datastax.oss.driver.api.core.cql.AsyncResultSet
import com.datastax.oss.driver.api.core.cql.BoundStatement
import com.datastax.oss.driver.api.core.cql.PagingState
import com.datastax.oss.driver.api.core.cql.ResultSet
import com.datastax.oss.driver.api.core.cql.Row
import com.datastax.oss.driver.api.core.metadata.schema.KeyspaceMetadata
import net.nmoncho.helenus.api.RowMapper
import net.nmoncho.helenus.api.`type`.codec.Codec
import net.nmoncho.helenus.api.cql.ScalaPreparedStatement.CQLQuery
import net.nmoncho.helenus.api.cql.{ Pager as ApiPager, * }
import net.nmoncho.helenus.internal.cql.*
import net.nmoncho.helenus.internal.macros.CqlQueryInterpolation
import net.nmoncho.helenus.internal.reactive.MapOperator
import org.reactivestreams.Publisher
import org.slf4j.LoggerFactory

given Conversion[CqlSession, Future[CqlSession]] = Future.successful

type TaggedBoundStatement[Out] = { type Tag = Out }
type ScalaBoundStatement[Out]  = BoundStatement & TaggedBoundStatement[Out]

inline private[helenus] def tag[Out](bs: BoundStatement): ScalaBoundStatement[Out] =
    bs.asInstanceOf[ScalaBoundStatement[Out]]

private val log = LoggerFactory.getLogger("net.nmoncho.helenus")

extension (query: String)
    def toCQL(using session: CqlSession): CQLQuery = CQLQuery(query, session)

    def toCQLAsync(using session: Future[CqlSession], ec: ExecutionContext): Future[CQLQuery] =
        session.map(CQLQuery(query, _))

end extension

extension (inline sc: StringContext)

    /** Creates a [[BoundStatement]] using String Interpolation.
      * There is also an asynchronous alternative, which is `cqlAsync` instead of `cql`.
      *
      * This won't execute the bound statement yet, just set its arguments.
      *
      * {{{
      * import net.nmoncho.helenus._
      *
      * val id = UUID.fromString("...")
      * val bstmt = cql"SELECT * FROM some_table WHERE id = $id"
      * }}}
      */
    inline def cql(inline args: Any*)(using inline session: CqlSession): WrappedBoundStatement[Row] =
        ${ CqlQueryInterpolation.cqlImpl('sc, 'args, 'session) }

    /** Creates a [[BoundStatement]] asynchronous using String Interpolation.
      * There is also a synchronous alternative, which is `cql` instead of `cqlAsync`.
      *
      * This won't execute the bound statement yet, just set its arguments.
      *
      * {{{
      * import net.nmoncho.helenus._
      *
      * val id = UUID.fromString("...")
      * val bstmt = cqlAsync"SELECT * FROM some_table WHERE id = $id"
      * }}}
      */
    inline def cqlAsync(inline args: Any*)(
        using inline session: CqlSession,
        inline ec: ExecutionContext
    ): Future[WrappedBoundStatement[Row]] =
        ${ CqlQueryInterpolation.cqlAsyncImpl('sc, 'args, 'session, 'ec) }

end extension

extension (bs: BoundStatement)
    /** Sets or binds the specified value only if it's not NULL, avoiding a tombstone insert.
      *
      * @param index position of bound parameter
      * @param value value to be bound
      * @param codec how to encode the provided value
      * @tparam T
      * @return a modified version of this [[BoundStatement]]
      */
    inline def setIfDefined[T](index: Int, value: T, codec: TypeCodec[T]): BoundStatement =
        if value == null || value == None then bs else bs.set(index, value, codec)

extension [Out](bstmt: ScalaBoundStatement[Out])

    /** Executes this CQL Statement synchronously
      */
    def execute()(using session: CqlSession, mapper: RowMapper[Out]): PagingIterable[Out] =
        session.execute(bstmt).as[Out]

    /** Executes this CQL Statement asynchronously
      */
    def executeAsync()(
        using session: CqlSession,
        ec: ExecutionContext,
        mapper: RowMapper[Out]
    ): Future[MappedAsyncPagingIterable[Out]] =
        import scala.jdk.FutureConverters.CompletionStageOps

        session.executeAsync(bstmt).asScala.map(_.as[Out])
    end executeAsync

    /** Returns a [[Publisher]] that, once subscribed to, executes the given query and emits all
      * the results.
      */
    def executeReactive()(using session: CqlSession, mapper: RowMapper[Out]): Publisher[Out] =
        session.executeReactive(bstmt).as[Out]

    /** Creates an initial [[Pager]] for this CQL statement
      */
    def pager(implicit using: RowMapper[Out]): Pager[Out] =
        Pager.initial(bstmt)

    /** Creates an continued [[Pager]] for this CQL statement from a [[PagingState]]
      */
    def pager(pagingState: PagingState)(using using: RowMapper[Out]): Try[Pager[Out]] =
        Pager.continue(bstmt, pagingState)

    /** Creates an continued [[Pager]] for this CQL statement from a [[PagingState]]
      */
    def pager[A: PagerSerializer](pagingState: A)(
        using mapper: RowMapper[Out]
    ): Try[Pager[Out]] =
        Pager.continueFromEncoded(bstmt, pagingState)

    /** Set options to this [[BoundStatement]] while returning the original type
      */
    def withOptions(fn: BoundStatement => BoundStatement): ScalaBoundStatement[Out] =
        fn(bstmt).asInstanceOf[ScalaBoundStatement[Out]]

    /** Set options to this [[BoundStatement]] while returning the original type
      */
    def withOptions(options: StatementOptions): ScalaBoundStatement[Out] =
        options(bstmt).asInstanceOf[ScalaBoundStatement[Out]]
end extension

extension [Out](bstmt: Future[ScalaBoundStatement[Out]])

    /** Executes this CQL Statement synchronously
      */
    def execute()(
        using session: Future[CqlSession],
        ec: ExecutionContext,
        mapper: RowMapper[Out]
    ): Future[PagingIterable[Out]] =
        session.flatMap { case s @ given CqlSession =>
            bstmt.map(b => s.execute(b).as[Out])
        }

    /** Executes this CQL Statement asynchronously
      */
    def executeAsync()(
        using session: Future[CqlSession],
        ec: ExecutionContext,
        mapper: RowMapper[Out]
    ): Future[MappedAsyncPagingIterable[Out]] =
        import scala.jdk.FutureConverters.CompletionStageOps

        session.flatMap { case s @ given CqlSession =>
            bstmt.flatMap(b => s.executeAsync(b).asScala.map(_.as[Out]))
        }
    end executeAsync

    /** Returns a [[Publisher]] that, once subscribed to, executes the given query and emits all
      * the results.
      */
    def executeReactive()(
        using session: Future[CqlSession],
        ec: ExecutionContext,
        mapper: RowMapper[Out]
    ): Future[Publisher[Out]] =
        session.flatMap { case s @ given CqlSession =>
            bstmt.map(b => s.executeReactive(b).as[Out])
        }

    /** Creates an initial [[Pager]] for this CQL statement
      */
    def pager(using ec: ExecutionContext, mapper: RowMapper[Out]): Future[Pager[Out]] =
        bstmt.map(b => Pager.initial(b))

    /** Creates an continued [[Pager]] for this CQL statement from a [[PagingState]]
      */
    def pager(
        pagingState: PagingState
    )(using ec: ExecutionContext, mapper: RowMapper[Out]): Future[Pager[Out]] =
        bstmt.flatMap(_.pager(pagingState) match
            case Success(value) => Future.successful(value)
            case Failure(exception) => Future.failed(exception)
        )

    /** Creates an continued [[Pager]] for this CQL statement from a [[PagingState]]
      */
    def pager[A: PagerSerializer](pagingState: A)(
        using ec: ExecutionContext,
        mapper: RowMapper[Out]
    ): Future[Pager[Out]] =
        bstmt.flatMap(_.pager[A](pagingState) match
            case Success(value) => Future.successful(value)
            case Failure(exception) => Future.failed(exception)
        )

    /** Set options to this [[BoundStatement]] while returning the original type
      */
    def withOptions(fn: BoundStatement => BoundStatement)(using ExecutionContext): Future[ScalaBoundStatement[Out]] =
        bstmt.map(b => fn(b).asInstanceOf[ScalaBoundStatement[Out]])

    /** Set options to this [[BoundStatement]] while returning the original type
      */
    def withOptions(options: StatementOptions)(using ExecutionContext): Future[ScalaBoundStatement[Out]] =
        bstmt.map(b => options(b).asInstanceOf[ScalaBoundStatement[Out]])
end extension

extension [Out](future: Future[WrappedBoundStatement[Out]])
    /** Maps the result from this [[BoundStatement]] with a different [[Out2]]
      * as long as there is an implicit [[RowMapper]] and [[Out]] is [[Row]] (this is
      * meant to avoid calling `as` twice)
      */
    def as[Out2: RowMapper](
        using ev: Out =:= Row,
        ec: ExecutionContext
    ): Future[WrappedBoundStatement[Out2]] = future.map(_.as[Out2])

    /** Executes this [[BoundStatement]] in a asynchronous fashion
      *
      * @return a future of [[MappedAsyncPagingIterable]]
      */
    def executeAsync()(
        implicit session: CqlSession,
        ec: ExecutionContext
    ): Future[MappedAsyncPagingIterable[Out]] = future.flatMap(_.executeAsync())
end extension

extension (row: Row)
    /** Converts a [[Row]] into a [[T]]
      */
    def as[T](using mapper: RowMapper[T]): T = mapper.apply(row)

    /** Gets a column from this [[Row]] of type [[T]] by name
      *
      * @param name column name
      */
    def getCol[T](name: String)(using codec: Codec[T]): T = row.get(name, codec)

    /** Gets a column from this [[Row]] of type [[T]] by index
      *
      * @param index position in row
      */
    def getCol[T](index: Int)(using codec: Codec[T]): T = row.get(index, codec)
end extension

extension (rs: ResultSet)
    /** Converts a [[ResultSet]] into a [[PagingIterable]] of type [[T]]
      */
    def as[T](using mapper: RowMapper[T]): PagingIterable[T] =
        rs.map(mapper.apply)

extension (rs: AsyncResultSet)
    /** Converts a [[AsyncResultSet]] into a [[MappedAsyncPagingIterable]] of type [[T]]
      */
    def as[T](using mapper: RowMapper[T]): MappedAsyncPagingIterable[T] =
        rs.map(mapper.apply)

extension (rs: ReactiveResultSet)
    /** Converts a [[ReactiveResultSet]] into a [[Publisher]] of type [[T]]
      */
    def as[T](using mapper: RowMapper[T]): Publisher[T] =
        val op = new MapOperator(rs, mapper.apply)

        op.publisher

extension [T](pi: PagingIterable[T])

    /** Next potential element of this iterable
      */
    def nextOption(): Option[T] = Option(pi.one())

    /** This [[PagingIterable]] as a Scala [[Iterator]]
      */
    def iter: Iterator[T] =
        import scala.jdk.CollectionConverters.IteratorHasAsScala
        pi.iterator().asScala

    /** This [[PagingIterable]] as a Scala Collection; <b>not recommended for queries that return a
      * large number of elements</b>.
      *
      * Example
      * {{{
      *   pagingIterable.to(List)
      *   pagingIterable.to(Set)
      * }}}
      */
    def to[Col[_]](factory: Factory[T, Col[T]]): Col[T] =
        iter.to(factory)
end extension

extension [T](pi: MappedAsyncPagingIterable[T])
    /** Current page as a Scala [[Iterator]]
      */
    def currPage: Iterator[T] =
        import scala.jdk.CollectionConverters.IteratorHasAsScala
        pi.currentPage().iterator().asScala

    /** Returns the next element from the results.
      *
      * Use this method when you <em>only<em> care about the first element.
      *
      * Unlike [[nextOption]], this method doesn't expose the mutated [[MappedAsyncPagingIterable]],
      * so it's not meant for iterating through all results.
      *
      * @return [[Some]] value if results haven't been exhausted, [[None]] otherwise
      */
    def oneOption: Option[T] = Option(pi.one())

    /** Fetches and returns the next page as a Scala [[Iterator]]
      */
    def nextPage()(
        using ec: ExecutionContext
    ): Future[Option[(Iterator[T], MappedAsyncPagingIterable[T])]] =
        import scala.jdk.FutureConverters.CompletionStageOps

        if pi.hasMorePages then
            pi.fetchNextPage().asScala.map(pi => Some(pi.currPage -> pi))
        else
            Future.successful(None)
    end nextPage

    /** Returns the next element from the results.
      *
      * It also returns the [[MappedAsyncPagingIterable]] that should be used next, since this could be the
      * last element from the page. A [[MappedAsyncPagingIterable]] effectively represents a pagination mechanism
      *
      * This is convenient for queries that are known to return exactly one element, for example
      * count queries.
      *
      * @return [[Some]] value if results haven't been exhausted, [[None]] otherwise
      */
    def nextOption()(
        using ec: ExecutionContext
    ): Future[Option[(T, MappedAsyncPagingIterable[T])]] =
        import scala.jdk.FutureConverters.CompletionStageOps

        val fromCurrentPage = pi.one()

        if fromCurrentPage != null then
            Future.successful(Some(fromCurrentPage -> pi))
        else if pi.hasMorePages then
            pi.fetchNextPage().asScala.map(nextPage => Option(nextPage.one()).map(_ -> nextPage))
        else
            Future.successful(None)
        end if
    end nextOption

    /** Returns the next element from the results
      *
      * It also returns the [[MappedAsyncPagingIterable]] that should be used next, since this could be the
      * last element from the page. A [[MappedAsyncPagingIterable]] effectively represents a pagination mechanism
      *
      * This is convenient for queries that are known to return exactly one element, for example
      * count queries.
      *
      * It will fetch the next page in a blocking fashion after it has exhausted the current page.
      *
      * @param timeout how much time to wait for the next page to be ready
      * @return [[Some]] value if results haven't been exhausted, [[None]] otherwise
      */
    def nextOption(
        timeout: FiniteDuration
    )(using ec: ExecutionContext): Option[(T, MappedAsyncPagingIterable[T])] =
        import scala.jdk.FutureConverters.CompletionStageOps

        val fromCurrentPage = pi.one()

        if fromCurrentPage != null then Some(fromCurrentPage -> pi)
        else if pi.hasMorePages then
            log.debug("Fetching more pages for request [{}]", pi.getExecutionInfo.getRequest)
            Await.result(
              pi.fetchNextPage().asScala.map(nextPage => Option(nextPage.one()).map(_ -> nextPage)),
              timeout
            )
        else None
        end if
    end nextOption

    /** Return all results of this [[MappedAsyncPagingIterable]] as a Scala [[Iterator]],
      * without having to request more pages.
      *
      * It will fetch the next page in a blocking fashion after it has exhausted the current page.
      *
      * @param timeout how much time to wait for the next page to be ready
      * @param ec
      */
    def iter(timeout: FiniteDuration)(using ec: ExecutionContext): Iterator[T] =
        import scala.jdk.CollectionConverters.IteratorHasAsScala
        import scala.jdk.FutureConverters.CompletionStageOps

        def concat(current: MappedAsyncPagingIterable[T]): IterableOnce[T] =
            current
                .currentPage()
                .iterator()
                .asScala
                .concat:
                    if current.hasMorePages() then
                        log.debug("fetching more pages")
                        val next = Await.result(current.fetchNextPage().asScala, timeout)

                        concat(next)
                    else
                        log.debug("no more pages")
                        Iterable.empty

        concat(pi).iterator
    end iter
end extension

extension (session: CqlSession)

    def toAsync: Future[CqlSession] =
        Future.successful(session)

    def sessionKeyspace: Option[KeyspaceMetadata] =
        val opt: java.util.Optional[String] = session.getKeyspace().map(_.asInternal())

        if opt.isPresent() then keyspace(opt.get())
        else None
    end sessionKeyspace

    def keyspace(name: String): Option[KeyspaceMetadata] =
        val opt = session.getMetadata().getKeyspace(name)

        if opt.isPresent() then Some(opt.get())
        else None
    end keyspace

    def executionProfile(name: String): Option[DriverExecutionProfile] =
        Try(session.getContext.getConfig.getProfile(name)).recoverWith { case t: Throwable =>
            log.warn("Couldn't find execution profile with name [{}]", name, t: Any)
            Failure(t)
        }.toOption

    def registerCodecs(codecs: TypeCodec[?]*): Try[Unit] =
        session.getContext.getCodecRegistry match
            case mutableRegistry: MutableCodecRegistry =>
                Try:
                    val keyspace = session.sessionKeyspace

                    codecs.foreach:
                        case codec =>
                            mutableRegistry.register(codec)

            // $COVERAGE-OFF$
            case _ =>
                Failure(new IllegalStateException("CodecRegistry isn't mutable"))
            // $COVERAGE-ON$
end extension

// format: off
extension (cql: Future[CQLQuery])
    def prepareUnit(using ExecutionContext): Future[ScalaPreparedStatementUnit[Row]] = cql.map(_.prepareUnit)

    // def prepareFrom[T1: Mapping](using ExecutionContext): Future[ScalaPreparedStatementMapped[T1, Row]] = cql.map(_.prepareFrom[T1])

    def prepare[T1: Codec](using ExecutionContext): Future[ScalaPreparedStatement1[T1, Row]] = cql.map(_.prepare[T1])

    def prepare[T1: Codec, T2: Codec](using ExecutionContext): Future[ScalaPreparedStatement2[T1, T2, Row]] = cql.map(_.prepare[T1, T2])

    def prepare[T1: Codec, T2: Codec, T3: Codec](using ExecutionContext): Future[ScalaPreparedStatement3[T1, T2, T3, Row]] = cql.map(_.prepare[T1, T2, T3])

    def prepare[T1: Codec, T2: Codec, T3: Codec, T4: Codec](using ExecutionContext): Future[ScalaPreparedStatement4[T1, T2, T3, T4, Row]] = cql.map(_.prepare[T1, T2, T3, T4])

    def prepare[T1: Codec, T2: Codec, T3: Codec, T4: Codec, T5: Codec](using ExecutionContext): Future[ScalaPreparedStatement5[T1, T2, T3, T4, T5, Row]] = cql.map(_.prepare[T1, T2, T3, T4, T5])

    def prepare[T1: Codec, T2: Codec, T3: Codec, T4: Codec, T5: Codec, T6: Codec](using ExecutionContext): Future[ScalaPreparedStatement6[T1, T2, T3, T4, T5, T6, Row]] = cql.map(_.prepare[T1, T2, T3, T4, T5, T6])

    def prepare[T1: Codec, T2: Codec, T3: Codec, T4: Codec, T5: Codec, T6: Codec, T7: Codec](using ExecutionContext): Future[ScalaPreparedStatement7[T1, T2, T3, T4, T5, T6, T7, Row]] = cql.map(_.prepare[T1, T2, T3, T4, T5, T6, T7])

    def prepare[T1: Codec, T2: Codec, T3: Codec, T4: Codec, T5: Codec, T6: Codec, T7: Codec, T8: Codec](using ExecutionContext): Future[ScalaPreparedStatement8[T1, T2, T3, T4, T5, T6, T7, T8, Row]] = cql.map(_.prepare[T1, T2, T3, T4, T5, T6, T7, T8])

    def prepare[T1: Codec, T2: Codec, T3: Codec, T4: Codec, T5: Codec, T6: Codec, T7: Codec, T8: Codec, T9: Codec](using ExecutionContext): Future[ScalaPreparedStatement9[T1, T2, T3, T4, T5, T6, T7, T8, T9, Row]] = cql.map(_.prepare[T1, T2, T3, T4, T5, T6, T7, T8, T9])

    def prepare[T1: Codec, T2: Codec, T3: Codec, T4: Codec, T5: Codec, T6: Codec, T7: Codec, T8: Codec, T9: Codec, T10: Codec](using ExecutionContext): Future[ScalaPreparedStatement10[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, Row]] = cql.map(_.prepare[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10])

    def prepare[T1: Codec, T2: Codec, T3: Codec, T4: Codec, T5: Codec, T6: Codec, T7: Codec, T8: Codec, T9: Codec, T10: Codec, T11: Codec](using ExecutionContext): Future[ScalaPreparedStatement11[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, Row]] = cql.map(_.prepare[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11])

    def prepare[T1: Codec, T2: Codec, T3: Codec, T4: Codec, T5: Codec, T6: Codec, T7: Codec, T8: Codec, T9: Codec, T10: Codec, T11: Codec, T12: Codec](using ExecutionContext): Future[ScalaPreparedStatement12[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, Row]] = cql.map(_.prepare[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12])

    def prepare[T1: Codec, T2: Codec, T3: Codec, T4: Codec, T5: Codec, T6: Codec, T7: Codec, T8: Codec, T9: Codec, T10: Codec, T11: Codec, T12: Codec, T13: Codec](using ExecutionContext): Future[ScalaPreparedStatement13[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, Row]] = cql.map(_.prepare[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13])

    def prepare[T1: Codec, T2: Codec, T3: Codec, T4: Codec, T5: Codec, T6: Codec, T7: Codec, T8: Codec, T9: Codec, T10: Codec, T11: Codec, T12: Codec, T13: Codec, T14: Codec](using ExecutionContext): Future[ScalaPreparedStatement14[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, Row]] = cql.map(_.prepare[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14])

    def prepare[T1: Codec, T2: Codec, T3: Codec, T4: Codec, T5: Codec, T6: Codec, T7: Codec, T8: Codec, T9: Codec, T10: Codec, T11: Codec, T12: Codec, T13: Codec, T14: Codec, T15: Codec](using ExecutionContext): Future[ScalaPreparedStatement15[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, Row]] = cql.map(_.prepare[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15])

    def prepare[T1: Codec, T2: Codec, T3: Codec, T4: Codec, T5: Codec, T6: Codec, T7: Codec, T8: Codec, T9: Codec, T10: Codec, T11: Codec, T12: Codec, T13: Codec, T14: Codec, T15: Codec, T16: Codec](using ExecutionContext): Future[ScalaPreparedStatement16[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, Row]] = cql.map(_.prepare[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16])

    def prepare[T1: Codec, T2: Codec, T3: Codec, T4: Codec, T5: Codec, T6: Codec, T7: Codec, T8: Codec, T9: Codec, T10: Codec, T11: Codec, T12: Codec, T13: Codec, T14: Codec, T15: Codec, T16: Codec, T17: Codec](using ExecutionContext): Future[ScalaPreparedStatement17[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, Row]] = cql.map(_.prepare[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17])

    def prepare[T1: Codec, T2: Codec, T3: Codec, T4: Codec, T5: Codec, T6: Codec, T7: Codec, T8: Codec, T9: Codec, T10: Codec, T11: Codec, T12: Codec, T13: Codec, T14: Codec, T15: Codec, T16: Codec, T17: Codec, T18: Codec](using ExecutionContext): Future[ScalaPreparedStatement18[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, Row]] = cql.map(_.prepare[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18])

    def prepare[T1: Codec, T2: Codec, T3: Codec, T4: Codec, T5: Codec, T6: Codec, T7: Codec, T8: Codec, T9: Codec, T10: Codec, T11: Codec, T12: Codec, T13: Codec, T14: Codec, T15: Codec, T16: Codec, T17: Codec, T18: Codec, T19: Codec](using ExecutionContext): Future[ScalaPreparedStatement19[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, Row]] = cql.map(_.prepare[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19])

    def prepare[T1: Codec, T2: Codec, T3: Codec, T4: Codec, T5: Codec, T6: Codec, T7: Codec, T8: Codec, T9: Codec, T10: Codec, T11: Codec, T12: Codec, T13: Codec, T14: Codec, T15: Codec, T16: Codec, T17: Codec, T18: Codec, T19: Codec, T20: Codec](using ExecutionContext): Future[ScalaPreparedStatement20[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, T20, Row]] = cql.map(_.prepare[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, T20])

    def prepare[T1: Codec, T2: Codec, T3: Codec, T4: Codec, T5: Codec, T6: Codec, T7: Codec, T8: Codec, T9: Codec, T10: Codec, T11: Codec, T12: Codec, T13: Codec, T14: Codec, T15: Codec, T16: Codec, T17: Codec, T18: Codec, T19: Codec, T20: Codec, T21: Codec](using ExecutionContext): Future[ScalaPreparedStatement21[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, T20, T21, Row]] = cql.map(_.prepare[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, T20, T21])

    def prepare[T1: Codec, T2: Codec, T3: Codec, T4: Codec, T5: Codec, T6: Codec, T7: Codec, T8: Codec, T9: Codec, T10: Codec, T11: Codec, T12: Codec, T13: Codec, T14: Codec, T15: Codec, T16: Codec, T17: Codec, T18: Codec, T19: Codec, T20: Codec, T21: Codec, T22: Codec](using ExecutionContext): Future[ScalaPreparedStatement22[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, T20, T21, T22, Row]] = cql.map(_.prepare[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, T20, T21, T22])
end extension

// extension [In2, In, Out](fut: Future[AdaptedScalaPreparedStatement[In2, In, Out]])
//     def as[Out2](using ec: ExecutionContext, mapper: RowMapper[Out2], ev: Out =:= Row): Future[AdaptedScalaPreparedStatement[In2, In, Out2]] = fut.map(_.as[Out2])

//     def as[Out2](mapper: RowMapper[Out2])(using ec: ExecutionContext, ev: Out =:= Row): Future[AdaptedScalaPreparedStatement[In2, In, Out2]] = fut.map(_.as[Out2](ev, mapper))

//     def executeAsync(t1: In2)(using cqlSession: Future[CqlSession], ec: ExecutionContext): Future[MappedAsyncPagingIterable[Out]] = cqlSession.flatMap {using s => fut.flatMap(_.executeAsync(t1))}

//     def pager(t1: In2)(using ec: ExecutionContext): Future[ApiPager[Out]] = fut.map(_.pager(t1))

//     def pager(pagingState: PagingState, t1: In2)(using ec: ExecutionContext): Future[ApiPager[Out]] = fut.map(_.pager(pagingState, t1).get)

//     def pager[A: PagerSerializer](pagingState: A, t1: In2)(using ec: ExecutionContext): Future[ApiPager[Out]] = fut.map(_.pager(pagingState, t1).get)
// end extension

extension [Out](fut: Future[ScalaPreparedStatementUnit[Out]])
    @targetName("future_scala_pstmt_unit_as_out2")
    def as[Out2](using ec: ExecutionContext, mapper: RowMapper[Out2], ev: Out =:= Row): Future[ScalaPreparedStatementUnit[Out2]] = fut.map(_.as[Out2])

    @targetName("future_scala_pstmt_unit_as_out2_explicit_mapper")
    def as[Out2](mapper: RowMapper[Out2])(using ec: ExecutionContext, ev: Out =:= Row): Future[ScalaPreparedStatementUnit[Out2]] = fut.map(_.as[Out2](ev, mapper))

    def executeAsync()(using cqlSession: Future[CqlSession], ec: ExecutionContext): Future[MappedAsyncPagingIterable[Out]] = cqlSession.flatMap {s => fut.flatMap(_.executeAsync()(using s))}

    def pager()(using ec: ExecutionContext): Future[ApiPager[Out]] = fut.map(_.pager())

    def pager(pagingState: PagingState)(using ec: ExecutionContext): Future[ApiPager[Out]] = fut.map(_.pager(pagingState).get)

    def pager[A: PagerSerializer](pagingState: A)(using ec: ExecutionContext): Future[ApiPager[Out]] = fut.map(_.pager(pagingState).get)
end extension

extension [T1, Out](fut: Future[ScalaPreparedStatement1[T1, Out]])
    @targetName("future_scala_pstmt_1_as_out2")
    def as[Out2](using ec: ExecutionContext, mapper: RowMapper[Out2], ev: Out =:= Row): Future[ScalaPreparedStatement1[T1, Out2]] = fut.map(_.as[Out2])

    @targetName("future_scala_pstmt_1_as_out2_explicit_mapper")
    def as[Out2](mapper: RowMapper[Out2])(using ec: ExecutionContext, ev: Out =:= Row): Future[ScalaPreparedStatement1[T1, Out2]] = fut.map(_.as[Out2](ev, mapper))

    def executeAsync(t1: T1)(using cqlSession: Future[CqlSession], ec: ExecutionContext): Future[MappedAsyncPagingIterable[Out]] = cqlSession.flatMap {s => fut.flatMap(_.executeAsync(t1)(using s))}

    def pager(t1: T1)(using ec: ExecutionContext): Future[ApiPager[Out]] = fut.map(_.pager(t1))

    def pager(pagingState: PagingState, t1: T1)(using ec: ExecutionContext): Future[ApiPager[Out]] = fut.map(_.pager(pagingState, t1).get)

    def pager[A: PagerSerializer](pagingState: A, t1: T1)(using ec: ExecutionContext): Future[ApiPager[Out]] = fut.map(_.pager(pagingState, t1).get)
end extension

// extension [T1, Out](fut: Future[ScalaPreparedStatementMapped[T1, Out]])
//     def as[Out2](using ec: ExecutionContext, mapper: RowMapper[Out2], ev: Out =:= Row): Future[ScalaPreparedStatementMapped[T1, Out2]] = fut.map(_.as[Out2])

//     def as[Out2](mapper: RowMapper[Out2])(using ec: ExecutionContext, ev: Out =:= Row): Future[ScalaPreparedStatementMapped[T1, Out2]] = fut.map(_.as[Out2](ev, mapper))

//     def executeAsync(t1: T1)(using cqlSession: Future[CqlSession], ec: ExecutionContext): Future[MappedAsyncPagingIterable[Out]] = cqlSession.flatMap {using s => fut.flatMap(_.executeAsync(t1))}

//     def pager(t1: T1)(using ec: ExecutionContext): Future[ApiPager[Out]] = fut.map(_.pager(t1))

//     def pager(pagingState: PagingState, t1: T1)(using ec: ExecutionContext): Future[ApiPager[Out]] = fut.map(_.pager(pagingState, t1).get)

//     def pager[A: PagerSerializer](pagingState: A, t1: T1)(using ec: ExecutionContext): Future[ApiPager[Out]] = fut.map(_.pager(pagingState, t1).get)
// end extension

extension [T1, T2, Out](fut: Future[ScalaPreparedStatement2[T1, T2, Out]])
    @targetName("future_scala_pstmt_2_as_out2")
    def as[Out2](using ec: ExecutionContext, mapper: RowMapper[Out2], ev: Out =:= Row): Future[ScalaPreparedStatement2[T1, T2, Out2]] = fut.map(_.as[Out2])

    @targetName("future_scala_pstmt_2_as_out2_explicit_mapper")
    def as[Out2](mapper: RowMapper[Out2])(using ec: ExecutionContext, ev: Out =:= Row): Future[ScalaPreparedStatement2[T1, T2, Out2]] = fut.map(_.as[Out2](ev, mapper))

    def executeAsync(t1: T1, t2: T2)(using cqlSession: Future[CqlSession], ec: ExecutionContext): Future[MappedAsyncPagingIterable[Out]] = cqlSession.flatMap {s => fut.flatMap(_.executeAsync(t1, t2)(using s))}

    def pager(t1: T1, t2: T2)(using ec: ExecutionContext): Future[ApiPager[Out]] = fut.map(_.pager(t1, t2))

    def pager(pagingState: PagingState, t1: T1, t2: T2)(using ec: ExecutionContext): Future[ApiPager[Out]] = fut.map(_.pager(pagingState, t1, t2).get)

    def pager[A: PagerSerializer](pagingState: A, t1: T1, t2: T2)(using ec: ExecutionContext): Future[ApiPager[Out]] = fut.map(_.pager(pagingState, t1, t2).get)
end extension

extension [T1, T2, T3, Out](fut: Future[ScalaPreparedStatement3[T1, T2, T3, Out]])
    @targetName("future_scala_pstmt_3_as_out2")
    def as[Out2](using ec: ExecutionContext, mapper: RowMapper[Out2], ev: Out =:= Row): Future[ScalaPreparedStatement3[T1, T2, T3, Out2]] = fut.map(_.as[Out2])

    @targetName("future_scala_pstmt_3_as_out2_explicit_mapper")
    def as[Out2](mapper: RowMapper[Out2])(using ec: ExecutionContext, ev: Out =:= Row): Future[ScalaPreparedStatement3[T1, T2, T3, Out2]] = fut.map(_.as[Out2](ev, mapper))

    def executeAsync(t1: T1, t2: T2, t3: T3)(using cqlSession: Future[CqlSession], ec: ExecutionContext): Future[MappedAsyncPagingIterable[Out]] = cqlSession.flatMap {s => fut.flatMap(_.executeAsync(t1, t2, t3)(using s))}

    def pager(t1: T1, t2: T2, t3: T3)(using ec: ExecutionContext): Future[ApiPager[Out]] = fut.map(_.pager(t1, t2, t3))

    def pager(pagingState: PagingState, t1: T1, t2: T2, t3: T3)(using ec: ExecutionContext): Future[ApiPager[Out]] = fut.map(_.pager(pagingState, t1, t2, t3).get)

    def pager[A: PagerSerializer](pagingState: A, t1: T1, t2: T2, t3: T3)(using ec: ExecutionContext): Future[ApiPager[Out]] = fut.map(_.pager(pagingState, t1, t2, t3).get)
end extension

extension [T1, T2, T3, T4, Out](fut: Future[ScalaPreparedStatement4[T1, T2, T3, T4, Out]])
    @targetName("future_scala_pstmt_4_as_out2")
    def as[Out2](using ec: ExecutionContext, mapper: RowMapper[Out2], ev: Out =:= Row): Future[ScalaPreparedStatement4[T1, T2, T3, T4, Out2]] = fut.map(_.as[Out2])

    @targetName("future_scala_pstmt_4_as_out2_explicit_mapper")
    def as[Out2](mapper: RowMapper[Out2])(using ec: ExecutionContext, ev: Out =:= Row): Future[ScalaPreparedStatement4[T1, T2, T3, T4, Out2]] = fut.map(_.as[Out2](ev, mapper))

    def executeAsync(t1: T1, t2: T2, t3: T3, t4: T4)(using cqlSession: Future[CqlSession], ec: ExecutionContext): Future[MappedAsyncPagingIterable[Out]] = cqlSession.flatMap {s => fut.flatMap(_.executeAsync(t1, t2, t3, t4)(using s))}

    def pager(t1: T1, t2: T2, t3: T3, t4: T4)(using ec: ExecutionContext): Future[ApiPager[Out]] = fut.map(_.pager(t1, t2, t3, t4))

    def pager(pagingState: PagingState, t1: T1, t2: T2, t3: T3, t4: T4)(using ec: ExecutionContext): Future[ApiPager[Out]] = fut.map(_.pager(pagingState, t1, t2, t3, t4).get)

    def pager[A: PagerSerializer](pagingState: A, t1: T1, t2: T2, t3: T3, t4: T4)(using ec: ExecutionContext): Future[ApiPager[Out]] = fut.map(_.pager(pagingState, t1, t2, t3, t4).get)
end extension

extension [T1, T2, T3, T4, T5, Out](fut: Future[ScalaPreparedStatement5[T1, T2, T3, T4, T5, Out]])
    @targetName("future_scala_pstmt_5_as_out2")
    def as[Out2](using ec: ExecutionContext, mapper: RowMapper[Out2], ev: Out =:= Row): Future[ScalaPreparedStatement5[T1, T2, T3, T4, T5, Out2]] = fut.map(_.as[Out2])

    @targetName("future_scala_pstmt_5_as_out2_explicit_mapper")
    def as[Out2](mapper: RowMapper[Out2])(using ec: ExecutionContext, ev: Out =:= Row): Future[ScalaPreparedStatement5[T1, T2, T3, T4, T5, Out2]] = fut.map(_.as[Out2](ev, mapper))

    def executeAsync(t1: T1, t2: T2, t3: T3, t4: T4, t5: T5)(using cqlSession: Future[CqlSession], ec: ExecutionContext): Future[MappedAsyncPagingIterable[Out]] = cqlSession.flatMap {s => fut.flatMap(_.executeAsync(t1, t2, t3, t4, t5)(using s))}

    def pager(t1: T1, t2: T2, t3: T3, t4: T4, t5: T5)(using ec: ExecutionContext): Future[ApiPager[Out]] = fut.map(_.pager(t1, t2, t3, t4, t5))

    def pager(pagingState: PagingState, t1: T1, t2: T2, t3: T3, t4: T4, t5: T5)(using ec: ExecutionContext): Future[ApiPager[Out]] = fut.map(_.pager(pagingState, t1, t2, t3, t4, t5).get)

    def pager[A: PagerSerializer](pagingState: A, t1: T1, t2: T2, t3: T3, t4: T4, t5: T5)(using ec: ExecutionContext): Future[ApiPager[Out]] = fut.map(_.pager(pagingState, t1, t2, t3, t4, t5).get)
end extension

extension [T1, T2, T3, T4, T5, T6, Out](fut: Future[ScalaPreparedStatement6[T1, T2, T3, T4, T5, T6, Out]])
    @targetName("future_scala_pstmt_6_as_out2")
    def as[Out2](using ec: ExecutionContext, mapper: RowMapper[Out2], ev: Out =:= Row): Future[ScalaPreparedStatement6[T1, T2, T3, T4, T5, T6, Out2]] = fut.map(_.as[Out2])

    @targetName("future_scala_pstmt_6_as_out2_explicit_mapper")
    def as[Out2](mapper: RowMapper[Out2])(using ec: ExecutionContext, ev: Out =:= Row): Future[ScalaPreparedStatement6[T1, T2, T3, T4, T5, T6, Out2]] = fut.map(_.as[Out2](ev, mapper))

    def executeAsync(t1: T1, t2: T2, t3: T3, t4: T4, t5: T5, t6: T6)(using cqlSession: Future[CqlSession], ec: ExecutionContext): Future[MappedAsyncPagingIterable[Out]] = cqlSession.flatMap {s => fut.flatMap(_.executeAsync(t1, t2, t3, t4, t5, t6)(using s))}

    def pager(t1: T1, t2: T2, t3: T3, t4: T4, t5: T5, t6: T6)(using ec: ExecutionContext): Future[ApiPager[Out]] = fut.map(_.pager(t1, t2, t3, t4, t5, t6))

    def pager(pagingState: PagingState, t1: T1, t2: T2, t3: T3, t4: T4, t5: T5, t6: T6)(using ec: ExecutionContext): Future[ApiPager[Out]] = fut.map(_.pager(pagingState, t1, t2, t3, t4, t5, t6).get)

    def pager[A: PagerSerializer](pagingState: A, t1: T1, t2: T2, t3: T3, t4: T4, t5: T5, t6: T6)(using ec: ExecutionContext): Future[ApiPager[Out]] = fut.map(_.pager(pagingState, t1, t2, t3, t4, t5, t6).get)
end extension

extension [T1, T2, T3, T4, T5, T6, T7, Out](fut: Future[ScalaPreparedStatement7[T1, T2, T3, T4, T5, T6, T7, Out]])
    @targetName("future_scala_pstmt_7_as_out2")
    def as[Out2](using ec: ExecutionContext, mapper: RowMapper[Out2], ev: Out =:= Row): Future[ScalaPreparedStatement7[T1, T2, T3, T4, T5, T6, T7, Out2]] = fut.map(_.as[Out2])

    @targetName("future_scala_pstmt_7_as_out2_explicit_mapper")
    def as[Out2](mapper: RowMapper[Out2])(using ec: ExecutionContext, ev: Out =:= Row): Future[ScalaPreparedStatement7[T1, T2, T3, T4, T5, T6, T7, Out2]] = fut.map(_.as[Out2](ev, mapper))

    def executeAsync(t1: T1, t2: T2, t3: T3, t4: T4, t5: T5, t6: T6, t7: T7)(using cqlSession: Future[CqlSession], ec: ExecutionContext): Future[MappedAsyncPagingIterable[Out]] = cqlSession.flatMap {s => fut.flatMap(_.executeAsync(t1, t2, t3, t4, t5, t6, t7)(using s))}

    def pager(t1: T1, t2: T2, t3: T3, t4: T4, t5: T5, t6: T6, t7: T7)(using ec: ExecutionContext): Future[ApiPager[Out]] = fut.map(_.pager(t1, t2, t3, t4, t5, t6, t7))

    def pager(pagingState: PagingState, t1: T1, t2: T2, t3: T3, t4: T4, t5: T5, t6: T6, t7: T7)(using ec: ExecutionContext): Future[ApiPager[Out]] = fut.map(_.pager(pagingState, t1, t2, t3, t4, t5, t6, t7).get)

    def pager[A: PagerSerializer](pagingState: A, t1: T1, t2: T2, t3: T3, t4: T4, t5: T5, t6: T6, t7: T7)(using ec: ExecutionContext): Future[ApiPager[Out]] = fut.map(_.pager(pagingState, t1, t2, t3, t4, t5, t6, t7).get)
end extension

extension [T1, T2, T3, T4, T5, T6, T7, T8, Out](fut: Future[ScalaPreparedStatement8[T1, T2, T3, T4, T5, T6, T7, T8, Out]])
    @targetName("future_scala_pstmt_8_as_out2")
    def as[Out2](using ec: ExecutionContext, mapper: RowMapper[Out2], ev: Out =:= Row): Future[ScalaPreparedStatement8[T1, T2, T3, T4, T5, T6, T7, T8, Out2]] = fut.map(_.as[Out2])

    @targetName("future_scala_pstmt_8_as_out2_explicit_mapper")
    def as[Out2](mapper: RowMapper[Out2])(using ec: ExecutionContext, ev: Out =:= Row): Future[ScalaPreparedStatement8[T1, T2, T3, T4, T5, T6, T7, T8, Out2]] = fut.map(_.as[Out2](ev, mapper))

    def executeAsync(t1: T1, t2: T2, t3: T3, t4: T4, t5: T5, t6: T6, t7: T7, t8: T8)(using cqlSession: Future[CqlSession], ec: ExecutionContext): Future[MappedAsyncPagingIterable[Out]] = cqlSession.flatMap {s => fut.flatMap(_.executeAsync(t1, t2, t3, t4, t5, t6, t7, t8)(using s))}

    def pager(t1: T1, t2: T2, t3: T3, t4: T4, t5: T5, t6: T6, t7: T7, t8: T8)(using ec: ExecutionContext): Future[ApiPager[Out]] = fut.map(_.pager(t1, t2, t3, t4, t5, t6, t7, t8))

    def pager(pagingState: PagingState, t1: T1, t2: T2, t3: T3, t4: T4, t5: T5, t6: T6, t7: T7, t8: T8)(using ec: ExecutionContext): Future[ApiPager[Out]] = fut.map(_.pager(pagingState, t1, t2, t3, t4, t5, t6, t7, t8).get)

    def pager[A: PagerSerializer](pagingState: A, t1: T1, t2: T2, t3: T3, t4: T4, t5: T5, t6: T6, t7: T7, t8: T8)(using ec: ExecutionContext): Future[ApiPager[Out]] = fut.map(_.pager(pagingState, t1, t2, t3, t4, t5, t6, t7, t8).get)
end extension

extension [T1, T2, T3, T4, T5, T6, T7, T8, T9, Out](fut: Future[ScalaPreparedStatement9[T1, T2, T3, T4, T5, T6, T7, T8, T9, Out]])
    @targetName("future_scala_pstmt_9_as_out2")
    def as[Out2](using ec: ExecutionContext, mapper: RowMapper[Out2], ev: Out =:= Row): Future[ScalaPreparedStatement9[T1, T2, T3, T4, T5, T6, T7, T8, T9, Out2]] = fut.map(_.as[Out2])

    @targetName("future_scala_pstmt_9_as_out2_explicit_mapper")
    def as[Out2](mapper: RowMapper[Out2])(using ec: ExecutionContext, ev: Out =:= Row): Future[ScalaPreparedStatement9[T1, T2, T3, T4, T5, T6, T7, T8, T9, Out2]] = fut.map(_.as[Out2](ev, mapper))

    def executeAsync(t1: T1, t2: T2, t3: T3, t4: T4, t5: T5, t6: T6, t7: T7, t8: T8, t9: T9)(using cqlSession: Future[CqlSession], ec: ExecutionContext): Future[MappedAsyncPagingIterable[Out]] = cqlSession.flatMap {s => fut.flatMap(_.executeAsync(t1, t2, t3, t4, t5, t6, t7, t8, t9)(using s))}

    def pager(t1: T1, t2: T2, t3: T3, t4: T4, t5: T5, t6: T6, t7: T7, t8: T8, t9: T9)(using ec: ExecutionContext): Future[ApiPager[Out]] = fut.map(_.pager(t1, t2, t3, t4, t5, t6, t7, t8, t9))

    def pager(pagingState: PagingState, t1: T1, t2: T2, t3: T3, t4: T4, t5: T5, t6: T6, t7: T7, t8: T8, t9: T9)(using ec: ExecutionContext): Future[ApiPager[Out]] = fut.map(_.pager(pagingState, t1, t2, t3, t4, t5, t6, t7, t8, t9).get)

    def pager[A: PagerSerializer](pagingState: A, t1: T1, t2: T2, t3: T3, t4: T4, t5: T5, t6: T6, t7: T7, t8: T8, t9: T9)(using ec: ExecutionContext): Future[ApiPager[Out]] = fut.map(_.pager(pagingState, t1, t2, t3, t4, t5, t6, t7, t8, t9).get)
end extension

extension [T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, Out](fut: Future[ScalaPreparedStatement10[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, Out]])
    @targetName("future_scala_pstmt_10_as_out2")
    def as[Out2](using ec: ExecutionContext, mapper: RowMapper[Out2], ev: Out =:= Row): Future[ScalaPreparedStatement10[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, Out2]] = fut.map(_.as[Out2])

    @targetName("future_scala_pstmt_10_as_out2_explicit_mapper")
    def as[Out2](mapper: RowMapper[Out2])(using ec: ExecutionContext, ev: Out =:= Row): Future[ScalaPreparedStatement10[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, Out2]] = fut.map(_.as[Out2](ev, mapper))

    def executeAsync(t1: T1, t2: T2, t3: T3, t4: T4, t5: T5, t6: T6, t7: T7, t8: T8, t9: T9, t10: T10)(using cqlSession: Future[CqlSession], ec: ExecutionContext): Future[MappedAsyncPagingIterable[Out]] = cqlSession.flatMap {s => fut.flatMap(_.executeAsync(t1, t2, t3, t4, t5, t6, t7, t8, t9, t10)(using s))}
    def pager(t1: T1, t2: T2, t3: T3, t4: T4, t5: T5, t6: T6, t7: T7, t8: T8, t9: T9, t10: T10)(using ec: ExecutionContext): Future[ApiPager[Out]] = fut.map(_.pager(t1, t2, t3, t4, t5, t6, t7, t8, t9, t10))

    def pager(pagingState: PagingState, t1: T1, t2: T2, t3: T3, t4: T4, t5: T5, t6: T6, t7: T7, t8: T8, t9: T9, t10: T10)(using ec: ExecutionContext): Future[ApiPager[Out]] = fut.map(_.pager(pagingState, t1, t2, t3, t4, t5, t6, t7, t8, t9, t10).get)

    def pager[A: PagerSerializer](pagingState: A, t1: T1, t2: T2, t3: T3, t4: T4, t5: T5, t6: T6, t7: T7, t8: T8, t9: T9, t10: T10)(using ec: ExecutionContext): Future[ApiPager[Out]] = fut.map(_.pager(pagingState, t1, t2, t3, t4, t5, t6, t7, t8, t9, t10).get)
end extension

extension [T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, Out](fut: Future[ScalaPreparedStatement11[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, Out]])
    @targetName("future_scala_pstmt_11_as_out2")
    def as[Out2](using ec: ExecutionContext, mapper: RowMapper[Out2], ev: Out =:= Row): Future[ScalaPreparedStatement11[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, Out2]] = fut.map(_.as[Out2])

    @targetName("future_scala_pstmt_11_as_out2_explicit_mapper")
    def as[Out2](mapper: RowMapper[Out2])(using ec: ExecutionContext, ev: Out =:= Row): Future[ScalaPreparedStatement11[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, Out2]] = fut.map(_.as[Out2](ev, mapper))

    def executeAsync(t1: T1, t2: T2, t3: T3, t4: T4, t5: T5, t6: T6, t7: T7, t8: T8, t9: T9, t10: T10, t11: T11)(using cqlSession: Future[CqlSession], ec: ExecutionContext): Future[MappedAsyncPagingIterable[Out]] = cqlSession.flatMap {s => fut.flatMap(_.executeAsync(t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11)(using s))}

    def pager(t1: T1, t2: T2, t3: T3, t4: T4, t5: T5, t6: T6, t7: T7, t8: T8, t9: T9, t10: T10, t11: T11)(using ec: ExecutionContext): Future[ApiPager[Out]] = fut.map(_.pager(t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11))

    def pager(pagingState: PagingState, t1: T1, t2: T2, t3: T3, t4: T4, t5: T5, t6: T6, t7: T7, t8: T8, t9: T9, t10: T10, t11: T11)(using ec: ExecutionContext): Future[ApiPager[Out]] = fut.map(_.pager(pagingState, t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11).get)

    def pager[A: PagerSerializer](pagingState: A, t1: T1, t2: T2, t3: T3, t4: T4, t5: T5, t6: T6, t7: T7, t8: T8, t9: T9, t10: T10, t11: T11)(using ec: ExecutionContext): Future[ApiPager[Out]] = fut.map(_.pager(pagingState, t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11).get)
end extension

extension [T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, Out](fut: Future[ScalaPreparedStatement12[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, Out]])
    @targetName("future_scala_pstmt_12_as_out2")
    def as[Out2](using ec: ExecutionContext, mapper: RowMapper[Out2], ev: Out =:= Row): Future[ScalaPreparedStatement12[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, Out2]] = fut.map(_.as[Out2])

    @targetName("future_scala_pstmt_12_as_out2_explicit_mapper")
    def as[Out2](mapper: RowMapper[Out2])(using ec: ExecutionContext, ev: Out =:= Row): Future[ScalaPreparedStatement12[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, Out2]] = fut.map(_.as[Out2](ev, mapper))

    def executeAsync(t1: T1, t2: T2, t3: T3, t4: T4, t5: T5, t6: T6, t7: T7, t8: T8, t9: T9, t10: T10, t11: T11, t12: T12)(using cqlSession: Future[CqlSession], ec: ExecutionContext): Future[MappedAsyncPagingIterable[Out]] = cqlSession.flatMap {s => fut.flatMap(_.executeAsync(t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12)(using s))}

    def pager(t1: T1, t2: T2, t3: T3, t4: T4, t5: T5, t6: T6, t7: T7, t8: T8, t9: T9, t10: T10, t11: T11, t12: T12)(using ec: ExecutionContext): Future[ApiPager[Out]] = fut.map(_.pager(t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12))

    def pager(pagingState: PagingState, t1: T1, t2: T2, t3: T3, t4: T4, t5: T5, t6: T6, t7: T7, t8: T8, t9: T9, t10: T10, t11: T11, t12: T12)(using ec: ExecutionContext): Future[ApiPager[Out]] = fut.map(_.pager(pagingState, t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12).get)

    def pager[A: PagerSerializer](pagingState: A, t1: T1, t2: T2, t3: T3, t4: T4, t5: T5, t6: T6, t7: T7, t8: T8, t9: T9, t10: T10, t11: T11, t12: T12)(using ec: ExecutionContext): Future[ApiPager[Out]] = fut.map(_.pager(pagingState, t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12).get)
end extension

extension [T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, Out](fut: Future[ScalaPreparedStatement13[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, Out]])
    @targetName("future_scala_pstmt_13_as_out2")
    def as[Out2](using ec: ExecutionContext, mapper: RowMapper[Out2], ev: Out =:= Row): Future[ScalaPreparedStatement13[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, Out2]] = fut.map(_.as[Out2])

    @targetName("future_scala_pstmt_13_as_out2_explicit_mapper")
    def as[Out2](mapper: RowMapper[Out2])(using ec: ExecutionContext, ev: Out =:= Row): Future[ScalaPreparedStatement13[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, Out2]] = fut.map(_.as[Out2](ev, mapper))

    def executeAsync(t1: T1, t2: T2, t3: T3, t4: T4, t5: T5, t6: T6, t7: T7, t8: T8, t9: T9, t10: T10, t11: T11, t12: T12, t13: T13)(using cqlSession: Future[CqlSession], ec: ExecutionContext): Future[MappedAsyncPagingIterable[Out]] = cqlSession.flatMap {s => fut.flatMap(_.executeAsync(t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13)(using s))}

    def pager(t1: T1, t2: T2, t3: T3, t4: T4, t5: T5, t6: T6, t7: T7, t8: T8, t9: T9, t10: T10, t11: T11, t12: T12, t13: T13)(using ec: ExecutionContext): Future[ApiPager[Out]] = fut.map(_.pager(t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13))

    def pager(pagingState: PagingState, t1: T1, t2: T2, t3: T3, t4: T4, t5: T5, t6: T6, t7: T7, t8: T8, t9: T9, t10: T10, t11: T11, t12: T12, t13: T13)(using ec: ExecutionContext): Future[ApiPager[Out]] = fut.map(_.pager(pagingState, t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13).get)

    def pager[A: PagerSerializer](pagingState: A, t1: T1, t2: T2, t3: T3, t4: T4, t5: T5, t6: T6, t7: T7, t8: T8, t9: T9, t10: T10, t11: T11, t12: T12, t13: T13)(using ec: ExecutionContext): Future[ApiPager[Out]] = fut.map(_.pager(pagingState, t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13).get)
end extension

extension [T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, Out](fut: Future[ScalaPreparedStatement14[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, Out]])
    @targetName("future_scala_pstmt_14_as_out2")
    def as[Out2](using ec: ExecutionContext, mapper: RowMapper[Out2], ev: Out =:= Row): Future[ScalaPreparedStatement14[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, Out2]] = fut.map(_.as[Out2])

    @targetName("future_scala_pstmt_14_as_out2_explicit_mapper")
    def as[Out2](mapper: RowMapper[Out2])(using ec: ExecutionContext, ev: Out =:= Row): Future[ScalaPreparedStatement14[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, Out2]] = fut.map(_.as[Out2](ev, mapper))

    def executeAsync(t1: T1, t2: T2, t3: T3, t4: T4, t5: T5, t6: T6, t7: T7, t8: T8, t9: T9, t10: T10, t11: T11, t12: T12, t13: T13, t14: T14)(using cqlSession: Future[CqlSession], ec: ExecutionContext): Future[MappedAsyncPagingIterable[Out]] = cqlSession.flatMap {s => fut.flatMap(_.executeAsync(t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14)(using s))}

    def pager(t1: T1, t2: T2, t3: T3, t4: T4, t5: T5, t6: T6, t7: T7, t8: T8, t9: T9, t10: T10, t11: T11, t12: T12, t13: T13, t14: T14)(using ec: ExecutionContext): Future[ApiPager[Out]] = fut.map(_.pager(t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14))

    def pager(pagingState: PagingState, t1: T1, t2: T2, t3: T3, t4: T4, t5: T5, t6: T6, t7: T7, t8: T8, t9: T9, t10: T10, t11: T11, t12: T12, t13: T13, t14: T14)(using ec: ExecutionContext): Future[ApiPager[Out]] = fut.map(_.pager(pagingState, t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14).get)

    def pager[A: PagerSerializer](pagingState: A, t1: T1, t2: T2, t3: T3, t4: T4, t5: T5, t6: T6, t7: T7, t8: T8, t9: T9, t10: T10, t11: T11, t12: T12, t13: T13, t14: T14)(using ec: ExecutionContext): Future[ApiPager[Out]] = fut.map(_.pager(pagingState, t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14).get)
end extension

extension [T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, Out](fut: Future[ScalaPreparedStatement15[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, Out]])
    @targetName("future_scala_pstmt_15_as_out2")
    def as[Out2](using ec: ExecutionContext, mapper: RowMapper[Out2], ev: Out =:= Row): Future[ScalaPreparedStatement15[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, Out2]] = fut.map(_.as[Out2])

    @targetName("future_scala_pstmt_15_as_out2_explicit_mapper")
    def as[Out2](mapper: RowMapper[Out2])(using ec: ExecutionContext, ev: Out =:= Row): Future[ScalaPreparedStatement15[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, Out2]] = fut.map(_.as[Out2](ev, mapper))

    def executeAsync(t1: T1, t2: T2, t3: T3, t4: T4, t5: T5, t6: T6, t7: T7, t8: T8, t9: T9, t10: T10, t11: T11, t12: T12, t13: T13, t14: T14, t15: T15)(using cqlSession: Future[CqlSession], ec: ExecutionContext): Future[MappedAsyncPagingIterable[Out]] = cqlSession.flatMap {s => fut.flatMap(_.executeAsync(t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14, t15)(using s))}

    def pager(t1: T1, t2: T2, t3: T3, t4: T4, t5: T5, t6: T6, t7: T7, t8: T8, t9: T9, t10: T10, t11: T11, t12: T12, t13: T13, t14: T14, t15: T15)(using ec: ExecutionContext): Future[ApiPager[Out]] = fut.map(_.pager(t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14, t15))

    def pager(pagingState: PagingState, t1: T1, t2: T2, t3: T3, t4: T4, t5: T5, t6: T6, t7: T7, t8: T8, t9: T9, t10: T10, t11: T11, t12: T12, t13: T13, t14: T14, t15: T15)(using ec: ExecutionContext): Future[ApiPager[Out]] = fut.map(_.pager(pagingState, t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14, t15).get)

    def pager[A: PagerSerializer](pagingState: A, t1: T1, t2: T2, t3: T3, t4: T4, t5: T5, t6: T6, t7: T7, t8: T8, t9: T9, t10: T10, t11: T11, t12: T12, t13: T13, t14: T14, t15: T15)(using ec: ExecutionContext): Future[ApiPager[Out]] = fut.map(_.pager(pagingState, t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14, t15).get)
end extension

extension [T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, Out](fut: Future[ScalaPreparedStatement16[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, Out]])
    @targetName("future_scala_pstmt_16_as_out2")
    def as[Out2](using ec: ExecutionContext, mapper: RowMapper[Out2], ev: Out =:= Row): Future[ScalaPreparedStatement16[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, Out2]] = fut.map(_.as[Out2])

    @targetName("future_scala_pstmt_16_as_out2_explicit_mapper")
    def as[Out2](mapper: RowMapper[Out2])(using ec: ExecutionContext, ev: Out =:= Row): Future[ScalaPreparedStatement16[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, Out2]] = fut.map(_.as[Out2](ev, mapper))

    def executeAsync(t1: T1, t2: T2, t3: T3, t4: T4, t5: T5, t6: T6, t7: T7, t8: T8, t9: T9, t10: T10, t11: T11, t12: T12, t13: T13, t14: T14, t15: T15, t16: T16)(using cqlSession: Future[CqlSession], ec: ExecutionContext): Future[MappedAsyncPagingIterable[Out]] = cqlSession.flatMap {s => fut.flatMap(_.executeAsync(t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14, t15, t16)(using s))}

    def pager(t1: T1, t2: T2, t3: T3, t4: T4, t5: T5, t6: T6, t7: T7, t8: T8, t9: T9, t10: T10, t11: T11, t12: T12, t13: T13, t14: T14, t15: T15, t16: T16)(using ec: ExecutionContext): Future[ApiPager[Out]] = fut.map(_.pager(t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14, t15, t16))

    def pager(pagingState: PagingState, t1: T1, t2: T2, t3: T3, t4: T4, t5: T5, t6: T6, t7: T7, t8: T8, t9: T9, t10: T10, t11: T11, t12: T12, t13: T13, t14: T14, t15: T15, t16: T16)(using ec: ExecutionContext): Future[ApiPager[Out]] = fut.map(_.pager(pagingState, t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14, t15, t16).get)

    def pager[A: PagerSerializer](pagingState: A, t1: T1, t2: T2, t3: T3, t4: T4, t5: T5, t6: T6, t7: T7, t8: T8, t9: T9, t10: T10, t11: T11, t12: T12, t13: T13, t14: T14, t15: T15, t16: T16)(using ec: ExecutionContext): Future[ApiPager[Out]] = fut.map(_.pager(pagingState, t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14, t15, t16).get)
end extension

extension [T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, Out](fut: Future[ScalaPreparedStatement17[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, Out]])
    @targetName("future_scala_pstmt_17_as_out2")
    def as[Out2](using ec: ExecutionContext, mapper: RowMapper[Out2], ev: Out =:= Row): Future[ScalaPreparedStatement17[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, Out2]] = fut.map(_.as[Out2])

    @targetName("future_scala_pstmt_17_as_out2_explicit_mapper")
    def as[Out2](mapper: RowMapper[Out2])(using ec: ExecutionContext, ev: Out =:= Row): Future[ScalaPreparedStatement17[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, Out2]] = fut.map(_.as[Out2](ev, mapper))

    def executeAsync(t1: T1, t2: T2, t3: T3, t4: T4, t5: T5, t6: T6, t7: T7, t8: T8, t9: T9, t10: T10, t11: T11, t12: T12, t13: T13, t14: T14, t15: T15, t16: T16, t17: T17)(using cqlSession: Future[CqlSession], ec: ExecutionContext): Future[MappedAsyncPagingIterable[Out]] = cqlSession.flatMap {s => fut.flatMap(_.executeAsync(t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14, t15, t16, t17)(using s))}

    def pager(t1: T1, t2: T2, t3: T3, t4: T4, t5: T5, t6: T6, t7: T7, t8: T8, t9: T9, t10: T10, t11: T11, t12: T12, t13: T13, t14: T14, t15: T15, t16: T16, t17: T17)(using ec: ExecutionContext): Future[ApiPager[Out]] = fut.map(_.pager(t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14, t15, t16, t17))

    def pager(pagingState: PagingState, t1: T1, t2: T2, t3: T3, t4: T4, t5: T5, t6: T6, t7: T7, t8: T8, t9: T9, t10: T10, t11: T11, t12: T12, t13: T13, t14: T14, t15: T15, t16: T16, t17: T17)(using ec: ExecutionContext): Future[ApiPager[Out]] = fut.map(_.pager(pagingState, t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14, t15, t16, t17).get)

    def pager[A: PagerSerializer](pagingState: A, t1: T1, t2: T2, t3: T3, t4: T4, t5: T5, t6: T6, t7: T7, t8: T8, t9: T9, t10: T10, t11: T11, t12: T12, t13: T13, t14: T14, t15: T15, t16: T16, t17: T17)(using ec: ExecutionContext): Future[ApiPager[Out]] = fut.map(_.pager(pagingState, t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14, t15, t16, t17).get)
end extension

extension [T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, Out](fut: Future[ScalaPreparedStatement18[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, Out]])
    @targetName("future_scala_pstmt_18_as_out2")
    def as[Out2](using ec: ExecutionContext, mapper: RowMapper[Out2], ev: Out =:= Row): Future[ScalaPreparedStatement18[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, Out2]] = fut.map(_.as[Out2])

    @targetName("future_scala_pstmt_18_as_out2_explicit_mapper")
    def as[Out2](mapper: RowMapper[Out2])(using ec: ExecutionContext, ev: Out =:= Row): Future[ScalaPreparedStatement18[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, Out2]] = fut.map(_.as[Out2](ev, mapper))

    def executeAsync(t1: T1, t2: T2, t3: T3, t4: T4, t5: T5, t6: T6, t7: T7, t8: T8, t9: T9, t10: T10, t11: T11, t12: T12, t13: T13, t14: T14, t15: T15, t16: T16, t17: T17, t18: T18)(using cqlSession: Future[CqlSession], ec: ExecutionContext): Future[MappedAsyncPagingIterable[Out]] = cqlSession.flatMap {s => fut.flatMap(_.executeAsync(t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14, t15, t16, t17, t18)(using s))}

    def pager(t1: T1, t2: T2, t3: T3, t4: T4, t5: T5, t6: T6, t7: T7, t8: T8, t9: T9, t10: T10, t11: T11, t12: T12, t13: T13, t14: T14, t15: T15, t16: T16, t17: T17, t18: T18)(using ec: ExecutionContext): Future[ApiPager[Out]] = fut.map(_.pager(t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14, t15, t16, t17, t18))

    def pager(pagingState: PagingState, t1: T1, t2: T2, t3: T3, t4: T4, t5: T5, t6: T6, t7: T7, t8: T8, t9: T9, t10: T10, t11: T11, t12: T12, t13: T13, t14: T14, t15: T15, t16: T16, t17: T17, t18: T18)(using ec: ExecutionContext): Future[ApiPager[Out]] = fut.map(_.pager(pagingState, t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14, t15, t16, t17, t18).get)

    def pager[A: PagerSerializer](pagingState: A, t1: T1, t2: T2, t3: T3, t4: T4, t5: T5, t6: T6, t7: T7, t8: T8, t9: T9, t10: T10, t11: T11, t12: T12, t13: T13, t14: T14, t15: T15, t16: T16, t17: T17, t18: T18)(using ec: ExecutionContext): Future[ApiPager[Out]] = fut.map(_.pager(pagingState, t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14, t15, t16, t17, t18).get)
end extension

extension [T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, Out](fut: Future[ScalaPreparedStatement19[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, Out]])
    @targetName("future_scala_pstmt_19_as_out2")
    def as[Out2](using ec: ExecutionContext, mapper: RowMapper[Out2], ev: Out =:= Row): Future[ScalaPreparedStatement19[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, Out2]] = fut.map(_.as[Out2])

    @targetName("future_scala_pstmt_19_as_out2_explicit_mapper")
    def as[Out2](mapper: RowMapper[Out2])(using ec: ExecutionContext, ev: Out =:= Row): Future[ScalaPreparedStatement19[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, Out2]] = fut.map(_.as[Out2](ev, mapper))

    def executeAsync(t1: T1, t2: T2, t3: T3, t4: T4, t5: T5, t6: T6, t7: T7, t8: T8, t9: T9, t10: T10, t11: T11, t12: T12, t13: T13, t14: T14, t15: T15, t16: T16, t17: T17, t18: T18, t19: T19)(using cqlSession: Future[CqlSession], ec: ExecutionContext): Future[MappedAsyncPagingIterable[Out]] = cqlSession.flatMap {s => fut.flatMap(_.executeAsync(t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14, t15, t16, t17, t18, t19)(using s))}

    def pager(t1: T1, t2: T2, t3: T3, t4: T4, t5: T5, t6: T6, t7: T7, t8: T8, t9: T9, t10: T10, t11: T11, t12: T12, t13: T13, t14: T14, t15: T15, t16: T16, t17: T17, t18: T18, t19: T19)(using ec: ExecutionContext): Future[ApiPager[Out]] = fut.map(_.pager(t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14, t15, t16, t17, t18, t19))

    def pager(pagingState: PagingState, t1: T1, t2: T2, t3: T3, t4: T4, t5: T5, t6: T6, t7: T7, t8: T8, t9: T9, t10: T10, t11: T11, t12: T12, t13: T13, t14: T14, t15: T15, t16: T16, t17: T17, t18: T18, t19: T19)(using ec: ExecutionContext): Future[ApiPager[Out]] = fut.map(_.pager(pagingState, t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14, t15, t16, t17, t18, t19).get)

    def pager[A: PagerSerializer](pagingState: A, t1: T1, t2: T2, t3: T3, t4: T4, t5: T5, t6: T6, t7: T7, t8: T8, t9: T9, t10: T10, t11: T11, t12: T12, t13: T13, t14: T14, t15: T15, t16: T16, t17: T17, t18: T18, t19: T19)(using ec: ExecutionContext): Future[ApiPager[Out]] = fut.map(_.pager(pagingState, t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14, t15, t16, t17, t18, t19).get)
end extension

extension [T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, T20, Out](fut: Future[ScalaPreparedStatement20[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, T20, Out]])
    @targetName("future_scala_pstmt_20_as_out2")
    def as[Out2](using ec: ExecutionContext, mapper: RowMapper[Out2], ev: Out =:= Row): Future[ScalaPreparedStatement20[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, T20, Out2]] = fut.map(_.as[Out2])

    @targetName("future_scala_pstmt_20_as_out2_explicit_mapper")
    def as[Out2](mapper: RowMapper[Out2])(using ec: ExecutionContext, ev: Out =:= Row): Future[ScalaPreparedStatement20[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, T20, Out2]] = fut.map(_.as[Out2](ev, mapper))

    def executeAsync(t1: T1, t2: T2, t3: T3, t4: T4, t5: T5, t6: T6, t7: T7, t8: T8, t9: T9, t10: T10, t11: T11, t12: T12, t13: T13, t14: T14, t15: T15, t16: T16, t17: T17, t18: T18, t19: T19, t20: T20)(using cqlSession: Future[CqlSession], ec: ExecutionContext): Future[MappedAsyncPagingIterable[Out]] = cqlSession.flatMap {s => fut.flatMap(_.executeAsync(t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14, t15, t16, t17, t18, t19, t20)(using s))}

    def pager(t1: T1, t2: T2, t3: T3, t4: T4, t5: T5, t6: T6, t7: T7, t8: T8, t9: T9, t10: T10, t11: T11, t12: T12, t13: T13, t14: T14, t15: T15, t16: T16, t17: T17, t18: T18, t19: T19, t20: T20)(using ec: ExecutionContext): Future[ApiPager[Out]] = fut.map(_.pager(t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14, t15, t16, t17, t18, t19, t20))

    def pager(pagingState: PagingState, t1: T1, t2: T2, t3: T3, t4: T4, t5: T5, t6: T6, t7: T7, t8: T8, t9: T9, t10: T10, t11: T11, t12: T12, t13: T13, t14: T14, t15: T15, t16: T16, t17: T17, t18: T18, t19: T19, t20: T20)(using ec: ExecutionContext): Future[ApiPager[Out]] = fut.map(_.pager(pagingState, t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14, t15, t16, t17, t18, t19, t20).get)

    def pager[A: PagerSerializer](pagingState: A, t1: T1, t2: T2, t3: T3, t4: T4, t5: T5, t6: T6, t7: T7, t8: T8, t9: T9, t10: T10, t11: T11, t12: T12, t13: T13, t14: T14, t15: T15, t16: T16, t17: T17, t18: T18, t19: T19, t20: T20)(using ec: ExecutionContext): Future[ApiPager[Out]] = fut.map(_.pager(pagingState, t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14, t15, t16, t17, t18, t19, t20).get)
end extension

extension [T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, T20, T21, Out](fut: Future[ScalaPreparedStatement21[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, T20, T21, Out]])
    @targetName("future_scala_pstmt_21_as_out2")
    def as[Out2](using ec: ExecutionContext, mapper: RowMapper[Out2], ev: Out =:= Row): Future[ScalaPreparedStatement21[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, T20, T21, Out2]] = fut.map(_.as[Out2])

    @targetName("future_scala_pstmt_21_as_out2_explicit_mapper")
    def as[Out2](mapper: RowMapper[Out2])(using ec: ExecutionContext, ev: Out =:= Row): Future[ScalaPreparedStatement21[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, T20, T21, Out2]] = fut.map(_.as[Out2](ev, mapper))

    def executeAsync(t1: T1, t2: T2, t3: T3, t4: T4, t5: T5, t6: T6, t7: T7, t8: T8, t9: T9, t10: T10, t11: T11, t12: T12, t13: T13, t14: T14, t15: T15, t16: T16, t17: T17, t18: T18, t19: T19, t20: T20, t21: T21)(using cqlSession: Future[CqlSession], ec: ExecutionContext): Future[MappedAsyncPagingIterable[Out]] = cqlSession.flatMap {s => fut.flatMap(_.executeAsync(t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14, t15, t16, t17, t18, t19, t20, t21)(using s))}

    def pager(t1: T1, t2: T2, t3: T3, t4: T4, t5: T5, t6: T6, t7: T7, t8: T8, t9: T9, t10: T10, t11: T11, t12: T12, t13: T13, t14: T14, t15: T15, t16: T16, t17: T17, t18: T18, t19: T19, t20: T20, t21: T21)(using ec: ExecutionContext): Future[ApiPager[Out]] = fut.map(_.pager(t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14, t15, t16, t17, t18, t19, t20, t21))

    def pager(pagingState: PagingState, t1: T1, t2: T2, t3: T3, t4: T4, t5: T5, t6: T6, t7: T7, t8: T8, t9: T9, t10: T10, t11: T11, t12: T12, t13: T13, t14: T14, t15: T15, t16: T16, t17: T17, t18: T18, t19: T19, t20: T20, t21: T21)(using ec: ExecutionContext): Future[ApiPager[Out]] = fut.map(_.pager(pagingState, t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14, t15, t16, t17, t18, t19, t20, t21).get)

    def pager[A: PagerSerializer](pagingState: A, t1: T1, t2: T2, t3: T3, t4: T4, t5: T5, t6: T6, t7: T7, t8: T8, t9: T9, t10: T10, t11: T11, t12: T12, t13: T13, t14: T14, t15: T15, t16: T16, t17: T17, t18: T18, t19: T19, t20: T20, t21: T21)(using ec: ExecutionContext): Future[ApiPager[Out]] = fut.map(_.pager(pagingState, t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14, t15, t16, t17, t18, t19, t20, t21).get)
end extension

extension [T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, T20, T21, T22, Out](fut: Future[ScalaPreparedStatement22[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, T20, T21, T22, Out]])
    @targetName("future_scala_pstmt_22_as_out2")
    def as[Out2](using ec: ExecutionContext, mapper: RowMapper[Out2], ev: Out =:= Row): Future[ScalaPreparedStatement22[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, T20, T21, T22, Out2]] = fut.map(_.as[Out2])

    @targetName("future_scala_pstmt_22_as_out2_explicit_mapper")
    def as[Out2](mapper: RowMapper[Out2])(using ec: ExecutionContext, ev: Out =:= Row): Future[ScalaPreparedStatement22[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, T20, T21, T22, Out2]] = fut.map(_.as[Out2](ev, mapper))

    def executeAsync(t1: T1, t2: T2, t3: T3, t4: T4, t5: T5, t6: T6, t7: T7, t8: T8, t9: T9, t10: T10, t11: T11, t12: T12, t13: T13, t14: T14, t15: T15, t16: T16, t17: T17, t18: T18, t19: T19, t20: T20, t21: T21, t22: T22)(using cqlSession: Future[CqlSession], ec: ExecutionContext): Future[MappedAsyncPagingIterable[Out]] = cqlSession.flatMap {s => fut.flatMap(_.executeAsync(t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14, t15, t16, t17, t18, t19, t20, t21, t22)(using s))}

    def pager(t1: T1, t2: T2, t3: T3, t4: T4, t5: T5, t6: T6, t7: T7, t8: T8, t9: T9, t10: T10, t11: T11, t12: T12, t13: T13, t14: T14, t15: T15, t16: T16, t17: T17, t18: T18, t19: T19, t20: T20, t21: T21, t22: T22)(using ec: ExecutionContext): Future[ApiPager[Out]] = fut.map(_.pager(t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14, t15, t16, t17, t18, t19, t20, t21, t22))

    def pager(pagingState: PagingState, t1: T1, t2: T2, t3: T3, t4: T4, t5: T5, t6: T6, t7: T7, t8: T8, t9: T9, t10: T10, t11: T11, t12: T12, t13: T13, t14: T14, t15: T15, t16: T16, t17: T17, t18: T18, t19: T19, t20: T20, t21: T21, t22: T22)(using ec: ExecutionContext): Future[ApiPager[Out]] = fut.map(_.pager(pagingState, t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14, t15, t16, t17, t18, t19, t20, t21, t22).get)

    def pager[A: PagerSerializer](pagingState: A, t1: T1, t2: T2, t3: T3, t4: T4, t5: T5, t6: T6, t7: T7, t8: T8, t9: T9, t10: T10, t11: T11, t12: T12, t13: T13, t14: T14, t15: T15, t16: T16, t17: T17, t18: T18, t19: T19, t20: T20, t21: T21, t22: T22)(using ec: ExecutionContext): Future[ApiPager[Out]] = fut.map(_.pager(pagingState, t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14, t15, t16, t17, t18, t19, t20, t21, t22).get)
end extension
// format: on
