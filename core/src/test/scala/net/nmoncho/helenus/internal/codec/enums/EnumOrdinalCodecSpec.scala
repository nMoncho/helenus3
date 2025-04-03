/*
 * Copyright 2021 the original author or authors
 *
 * SPDX-License-Identifier: MIT
 */

package net.nmoncho.helenus
package internal.codec.enums

import com.datastax.oss.driver.api.core.`type`.reflect.GenericType
import net.nmoncho.helenus.api.`type`.codec.Codec
import net.nmoncho.helenus.internal.codec.CodecSpecBase
import net.nmoncho.helenus.internal.codec.enums.EnumOrdinalCodecSpec.Fingers
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class EnumOrdinalCodecSpec extends AnyWordSpec with Matchers with CodecSpecBase[Fingers]:

    override protected val codec: Codec[Fingers] = Codec[Fingers]

    "EnumOrdinalCodecSpec" should {
        "encode" in {
            encode(Fingers.Ring) shouldBe Some("0x00000003")
            encode(Fingers.Index) shouldBe Some("0x00000001")
            encode(Fingers.Little) shouldBe Some("0x00000004")
        }

        "decode" in {
            decode("0x00000003") shouldBe Some(Fingers.Ring)
            decode("0x00000001") shouldBe Some(Fingers.Index)
            decode("0x00000004") shouldBe Some(Fingers.Little)
        }

        "fail to decode wrong value" in {
            intercept[NoSuchElementException] {
                decode("0x52696e6e")
            }
        }

        "format" in {
            format(Fingers.Ring) shouldBe "3"
            format(Fingers.Index) shouldBe "1"
            format(null) shouldBe "NULL"
        }

        "parse" in {
            parse("3") shouldBe Fingers.Ring
            parse("1") shouldBe Fingers.Index
            parse("null") shouldBe null
            parse("") shouldBe null
            parse(null) shouldBe null
        }

        "fail to parse invalid input" in {
            intercept[IllegalArgumentException] {
                parse("not a finger")
            }
        }

        "accept generic type" in {
            codec.accepts(GenericType.of(classOf[Fingers])) shouldBe true
            codec.accepts(GenericType.of(classOf[Float])) shouldBe false
        }

        "accept raw type" in {
            codec.accepts(classOf[Fingers]) shouldBe true
            codec.accepts(classOf[Float]) shouldBe false
        }

        "accept objects" in {
            codec.accepts(Fingers.Index) shouldBe true
            codec.accepts(Double.MaxValue) shouldBe false
        }
    }
end EnumOrdinalCodecSpec

object EnumOrdinalCodecSpec:

    enum Fingers derives OrdinalEnumCodec:
        case Thumb, Index, Middle, Ring, Little

end EnumOrdinalCodecSpec
