/*
 * Copyright (c) 2021 the original author or authors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
 * IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
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
