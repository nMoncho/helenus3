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
import com.datastax.oss.driver.api.core.`type`.codec.MappingCodec
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
import net.nmoncho.helenus.api.cql.PagerSerializer
import net.nmoncho.helenus.api.cql.ScalaPreparedStatement.CQLQuery
import net.nmoncho.helenus.api.cql.StatementOptions
import net.nmoncho.helenus.api.cql.WrappedBoundStatement
import net.nmoncho.helenus.internal.cql.Pager
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
    def withOptions(fn: BoundStatement => BoundStatement)(
        implicit ec: ExecutionContext
    ): Future[ScalaBoundStatement[Out]] =
        bstmt.map(b => fn(b).asInstanceOf[ScalaBoundStatement[Out]])

    /** Set options to this [[BoundStatement]] while returning the original type
      */
    def withOptions(options: StatementOptions)(
        implicit ec: ExecutionContext
    ): Future[ScalaBoundStatement[Out]] =
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

extension (row: Row)
    /** Converts a [[Row]] into a [[T]]
      */
    def as[T](using mapper: RowMapper[T]): T = mapper.apply(row)

    /** Gets a column from this [[Row]] of type [[T]] by name
      *
      * @param name column name
      */
    def getCol[T](name: String)(using codec: TypeCodec[T]): T = row.get(name, codec)

    /** Gets a column from this [[Row]] of type [[T]] by index
      *
      * @param index position in row
      */
    def getCol[T](index: Int)(using codec: TypeCodec[T]): T = row.get(index, codec)
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
      * <b>NOTE:</b> On Scala 2.12 it will fetch all pages!
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
