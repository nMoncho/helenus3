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
