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

class VectorCodec[T](inner: TypeCodec[T], frozen: Boolean)
    extends AbstractSeqCodec[T, Vector](inner, frozen):

    override val getJavaType: GenericType[Vector[T]] =
        GenericType
            .of(new ScalaTypeToken[Vector[T]] {}
                .where(new TypeParameter[T] {}, inner.getJavaType().getType())
                .getType())
            .asInstanceOf[GenericType[Vector[T]]]

    override def toString: String = s"VectorCodec[${inner.getCqlType.toString}]"
end VectorCodec

object VectorCodec:
    def apply[T](inner: TypeCodec[T], frozen: Boolean): VectorCodec[T] =
        new VectorCodec(inner, frozen)

    def frozen[T](inner: TypeCodec[T]): VectorCodec[T] = VectorCodec[T](inner, frozen = true)
end VectorCodec
