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

class MapCodec[K, V](keyInner: TypeCodec[K], valueInner: TypeCodec[V], frozen: Boolean)
    extends AbstractMapCodec[K, V, Map](keyInner, valueInner, frozen):

    override val getJavaType: GenericType[Map[K, V]] =
        GenericType
            .of(new ScalaTypeToken[Map[K, V]] {}
                .where(new TypeParameter[K] {}, keyInner.getJavaType().getType())
                .where(new TypeParameter[V] {}, valueInner.getJavaType().getType())
                .getType())
            .asInstanceOf[GenericType[Map[K, V]]]

    override def toString: String =
        s"MapCodec[${keyInner.getCqlType.toString}, ${valueInner.getCqlType.toString}]"
end MapCodec

object MapCodec:
    def apply[K, V](
        keyInner: TypeCodec[K],
        valueInner: TypeCodec[V],
        frozen: Boolean
    ): MapCodec[K, V] =
        new MapCodec(keyInner, valueInner, frozen)

    def frozen[K, V](keyInner: TypeCodec[K], valueInner: TypeCodec[V]): MapCodec[K, V] =
        MapCodec(keyInner, valueInner, frozen = true)
end MapCodec
