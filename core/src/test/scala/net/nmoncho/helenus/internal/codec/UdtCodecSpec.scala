/*
 * Copyright 2021 the original author or authors
 *
 * SPDX-License-Identifier: MIT
 */

package net.nmoncho.helenus
package internal.codec

import java.util.UUID

import com.datastax.oss.driver.api.core.ProtocolVersion
import com.datastax.oss.driver.api.core.cql.Row
import com.datastax.oss.driver.api.core.servererrors.ServerError
import net.nmoncho.helenus.api.ColumnNamingScheme
import net.nmoncho.helenus.api.SnakeCase
import net.nmoncho.helenus.api.`type`.codec.Codec
import net.nmoncho.helenus.api.`type`.codec.Codecs
import net.nmoncho.helenus.api.`type`.codec.UDTCodec
import net.nmoncho.helenus.internal.codec.udt.IdenticalUDTCodec
import net.nmoncho.helenus.internal.codec.udt.NonIdenticalUDTCodec
import net.nmoncho.helenus.internal.codec.udt.UnifiedUDTCodec
import net.nmoncho.helenus.utils.CassandraSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class UdtCodecSpec extends AnyWordSpec with Matchers:
    import UdtCodecSpec.*

    "UdtCodec" should {

        "round-trip (encode-decode) properly" in {
            val codec   = summon[Codec[IceCream]]
            val sundae  = IceCream("Sundae", 3, cone = false)
            val vanilla = IceCream("Vanilla", 3, cone = true)

            val round =
                codec.decode(codec.encode(sundae, ProtocolVersion.DEFAULT), ProtocolVersion.DEFAULT)

            round shouldBe sundae
            round should not be vanilla
        }

        "encode-decode a case class with a tuple" in {
            val codec = summon[Codec[IceCream2]]

            val sundae  = IceCream2("Sundae", 3, cone = false, 1 -> 2)
            val vanilla = IceCream2("Vanilla", 3, cone = true, 2 -> 1)

            val round =
                codec.decode(codec.encode(sundae, ProtocolVersion.DEFAULT), ProtocolVersion.DEFAULT)

            round shouldBe sundae
            round should not be vanilla
        }

        "encode-decode a case class with a options" in {
            val codec = summon[Codec[IceCream3]]

            withClue("with defined values") {
                val sundae  = IceCream3("Sundae", 3, cone = Some(false), Some(1 -> 2))
                val vanilla = IceCream3("Vanilla", 3, cone = Some(true), Some(2 -> 1))

                val round =
                    codec.decode(codec.encode(sundae, ProtocolVersion.DEFAULT), ProtocolVersion.DEFAULT)

                round shouldBe sundae
                round should not be vanilla
            }

            withClue("with not defined values") {
                val sundae  = IceCream3("Sundae", 3, cone = None, None)
                val vanilla = IceCream3("Vanilla", 3, cone = Some(true), Some(2 -> 1))

                val round =
                    codec.decode(codec.encode(sundae, ProtocolVersion.DEFAULT), ProtocolVersion.DEFAULT)

                round shouldBe sundae
                round should not be vanilla
            }
        }

        // "create a codec from fields" in {
        //     implicit val colMapper: ColumnNamingScheme = DefaultColumnNamingScheme
        //     val codec: TypeCodec[IceCream3] =
        //         Codec.udtFromFields[IceCream3]("", "", true)(_.name, _.cone, _.count, _.numCherries)

        //     val sundae  = IceCream3("Sundae", 3, cone = Some(false), Some(1 -> 2))
        //     val vanilla = IceCream3("Vanilla", 3, cone = Some(true), Some(2 -> 1))

        //     val round =
        //         codec.decode(codec.encode(sundae, ProtocolVersion.DEFAULT), ProtocolVersion.DEFAULT)

        //     round shouldBe sundae
        //     round should not be vanilla
        // }

        "format-parse" in {
            val codec = summon[Codec[IceCream]]

            val vanilla = IceCream("Vanilla", 3, cone = true)

            codec.parse(codec.format(vanilla)) shouldEqual vanilla
        }

    }
end UdtCodecSpec

object UdtCodecSpec:
    case class IceCream(name: String, numCherries: Int, cone: Boolean) derives UDTCodec

    case class IceCream2(name: String, numCherries: Int, cone: Boolean, count: (Int, Int)) derives UDTCodec

    case class IceCream3(
        name: String,
        numCherries: Int,
        cone: Option[Boolean],
        count: Option[(Int, Int)]
    ) derives UDTCodec:
        val shouldBeIgnored: String = "bar"
    end IceCream3

end UdtCodecSpec

