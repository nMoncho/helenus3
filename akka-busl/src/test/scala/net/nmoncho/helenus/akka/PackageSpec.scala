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

package net.nmoncho.helenus.akka

import scala.collection.immutable
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import _root_.akka.Done
import _root_.akka.NotUsed
import _root_.akka.actor.ActorSystem
import _root_.akka.stream.alpakka.cassandra.*
import _root_.akka.stream.alpakka.cassandra.scaladsl.*
import _root_.akka.stream.scaladsl.FlowWithContext
import _root_.akka.stream.scaladsl.Sink
import _root_.akka.stream.scaladsl.Source
import akka.stream.scaladsl.Keep
import com.datastax.oss.driver.api.core.CqlSession
import com.datastax.oss.driver.api.core.cql.Row
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import net.nmoncho.helenus.api.RowMapper
import net.nmoncho.helenus.api.cql.Adapter
import net.nmoncho.helenus.api.cql.Pager
import net.nmoncho.helenus.api.cql.PagerSerializer
import net.nmoncho.helenus.utils.CassandraSpec
import org.scalatest.OptionValues.*
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.Seconds
import org.scalatest.time.Span
import org.scalatest.wordspec.AnyWordSpec

class PackageSpec extends AnyWordSpec with Matchers with CassandraSpec with ScalaFutures:
    import PackageSpec.*
    import net.nmoncho.helenus.*

    override implicit def patienceConfig: PatienceConfig = PatienceConfig(Span(6, Seconds))

    private implicit lazy val system: ActorSystem =
        ActorSystem(
          "akka-spec",
          cassandraConfig
        )

    private implicit lazy val as: CassandraSession = CassandraSessionRegistry(system)
        .sessionFor(CassandraSessionSettings())

    given PagerSerializer[String] = PagerSerializer.DefaultPagingStateSerializer

    "Helenus" should {
        import system.dispatcher

        val pageSize = 2

        "work with Akka Streams (sync)" in withSession { implicit s =>
            val query: Source[IceCream, NotUsed] = "SELECT * FROM ice_creams".toCQL.prepareUnit
                .as[IceCream]
                .asReadSource()

            val insert: Sink[IceCream, Future[Done]] =
                "INSERT INTO ice_creams(name, numCherries, cone) VALUES(?, ?, ?)".toCQL
                    .prepare[String, Int, Boolean]
                    .from[IceCream]
                    .asWriteSink(writeSettings)

            testStream(ijes, query, insert)(identity)

            val queryName: Source[IceCream, NotUsed] = "SELECT * FROM ice_creams WHERE name = ?".toCQL
                .prepare[String]
                .as[IceCream]
                .asReadSource("vanilla")

            val futQueryName = queryName.runWith(Sink.seq)
            whenReady(futQueryName) { result =>
                result should not be empty
            }

            val queryNameAndCone: Source[IceCream, NotUsed] =
                "SELECT * FROM ice_creams WHERE name = ? AND cone = ? ALLOW FILTERING".toCQL
                    .prepare[String, Boolean]
                    .as[IceCream]
                    .asReadSource("vanilla", true)

            val futQueryNameAndCone = queryNameAndCone.runWith(Sink.seq[IceCream])
            whenReady(futQueryNameAndCone) { result =>
                result should not be empty
            }

            withClue("work with interpolated queries") {
                val name  = "vanilla"
                val query = cql"SELECT * FROM ice_creams WHERE name = $name".as[IceCream].asReadSource()

                val queryStream = query.runWith(Sink.seq[IceCream])
                whenReady(queryStream) { result =>
                    result should not be empty
                }
            }

            withClue("use reactive pagination") {
                val rows = Source.fromPublisher(
                  "SELECT * FROM ice_creams".toCQL.prepareUnit
                      .as[IceCream]
                      .pager()
                      .executeReactive(pageSize)
                )

                val page0Stream = rows.runWith(Sink.seq[(Pager[IceCream], IceCream)])
                val pager0 = whenReady(page0Stream) { result =>
                    result should have size pageSize
                    result.last._1
                }

                val rows2 = Source.fromPublisher(
                  "SELECT * FROM ice_creams".toCQL.prepareUnit
                      .as[IceCream]
                      .pager(pager0.encodePagingState.get)
                      .get
                      .executeReactive(pageSize)
                )

                val page1Stream = rows2.runWith(Sink.seq[(Pager[IceCream], IceCream)])
                whenReady(page1Stream) { result =>
                    result should have size 1
                }
            }

            withClue("use pager operator") {
                val query = "SELECT * FROM ice_creams".toCQL.prepareUnit.as[IceCream]

                val pager0 = query.pager().asReadSource(pageSize)

                val (state0, rows0) = pager0.toMat(Sink.seq[IceCream])(Keep.both).run()
                val (page0State, page0) = whenReady(rows0.flatMap(r => state0.map(r -> _))) {
                    case (rows, state) =>
                        rows should have size pageSize

                        state -> rows
                }

                val pager1 = query.pager(page0State.value).asReadSource(pageSize)

                val (state2, rows2) = pager1.toMat(Sink.seq[IceCream])(Keep.both).run()
                whenReady(rows2.flatMap(r => state2.map(r -> _))) { case (rows, state) =>
                    rows should have size 1
                    rows.toSet should not equal (page0.toSet)

                    state should not be page0State
                    state should not be defined
                }
            }
        }

        "work with Akka Streams and Context (sync)" in withSession { implicit session =>
            val query: Source[IceCream, NotUsed] = "SELECT * FROM ice_creams".toCQL.prepareUnit
                .as[IceCream]
                .asReadSource()

            val insert =
                "INSERT INTO ice_creams(name, numCherries, cone) VALUES(?, ?, ?)".toCQL
                    .prepare[String, Int, Boolean]
                    .from[IceCream]
                    .asWriteFlowWithContext[String](writeSettings)

            testStreamWithContext(ijes, query, insert)(ij => ij -> ij.name)
        }

        "perform batched writes with Akka Stream (sync)" in withSession { implicit session =>
            val query: Source[IceCream, NotUsed] = "SELECT * FROM ice_creams".toCQL.prepareUnit
                .as[IceCream]
                .asReadSource()

            val batchedInsert: Sink[IceCream, Future[Done]] =
                "INSERT INTO ice_creams(name, numCherries, cone) VALUES(?, ?, ?)".toCQL
                    .prepare[String, Int, Boolean]
                    .from[IceCream]
                    .asWriteSinkBatched(writeSettings, _.name.charAt(0))

            testStream(batchIjs, query, batchedInsert)(identity)
        }

        "work with Akka Streams (async)" in {
            val query: Source[IceCream, NotUsed] = "SELECT * FROM ice_creams".toCQLAsync.prepareUnit
                .as[IceCream]
                .asReadSource()

            val insert: Sink[IceCream, Future[Done]] =
                "INSERT INTO ice_creams(name, numCherries, cone) VALUES(?, ?, ?)".toCQLAsync
                    .prepare[String, Int, Boolean]
                    .from[IceCream]
                    .asWriteSink(writeSettings)

            testStream(ijes, query, insert)(identity)

            withClue("and use an explicit RowMapper") {
                val query: Source[IceCream, NotUsed] = "SELECT * FROM ice_creams".toCQLAsync.prepareUnit
                    .as((row: Row) =>
                        IceCream(
                          row.getCol[String]("name"),
                          row.getCol[Int]("numCherries"),
                          row.getCol[Boolean]("cone")
                        )
                    )
                    .asReadSource()

                testStream(ijes, query, insert)(identity)
            }

            // withClue("work with interpolated queries") {
            //     val name = "vanilla"
            //     val query =
            //         cqlAsync"SELECT * FROM ice_creams WHERE name = $name".as[IceCream].asReadSource()

            //     whenReady(query.runWith(Sink.seq[IceCream])) { result =>
            //         result should not be empty
            //     }
            // }
        }

        "work with Akka Streams and Context (async)" in {
            val query: Source[IceCream, NotUsed] = "SELECT * FROM ice_creams".toCQLAsync.prepareUnit
                .as[IceCream]
                .asReadSource()

            val insert =
                "INSERT INTO ice_creams(name, numCherries, cone) VALUES(?, ?, ?)".toCQLAsync
                    .prepare[String, Int, Boolean]
                    .from[IceCream]
                    .asWriteFlowWithContext[String](writeSettings)

            testStreamWithContext(ijes, query, insert)(ij => ij -> ij.name)

            val queryName: Source[IceCream, NotUsed] =
                "SELECT * FROM ice_creams WHERE name = ?".toCQLAsync
                    .prepare[String]
                    .as[IceCream]
                    .asReadSource("vanilla")

            val queryStream = queryName.runWith(Sink.seq[IceCream])
            whenReady(queryStream) { result =>
                result should not be empty
            }

            // withClue("use pager operator") {
            //     val query = "SELECT * FROM ice_creams".toCQLAsync.prepareUnit.as[IceCream]

            //     val pager0 = query.pager().asReadSource(pageSize)

            //     val (state0, rows0) = pager0.toMat(Sink.seq[IceCream])(Keep.both).run()
            //     val (page0State, page0) = whenReady(rows0.flatMap(r => state0.map(r -> _))) {
            //     case (rows, state) =>
            //         rows should have size pageSize

            //         state -> rows
            //     }

            //     val pager1 = query.pager(page0State.value).asReadSource(pageSize)

            //     val (state2, rows2) = pager1.toMat(Sink.seq[IceCream])(Keep.both).run()
            //     whenReady(rows2.flatMap(r => state2.map(r -> _))) { case (rows, state) =>
            //     rows should have size 1
            //     rows.toSet should not equal (page0.toSet)

            //     state should not be page0State
            //     }
            // }

            // withClue("handle an empty operator") {
            //     val query = "SELECT * FROM ice_creams WHERE name = 'crema del cielo'".toCQLAsync.prepareUnit
            //     .as[IceCream]

            //     val pager0          = query.pager().asReadSource(pageSize)
            //     val (state0, rows0) = pager0.toMat(Sink.seq[IceCream])(Keep.both).run()

            //     whenReady(rows0.flatMap(r => state0.map(r -> _))) { case (rows, state) =>
            //     rows shouldBe empty

            //     state should not be defined
            //     }
            // }
        }

        "perform batched writes with Akka Stream (async)" in {
            val query: Source[IceCream, NotUsed] = "SELECT * FROM ice_creams".toCQLAsync.prepareUnit
                .as[IceCream]
                .asReadSource()

            val batchedInsert: Sink[IceCream, Future[Done]] =
                "INSERT INTO ice_creams(name, numCherries, cone) VALUES(?, ?, ?)".toCQLAsync
                    .prepare[String, Int, Boolean]
                    .from[IceCream]
                    .asWriteSinkBatched(writeSettings, _.name.charAt(0))

            testStream(batchIjs, query, batchedInsert)(identity)
        }
    }

    private def withSession(fn: CqlSession => Unit)(using ExecutionContext): Unit =
        whenReady(as.underlying().map(fn))(_ => /* Do nothing, test should be inside */ ())

    /** Inserts data with a sink, and reads it back with source to compare it
      */
    private def testStream[T, U](
        data: immutable.Iterable[T],
        source: Source[T, NotUsed],
        sink: Sink[U, Future[Done]]
    )(fn: T => U): Unit =
        import system.dispatcher

        val tx =
            for
                // Write to DB
                _ <- Source(data).map(fn).runWith(sink)
                // Read from DB
                values <- source.runWith(Sink.seq)
            yield values

        whenReady(tx) { dbValues =>
            dbValues.toSet shouldBe data.toSet
        }
    end testStream

    /** Inserts data with a sink, and reads it back with source to compare it
      */
    private def testStreamWithContext[T, U, Ctx](
        data: immutable.Iterable[T],
        source: Source[T, NotUsed],
        flowWithContext: FlowWithContext[U, Ctx, U, Ctx, NotUsed]
    )(fn: T => (U, Ctx)): Unit =
        import system.dispatcher

        val tx =
            for
                // Write to DB
                _ <- Source(data).map(fn).via(flowWithContext).runWith(Sink.ignore)
                // Read from DB
                values <- source.runWith(Sink.seq)
            yield values

        whenReady(tx) { dbValues =>
            dbValues.toSet shouldBe data.toSet
        }
    end testStreamWithContext

    override def beforeAll(): Unit =
        super.beforeAll()
        executeDDL("""CREATE TABLE IF NOT EXISTS ice_creams(
                    |  name         TEXT PRIMARY KEY,
                    |  numCherries  INT,
                    |  cone         BOOLEAN
                    |)""".stripMargin)
    end beforeAll

    private def cassandraConfig: Config = ConfigFactory
        .parseString(s"""
                        |datastax-java-driver.basic {
                        |  contact-points = ["$contactPoint"]
                        |  session-keyspace = "$keyspace"
                        |  load-balancing-policy.local-datacenter = "datacenter1"
                        |}""".stripMargin)
        .withFallback(ConfigFactory.load())

end PackageSpec

object PackageSpec:
    case class IceCream(name: String, numCherries: Int, cone: Boolean) derives RowMapper

    object IceCream:
        given Adapter[IceCream, (String, Int, Boolean)] = Adapter[IceCream]
    end IceCream

    private val writeSettings = CassandraWriteSettings.defaults

    private val ijes = List(
      IceCream("vanilla", numCherries    = 2, cone  = true),
      IceCream("chocolate", numCherries  = 0, cone  = false),
      IceCream("the answer", numCherries = 42, cone = true)
    )

    private val batchIjs = (0 until writeSettings.maxBatchSize).map { i =>
        val original = ijes(i % ijes.size)
        original.copy(name = s"${original.name} $i")
    }.toSet

end PackageSpec
