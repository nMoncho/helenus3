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

class SetCodec[T](inner: TypeCodec[T], frozen: Boolean)
    extends AbstractSetCodec[T, Set](inner, frozen):

    override val getJavaType: GenericType[Set[T]] =
        GenericType
            .of(new ScalaTypeToken[Set[T]] {}
                .where(new TypeParameter[T] {}, inner.getJavaType().getType())
                .getType())
            .asInstanceOf[GenericType[Set[T]]]

    override def toString: String = s"SetCodec[${inner.getCqlType.toString}]"
end SetCodec

object SetCodec:
    def apply[T](inner: TypeCodec[T], frozen: Boolean): SetCodec[T] = new SetCodec[T](inner, frozen)

    def frozen[T](inner: TypeCodec[T]): SetCodec[T] = SetCodec[T](inner, frozen = true)
end SetCodec
