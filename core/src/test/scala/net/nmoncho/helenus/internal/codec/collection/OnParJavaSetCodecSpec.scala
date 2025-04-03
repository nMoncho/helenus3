/*
 * Copyright 2021 the original author or authors
 *
 * SPDX-License-Identifier: MIT
 */

package net.nmoncho.helenus
package internal.codec.collection

import java.util

import scala.collection.Factory
import scala.collection.mutable as mutablecoll

import com.datastax.oss.driver.api.core.`type`.codec.TypeCodec
import com.datastax.oss.driver.api.core.`type`.codec.TypeCodecs
import net.nmoncho.helenus.api.`type`.codec.Codec

abstract class OnParJavaSetCodecSpec[ScalaColl[_] <: scala.collection.Set[?]](name: String)(
    implicit factory: Factory[String, ScalaColl[String]]
) extends OnParJavaCodecSpec[ScalaColl, java.util.Set](name):

    override val javaCodec: TypeCodec[java.util.Set[String]] = TypeCodecs.setOf(TypeCodecs.TEXT)

end OnParJavaSetCodecSpec

class OnParMutableSetCodecSpec extends OnParJavaSetCodecSpec[mutablecoll.Set]("MutableSetCodec"):

    import scala.jdk.CollectionConverters.*

    override protected val codec: Codec[mutablecoll.Set[String]] =
        Codec[mutablecoll.Set[String]]

    override def toJava(t: mutablecoll.Set[String]): util.Set[String] =
        if t == null then null else t.asJava

end OnParMutableSetCodecSpec

class OnParSetCodecSpec extends OnParJavaSetCodecSpec[Set]("SetCodec"):

    import scala.jdk.CollectionConverters.*

    override protected val codec: Codec[Set[String]] =
        Codec[Set[String]]

    override def toJava(t: Set[String]): util.Set[String] =
        if t == null then null else t.asJava
end OnParSetCodecSpec
