/*
 * Copyright 2021 the original author or authors
 *
 * SPDX-License-Identifier: MIT
 */

package net.nmoncho.helenus
package api.cql

import scala.collection.mutable

import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import com.datastax.oss.driver.api.core.CqlSession
import net.nmoncho.helenus.models.Address
import net.nmoncho.helenus.utils.CassandraSpec
import net.nmoncho.helenus.utils.HotelsTestData
import org.scalatest.concurrent.Eventually
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.Seconds
import org.scalatest.time.Span
import org.scalatest.wordspec.AnyWordSpec
import org.slf4j.LoggerFactory

class ScalaPreparedStatementAritySpec extends AnyWordSpec
    with Matchers
    with Eventually
    with CassandraSpec
    with ScalaFutures:

    import HotelsTestData.*

    import scala.concurrent.ExecutionContext.Implicits.global

    private implicit lazy val cqlSession: CqlSession = session

    "ScalaPreparedStatement" should {
        import MemoryAppender.*

        "check parameter arity on simple parameters" in {
            "SELECT * FROM hotels".toCQL.prepareUnit
            logs shouldBe empty

            "SELECT * FROM hotels WHERE id = ?".toCQL.prepare[String]
            logs shouldBe empty

            "SELECT * FROM hotels WHERE id = ?".toCQL.prepare[Int]
            logs should not be empty
            logs should contain(
              "Invalid PreparedStatement expected parameter with type TEXT at index 0 but got type INT"
            )
        }

        "check parameter arity on UDTs" in {
            "SELECT * FROM hotels WHERE address = ? ALLOW FILTERING".toCQL.prepare[Address]
            logs shouldBe empty
        }

        "check parameter arity on collections" in {
            "UPDATE hotels SET pois = ? WHERE id = ?".toCQL.prepare[Set[String], String]
            logs shouldBe empty

            "UPDATE hotels SET pois = ? WHERE id = ?".toCQL.prepare[List[String], String]
            logs should contain(
              "Invalid PreparedStatement expected parameter with type Set(TEXT, not frozen) at index 0 but got type List(TEXT, frozen)"
            )
        }

        "check parameter arity on simple parameters (async)" in {
            whenReady("SELECT * FROM hotels WHERE id = ?".toCQL.prepareAsync[String])(_ => ())
            logs shouldBe empty

            whenReady("SELECT * FROM hotels WHERE id = ?".toCQL.prepareAsync[Int])(_ => ())
            logs should not be empty
            logs should contain(
              "Invalid PreparedStatement expected parameter with type TEXT at index 0 but got type INT"
            )
        }

        "check parameter arity on UDTs (async)" in {
            whenReady(
              "SELECT * FROM hotels WHERE address = ? ALLOW FILTERING".toCQL.prepareAsync[Address]
            )(_ => ())
            logs shouldBe empty
        }

        "check parameter arity on collections (async)" in {
            whenReady("UPDATE hotels SET pois = ? WHERE id = ?".toCQL.prepareAsync[Set[String], String])(_ => ())
            logs shouldBe empty

            whenReady("UPDATE hotels SET pois = ? WHERE id = ?".toCQL.prepareAsync[List[String], String])(_ => ())
            logs should contain(
              "Invalid PreparedStatement expected parameter with type Set(TEXT, not frozen) at index 0 but got type List(TEXT, frozen)"
            )
        }
    }

    override implicit def patienceConfig: PatienceConfig = PatienceConfig(Span(6, Seconds))

    override def beforeAll(): Unit =
        super.beforeAll()
        executeFile("hotels.cql")
        insertTestData()

        MemoryAppender.setContext(LoggerFactory.getILoggerFactory().asInstanceOf[LoggerContext])
        val logger = LoggerFactory.getLogger(classOf[ScalaPreparedStatement[?, ?]])
        logger match
            case logger: ch.qos.logback.classic.Logger =>
                logger.setLevel(ch.qos.logback.classic.Level.DEBUG)
                logger.addAppender(MemoryAppender)
                MemoryAppender.start()

            case _ =>
                fail("Invalid logger for test")
        end match
    end beforeAll

    override def afterAll(): Unit =
        super.afterAll()

        val logger = LoggerFactory.getLogger(classOf[ScalaPreparedStatement[?, ?]])
        logger match
            case logger: ch.qos.logback.classic.Logger =>
                logger.detachAppender(MemoryAppender)

            case _ =>
                fail("Invalid logger for test")
        end match
    end afterAll

    override def afterEach(): Unit =
        MemoryAppender.reset()

    object MemoryAppender extends AppenderBase[ILoggingEvent]:
        private val events: mutable.Buffer[ILoggingEvent] =
            scala.collection.mutable.Buffer.empty[ILoggingEvent]

        override def append(eventObject: ILoggingEvent): Unit = events.append(eventObject)

        def logs: List[String] = events.map(_.getFormattedMessage).toList

        def reset(): Unit = events.clear()
    end MemoryAppender

end ScalaPreparedStatementAritySpec
