/*
 * Copyright 2021 the original author or authors
 *
 * SPDX-License-Identifier: MIT
 */

package net.nmoncho.helenus

import scala.util.Success

import com.datastax.oss.driver.internal.core.`type`.DefaultListType
import com.datastax.oss.driver.internal.core.`type`.PrimitiveType
import com.datastax.oss.protocol.internal.ProtocolConstants
import net.nmoncho.helenus.api.`type`.codec.Codec
import net.nmoncho.helenus.api.`type`.codec.UDTCodec
import net.nmoncho.helenus.internal.codec.udt.IdenticalUDTCodec
import net.nmoncho.helenus.models.Address
import net.nmoncho.helenus.utils.CassandraSpec
import org.scalatest.OptionValues.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class PackageSpec extends AnyWordSpec with Matchers with CassandraSpec:

    "Package" should {

        "get keyspace by name" in {
            val sessionKeyspace = session.keyspace(keyspace)
            sessionKeyspace shouldBe defined
            sessionKeyspace.value.getName.asInternal() shouldEqual keyspace

            sessionKeyspace shouldEqual session.sessionKeyspace

            session.keyspace("another_keyspace") should not be defined
        }

        "get Execution Profiles" in {
            val defaultProfile = session.executionProfile("default")
            defaultProfile shouldBe defined
            defaultProfile.value.getName shouldEqual "default"

            session.executionProfile("another-profile") should not be defined
        }

        "register codecs" in {
            val listStringCodec = Codec[List[String]]

            session.registerCodecs(listStringCodec) shouldBe a[Success[?]]

            session.getContext.getCodecRegistry
                .codecFor(
                  new DefaultListType(new PrimitiveType(ProtocolConstants.DataType.VARCHAR), true)
                ) shouldBe listStringCodec
        }

        "register UDT codecs" in {
            val keyspace = session.sessionKeyspace.map(_.getName.asInternal()).getOrElse("")

            withClue("registering codec with a keyspace") {
                session.registerCodecs(
                  UDTCodec.derive[Address](keyspace = keyspace, name = "address")
                ) shouldBe a[Success[?]]
            }

            withClue("registering codec without a specific keyspace") {
                session.registerCodecs(summon[Codec[Address]]) shouldBe a[Success[?]]
            }
        }
    }

end PackageSpec
