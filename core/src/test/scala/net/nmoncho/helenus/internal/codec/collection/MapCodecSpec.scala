/*
 * Copyright 2021 the original author or authors
 *
 * SPDX-License-Identifier: MIT
 */

package net.nmoncho.helenus
package internal.codec.collection

import scala.collection.Factory
import scala.collection.mutable as mutablecoll

import com.datastax.oss.driver.api.core.ProtocolVersion
import net.nmoncho.helenus.api.`type`.codec.Codec
import net.nmoncho.helenus.internal.codec.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

abstract class AbstractMapCodecSpec[Coll[_, _] <: scala.collection.Map[?, ?]](name: String)(
    implicit intFactory: Factory[(String, Int), Coll[String, Int]],
    stringFactory: Factory[(String, String), Coll[String, String]]
) extends AnyWordSpec
    with Matchers
    with CodecSpecBase[Coll[String, Int]]:

    override protected val codec: Codec[Coll[String, Int]]
    protected val sCodec: Codec[Coll[String, String]]

    private val emptyMap  = intFactory.newBuilder.result()
    private val fooBarBaz =
        val builder = intFactory.newBuilder
        builder ++= Seq("Foo" -> 1, "Bar" -> 2, "Baz" -> 3)
        builder.result()

    s"MapCodec (implementation = $name)" should {
        "encode-decode" in {
            encode(null.asInstanceOf[Coll[String, Int]]) shouldBe None
            encode(emptyMap) shouldBe Some("0x00000000")

            decode(null) shouldBe Some(emptyMap)
            decode("0x00000000") shouldBe Some(emptyMap)

            decode(encode(fooBarBaz).get) shouldBe Some(fooBarBaz)
        }

        "format-parse" in {
            format(null.asInstanceOf[Coll[String, Int]]) shouldBe NULL
            format(emptyMap) shouldBe "{}"

            parse("") shouldBe null
            parse(NULL) shouldBe null
            parse(NULL.toLowerCase) shouldBe null
            parse("{}") shouldBe Map.empty[String, Int]

            parse("{'Foo':1,'Bar':2,'Baz':3}") shouldBe fooBarBaz
            parse(" { 'Foo' : 1 , 'Bar' : 2 , 'Baz' : 3 } ") shouldBe fooBarBaz

            parse(format(fooBarBaz)) shouldBe fooBarBaz
        }

        "fail to encode" in {
            val nullKey =
                val builder = stringFactory.newBuilder
                builder += null.asInstanceOf[String] -> "1"
                builder.result()
            val nullValue =
                val builder = stringFactory.newBuilder
                builder += "foo" -> null.asInstanceOf[String]
                builder.result()

            intercept[IllegalArgumentException](sCodec.encode(nullKey, ProtocolVersion.DEFAULT))
            intercept[IllegalArgumentException](sCodec.encode(nullValue, ProtocolVersion.DEFAULT))
        }

        "fail to parse invalid input" in {
            val invalid = Seq(
              "Foo:1,Bar:2,Baz:3}",
              "{Foo:1,Bar:2,Baz:3",
              "{{Foo:1,Bar:2,Baz:3}",
              "{Foo,Bar:2,Baz:3}",
              "{Foo:1 Bar:2,Baz:3}"
            )

            invalid.foreach { input =>
                intercept[IllegalArgumentException] {
                    parse(input)
                }
            }
        }

        "accept generic type" in {
            codec.accepts(codec.getJavaType) shouldBe true
            codec.accepts(sCodec.getJavaType) shouldBe false
        }

        "accept objects" in {
            val anotherValue =
                val builder = stringFactory.newBuilder
                builder += "foo" -> "bar"
                builder.result()

            codec.accepts(fooBarBaz) shouldBe true
            codec.accepts(anotherValue) shouldBe false
        }
    }

end AbstractMapCodecSpec

class MapCodecSpec extends AbstractMapCodecSpec[Map]("MapCodec"):
    override protected val codec: Codec[Map[String, Int]] =
        Codec[Map[String, Int]]

    override protected val sCodec: Codec[Map[String, String]] =
        Codec[Map[String, String]]

end MapCodecSpec

class MutableMapCodecSpec extends AbstractMapCodecSpec[mutablecoll.Map]("MutableMapCodec"):

    override protected val codec: Codec[mutablecoll.Map[String, Int]] =
        Codec[mutablecoll.Map[String, Int]]

    override protected val sCodec: Codec[mutablecoll.Map[String, String]] =
        Codec[mutablecoll.Map[String, String]]

end MutableMapCodecSpec
