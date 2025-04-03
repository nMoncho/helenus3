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

abstract class OnParJavaListCodecSpec[ScalaColl[_] <: scala.collection.Seq[?]](name: String)(
    implicit factory: Factory[String, ScalaColl[String]]
) extends OnParJavaCodecSpec[ScalaColl, java.util.List](name):

    override val javaCodec: TypeCodec[java.util.List[String]] = TypeCodecs.listOf(TypeCodecs.TEXT)

end OnParJavaListCodecSpec

class OnParBufferCodecSpec extends OnParJavaListCodecSpec[mutablecoll.Buffer]("BufferCodec"):

    import scala.jdk.CollectionConverters.*

    override protected val codec: Codec[mutablecoll.Buffer[String]] =
        Codec[mutablecoll.Buffer[String]]

    override def toJava(t: mutablecoll.Buffer[String]): util.List[String] =
        if t == null then null else t.asJava
end OnParBufferCodecSpec

class OnParIndexedSeqCodecSpec
    extends OnParJavaListCodecSpec[mutablecoll.IndexedSeq]("IndexedSeqCodec"):

    import scala.jdk.CollectionConverters.*

    override protected val codec: Codec[mutablecoll.IndexedSeq[String]] =
        Codec[mutablecoll.IndexedSeq[String]]

    override def toJava(t: mutablecoll.IndexedSeq[String]): util.List[String] =
        if t == null then null else t.asJava
end OnParIndexedSeqCodecSpec

class OnParListCodecSpec extends OnParJavaListCodecSpec[List]("ListCodec"):

    import scala.jdk.CollectionConverters.*

    override protected val codec: Codec[List[String]] = Codec[List[String]]

    override def toJava(t: List[String]): util.List[String] = if t == null then null else t.asJava
end OnParListCodecSpec

class OnParSeqCodecSpec extends OnParJavaListCodecSpec[Seq]("SeqCodec"):

    import scala.jdk.CollectionConverters.*

    override protected val codec: Codec[Seq[String]] = Codec[Seq[String]]

    override def toJava(t: Seq[String]): util.List[String] = if t == null then null else t.asJava
end OnParSeqCodecSpec

class OnParVectorCodecSpec extends OnParJavaListCodecSpec[Vector]("VectorCodec"):

    import scala.jdk.CollectionConverters.*

    override protected val codec: Codec[Vector[String]] = Codec[Vector[String]]

    override def toJava(t: Vector[String]): util.List[String] = if t == null then null else t.asJava
end OnParVectorCodecSpec