class CassandraUdtCodecSpec extends AnyWordSpec with Matchers with CassandraSpec:
    import CassandraUdtCodecSpec.*
    import scala.jdk.OptionConverters.*

    override protected lazy val keyspace: String = "udt_codec_tests"

    "UdtCodec" should {
        "work with Cassandra" in {
            val codec = summon[Codec[IceCream]]

            val id = UUID.randomUUID()
            query(id) shouldBe empty

            val ice = IceCream("Vanilla", 2, cone = false)
            insert(id, ice, codec)

            val rowOpt = query(id)
            rowOpt shouldBe defined
            rowOpt.foreach(row => row.get("ice", codec) shouldBe ice)
        }

        "work when fields are in different order (with session)" in {
            val codec = summon[Codec[IceCreamShuffled]]
            val ice   = IceCreamShuffled(2, cone = false, "Vanilla")

            withClue("fail before adaptation") {
                val exception = intercept[ServerError](
                  insert(UUID.randomUUID(), ice, codec)
                )

                exception.getMessage should include("Expected 4 or 0 byte int")
            }

            // this adaptation would be done after a statement is prepared,
            // so we're hacking this in the middle for this test
            val udt = session.sessionKeyspace.flatMap(_.getUserDefinedType("ice_cream").toScala).get
            codec.asInstanceOf[UnifiedUDTCodec[?]].adapt(udt)

            val id = UUID.randomUUID()
            query(id) shouldBe empty

            insert(id, ice, codec)

            val rowOpt = query(id)
            rowOpt shouldBe defined
            rowOpt.foreach(row => row.get("ice", codec) shouldBe ice)
        }

        // "work when fields are in different order (with fields)" in {
        //     implicit val colMapper: ColumnNamingScheme = SnakeCase
        //     val codec: TypeCodec[IceCreamShuffled] =
        //         Codec.udtFromFields[IceCreamShuffled]("", "", true)(_.name, _.numCherries, _.cone)

        //     val id = UUID.randomUUID()
        //     query(id) shouldBe empty

        //     val ice = IceCreamShuffled(2, cone = false, "Vanilla")
        //     insert(id, ice, codec)

        //     val rowOpt = query(id)
        //     rowOpt shouldBe defined
        //     rowOpt.foreach(row => row.get("ice", codec) shouldBe ice)
        // }

        "fail on invalid mapping" in {
            val codec = summon[Codec[IceCreamInvalid]]
            val ice   = IceCreamInvalid(2, cone = false, "Vanilla")

            withClue("fail before adaptation") {
                val exception = intercept[ServerError](
                  insert(UUID.randomUUID(), ice, codec)
                )

                exception.getMessage should include("Expected 4 or 0 byte int")
            }

            // this adaptation would be done after a statement is prepared,
            // so we're hacking this in the middle for this test
            val udt = session.sessionKeyspace.flatMap(_.getUserDefinedType("ice_cream").toScala).get
            codec.asInstanceOf[UnifiedUDTCodec[?]].adapt(udt)

            val exception = intercept[IllegalArgumentException](
              insert(UUID.randomUUID(), ice, codec)
            )

            exception.getMessage should include("cherries_number is not a field in this UDT")
        }

        "be registered on the session" in {
            val codec = summon[Codec[UdtCodecSpec.IceCream3]]

            session.registerCodecs(codec).isSuccess shouldBe true
        }
    }

    private def query(id: UUID): Option[Row] =
        val rs = session.execute(s"SELECT * from udt_table WHERE id = $id")
        Option(rs.one())

    private def insert[T](id: UUID, ice: T, codec: Codec[T]): Unit =
        val pstmt = session.prepare("INSERT INTO udt_table(id, ice) VALUES (?, ?)")
        val bstmt = pstmt
            .bind()
            .set(0, id, Codecs.uuidCodec)
            .set(1, ice, codec)
        session.execute(bstmt)
    end insert

    override def beforeAll(): Unit =
        super.beforeAll()
        executeDDL(
          "CREATE TYPE IF NOT EXISTS ice_cream (name TEXT, num_cherries INT, cone BOOLEAN)"
        )
        executeDDL("""CREATE TABLE IF NOT EXISTS udt_table(
        |   id      UUID,
        |   ice     ice_cream,
        |   PRIMARY KEY (id)
        |)""".stripMargin)
    end beforeAll

end CassandraUdtCodecSpec

object CassandraUdtCodecSpec:
    given ColumnNamingScheme = SnakeCase

    case class IceCream(name: String, numCherries: Int, cone: Boolean) derives UDTCodec

    case class IceCreamShuffled(numCherries: Int, cone: Boolean, name: String) derives UDTCodec

    case class IceCreamInvalid(cherriesNumber: Int, cone: Boolean, name: String) derives UDTCodec

end CassandraUdtCodecSpec
