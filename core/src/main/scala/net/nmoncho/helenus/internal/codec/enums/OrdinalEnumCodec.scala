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

class OrdinalEnumCodec[Enum <: scala.reflect.Enum](clazz: Class[Enum], enumeration: Int => Enum)
    extends MappingCodec[java.lang.Integer, Enum](TypeCodecs.INT, GenericType.of(clazz)):

    override def innerToOuter(value: java.lang.Integer): Enum =
        if value == null then null.asInstanceOf[Enum] else enumeration(value)

    override def outerToInner(value: Enum): java.lang.Integer =
        if value == null then null else value.ordinal

    override def toString: String = s"OrdinalEnumCodec[${clazz.toString}]"

end OrdinalEnumCodec

object OrdinalEnumCodec:

    inline def allInstances[EnumValue <: Tuple, Enum <: scala.reflect.Enum]: Map[Int, Enum] =
        import scala.compiletime.*

        inline erasedValue[EnumValue] match
            case _: EmptyTuple => Map.empty
            case _: (t *: ts) =>
                val value = summonInline[ValueOf[t]].value.asInstanceOf[Enum]
                allInstances[ts, Enum] + (value.ordinal -> value)
        end match
    end allInstances

    inline def derived[Enum <: scala.reflect.Enum](
        using m: Mirror.SumOf[Enum],
        tag: ClassTag[Enum]
    ): OrdinalEnumCodec[Enum] =
        new OrdinalEnumCodec(
          tag.runtimeClass.asInstanceOf[Class[Enum]],
          allInstances[m.MirroredElemTypes, m.MirroredType].apply
        )

end OrdinalEnumCodec
