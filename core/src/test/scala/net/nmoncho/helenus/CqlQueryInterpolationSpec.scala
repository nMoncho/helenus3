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

import java.util.UUID

import com.datastax.oss.driver.api.core.CqlSession
import net.nmoncho.helenus.utils.CassandraSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class CqlQueryInterpolationSpec extends AnyWordSpec with Matchers with CassandraSpec:

    import Keyspace.*

    private implicit lazy val cqlSession: CqlSession = session

    "CQL Query interpolation" should {
        val id   = UUID.fromString("81a410a2-dc22-4ef9-85d7-6a7a0f64232f")
        val age  = 42
        val name = "helenus"

        "run synchronously" in {
            withClue("return on empty table") {
                def query(id: UUID) =
                    cql"SELECT * FROM ${InterpolationTest.tableName} WHERE ${InterpolationTest.id} = $id"

                query(id).execute().nextOption() should not be defined
            }

            withClue("return on empty table") {
                val insert =
                    cql"INSERT INTO ${InterpolationTest.tableName}(${InterpolationTest.id}, ${InterpolationTest
                            .age}, ${InterpolationTest.name}) VALUES ($id, $age, $name)"
                val query =
                    cql"SELECT * FROM ${InterpolationTest.tableName} WHERE ${InterpolationTest.id} = $id"

                insert.execute()
                val row = query.execute().nextOption()

                row shouldBe defined
                row.foreach(_.getUuid(0) shouldBe id)
            }

            withClue("and adapt results with a RowMapper") {
                val query =
                    cql"SELECT * FROM ${InterpolationTest.tableName} WHERE ${InterpolationTest.id} = $id"
                        .as[(UUID, Int, String)]

                val row = query.execute().nextOption()

                row shouldBe defined
                row.foreach { case (rowId, _, _) => rowId shouldBe id }
            }
        }

    }

    override def beforeAll(): Unit =
        super.beforeAll()
        import InterpolationTest.*

        executeDDL(
          s"""CREATE TABLE IF NOT EXISTS $tableName(
            |   $id     UUID,
            |   $age    INT,
            |   $name   TEXT,
            |   PRIMARY KEY ($id, $age)
            |)""".stripMargin
        )
    end beforeAll

end CqlQueryInterpolationSpec
