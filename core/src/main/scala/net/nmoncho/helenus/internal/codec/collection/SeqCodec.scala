/*
 * Copyright 2021 the original author or authors
 *
 * SPDX-License-Identifier: MIT
 */

package net.nmoncho.helenus.internal.codec.collection

import com.datastax.oss.driver.api.core.`type`.codec.TypeCodec
import com.datastax.oss.driver.api.core.`type`.reflect.GenericType
import com.datastax.oss.driver.shaded.guava.common.reflect.ScalaTypeToken
import com.datastax.oss.driver.shaded.guava.common.reflect.TypeParameter

class SeqCodec[T](inner: TypeCodec[T], frozen: Boolean)
    extends AbstractSeqCodec[T, Seq](inner, frozen):

    override val getJavaType: GenericType[Seq[T]] =
        GenericType
            .of(new ScalaTypeToken[Seq[T]] {}
                .where(new TypeParameter[T] {}, inner.getJavaType().getType())
                .getType())
            .asInstanceOf[GenericType[Seq[T]]]

    override def toString: String = s"SeqCodec[${inner.getCqlType.toString}]"
end SeqCodec

object SeqCodec:
    def apply[T](inner: TypeCodec[T], frozen: Boolean): SeqCodec[T] =
        new SeqCodec(inner, frozen)

    def frozen[T](inner: TypeCodec[T]): SeqCodec[T] = SeqCodec[T](inner, frozen = true)
end SeqCodec
