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

import com.datastax.oss.driver.api.core.CqlSession
import com.datastax.oss.driver.api.core.cql.Row
import net.nmoncho.helenus.api.RowMapper.ColumnMapper
import net.nmoncho.helenus.models.Address
import net.nmoncho.helenus.models.Hotel
import net.nmoncho.helenus.utils.CassandraSpec
import net.nmoncho.helenus.utils.HotelsTestData
import net.nmoncho.helenus.utils.HotelsTestData.Hotels
import org.scalatest.OptionValues.convertOptionToValuable
import org.scalatest.concurrent.Eventually
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.Seconds
import org.scalatest.time.Span
import org.scalatest.wordspec.AnyWordSpec

class RowMapperSpec
    extends AnyWordSpec
    with Matchers
    with Eventually
    with CassandraSpec
    with ScalaFutures:

    private implicit lazy val cqlSession: CqlSession = session

    // We create the mapper here to avoid testing the generic derivation
    implicit val rowMapper: RowMapper[Hotel] = (row: Row) =>
        Hotel(
          row.getCol[String]("id"),
          row.getCol[String]("name"),
          row.getCol[String]("phone"),
          Address.Empty,
          Set.empty[String]
        )

    "RowMapper" should {
        "map rows" in {
            // this test if when users don't use the short-hand syntax
            val query = "SELECT name FROM hotels WHERE id = ?".toCQL
                .prepare[String]
                .as[String]

            query(Hotels.h3.id)
                .execute()
                .nextOption() shouldBe Some(Hotels.h3.name)
        }
    }

    override implicit def patienceConfig: PatienceConfig = PatienceConfig(Span(6, Seconds))

    override def beforeAll(): Unit =
        super.beforeAll()
        executeFile("hotels.cql")
        HotelsTestData.insertTestData()

    override def afterEach(): Unit = {
        // Don't truncate keyspace
    }

end RowMapperSpec
