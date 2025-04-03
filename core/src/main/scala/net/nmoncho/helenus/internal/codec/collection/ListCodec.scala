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

class ListCodec[T](inner: TypeCodec[T], frozen: Boolean)
    extends AbstractSeqCodec[T, List](inner, frozen):

    override val getJavaType: GenericType[List[T]] =
        GenericType
            .of(new ScalaTypeToken[List[T]] {}
                .where(new TypeParameter[T] {}, inner.getJavaType().getType())
                .getType())
            .asInstanceOf[GenericType[List[T]]]

    override def toString: String = s"ListCodec[${inner.getCqlType.toString}]"
end ListCodec

object ListCodec:
    def apply[T](inner: TypeCodec[T], frozen: Boolean): ListCodec[T] =
        new ListCodec(inner, frozen)

    def frozen[T](inner: TypeCodec[T]): ListCodec[T] = ListCodec[T](inner, frozen = true)
end ListCodec
