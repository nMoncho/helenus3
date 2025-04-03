/*
 * Copyright 2021 the original author or authors
 *
 * SPDX-License-Identifier: MIT
 */

package net.nmoncho.helenus
package internal.codec

import net.nmoncho.helenus.api.`type`.codec.Codec
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class MappedCodecSpec extends AnyWordSpec with CodecSpecBase[Int] with Matchers:

    override protected val codec: Codec[Int] =
        Codec.mappingCodec[String, Int](_.toInt, _.toString)

    "MappingCodec" should {
        "encode" in {
            encode(1) shouldBe Some("0x31")
        }

        "decode" in {
            decode("0x31") shouldBe Some(1)
        }

        "format" in {
            format(1) shouldBe "'1'"
        }

        "parse" in {
            parse("'1'") shouldBe 1
        }
    }

end MappedCodecSpec
