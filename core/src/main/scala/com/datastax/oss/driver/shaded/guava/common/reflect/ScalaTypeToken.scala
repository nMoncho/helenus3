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

package com.datastax.oss.driver.shaded.guava.common.reflect

import java.lang.reflect.Type

import com.datastax.oss.driver.shaded.guava.common.collect.ImmutableMap
import com.datastax.oss.driver.shaded.guava.common.primitives.Primitives
import com.datastax.oss.driver.shaded.guava.common.reflect.ScalaTypeToken.SimpleScalaTypeToken

abstract class ScalaTypeToken[T](other: Type) extends TypeCapture[T] with Serializable:
    private val runtimeType: Type = if other == null then capture() else other

    def this() = this(null)

    def getType(): Type = runtimeType

    def where[X](typeParam: TypeParameter[X], typeArg: Type): ScalaTypeToken[T] =
        val resolver = new TypeResolver().where(
          ImmutableMap.of(
            new TypeResolver.TypeVariableKey(typeParam.typeVariable),
            ScalaTypeToken.wrap(typeArg)
          )
        )

        new SimpleScalaTypeToken[T](resolver.resolveType(runtimeType))
    end where

end ScalaTypeToken

object ScalaTypeToken:
    class SimpleScalaTypeToken[T](runtimeType: Type) extends ScalaTypeToken[T](runtimeType)

    def isPrimitive(typeArg: Type): Boolean =
        typeArg.isInstanceOf[Class[?]] && typeArg.asInstanceOf[Class[?]].isPrimitive()

    def wrap[T](typeArg: Type): Class[T] =
        if isPrimitive(typeArg) then Primitives.wrap(typeArg.asInstanceOf[Class[T]])
        else typeArg.getClass().asInstanceOf[Class[T]]

end ScalaTypeToken

abstract class ScalaTypeParameter[T] extends TypeParameter[T]
