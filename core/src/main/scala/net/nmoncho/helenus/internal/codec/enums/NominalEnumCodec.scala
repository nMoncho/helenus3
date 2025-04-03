/*
 * Copyright 2021 the original author or authors
 *
 * SPDX-License-Identifier: MIT
 */

package net.nmoncho.helenus.internal.codec
package enums

import scala.deriving.Mirror
import scala.reflect.ClassTag

import com.datastax.oss.driver.api.core.`type`.codec.TypeCodecs
import com.datastax.oss.driver.api.core.`type`.reflect.GenericType

class NominalEnumCodec[Enum <: scala.reflect.Enum](clazz: Class[Enum], enumeration: String => Enum)
    extends MappingCodec[String, Enum](TypeCodecs.TEXT, GenericType.of(clazz)):

    override def innerToOuter(value: String): Enum =
        if value == null then null.asInstanceOf[Enum] else enumeration(value)

    override def outerToInner(value: Enum): String =
        if value == null then null else value.toString()

    override def toString: String = s"NominalEnumCodec[${clazz.toString}]"

end NominalEnumCodec

object NominalEnumCodec:

    inline def allInstances[EnumValue <: Tuple, Enum <: scala.reflect.Enum]: Map[String, Enum] =
        import scala.compiletime.*

        inline erasedValue[EnumValue] match
            case _: EmptyTuple => Map.empty
            case _: (t *: ts) =>
                val value = summonInline[ValueOf[t]].value.asInstanceOf[Enum]
                allInstances[ts, Enum] + (value.toString() -> value)
        end match
    end allInstances

    inline def derived[Enum <: scala.reflect.Enum](
        using m: Mirror.SumOf[Enum],
        tag: ClassTag[Enum]
    ): NominalEnumCodec[Enum] =
        new NominalEnumCodec(
          tag.runtimeClass.asInstanceOf[Class[Enum]],
          allInstances[m.MirroredElemTypes, m.MirroredType].apply
        )

end NominalEnumCodec
