/*
 * Copyright 2021 the original author or authors
 *
 * SPDX-License-Identifier: MIT
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
