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

package net.nmoncho.helenus
package internal.codec
package udt

import scala.deriving.Mirror
import scala.reflect.ClassTag

import com.datastax.oss.driver.api.core.CqlSession
import com.datastax.oss.driver.api.core.`type`.DataType
import com.datastax.oss.driver.api.core.`type`.UserDefinedType
import com.datastax.oss.driver.api.core.`type`.reflect.GenericType
import com.datastax.oss.driver.api.core.data.UdtValue
import com.datastax.oss.driver.internal.core.`type`.codec.UdtCodec as DseUdtCodec
import com.datastax.oss.driver.shaded.guava.common.reflect.ScalaTypeToken
import net.nmoncho.helenus.api.ColumnNamingScheme
import net.nmoncho.helenus.api.DefaultColumnNamingScheme
import net.nmoncho.helenus.api.`type`.codec.Codec
import net.nmoncho.helenus.api.`type`.codec.UDTCodec
import net.nmoncho.helenus.internal.Labelling

/** A [[NonIdenticalUDTCodec]] is one that maps a case class to a UDT, both not having the same
  * field order, for example:
  *
  * {{{
  * case class IceCream(name: String, numCherries: Int, cone: Boolean)
  *
  * TYPE ice_cream(cone BOOLEAN, name TEXT, num_cherries INT)
  * }}}
  *
  * For case classes and UDTs that align on the field order, please use [[IdenticalUDTCodec]]. See our
  * Developer Notes for more information.
  *
  * This trait follows [[MappingCodec]] interface closely, as that's how it implemented in the end.
  * The only difference is that `outerToInner` is "tail-rec" to handle shapeless encoding.
  *
  * @tparam A case class being encoded
  */
trait NonIdenticalUDTCodec[A]:

    /** Maps a [[UdtValue]] into an [[A]]
      *
      * @param value value coming from the database
      * @return mapped [[A]] value
      */
    def innerToOuter(value: UdtValue): A

    /** Maps a value [[A]] into a [[UdtValue]]
      *
      * @param udt value used to accumulate all fields from [[A]]
      * @param value value going to the database
      * @return [[UdtValue]] containing all mapped values from [[A]]
      */
    def outerToInner(udt: UdtValue, value: A): UdtValue

end NonIdenticalUDTCodec

object NonIdenticalUDTCodec:
    import scala.compiletime.*

    inline def summonInstances[Elems <: Tuple](columns: Seq[String]): NonIdenticalUDTCodec[Elems] =
        inline erasedValue[Elems] match
            case _: EmptyTuple =>
                deriveEmptyTuple(columns).asInstanceOf[NonIdenticalUDTCodec[Elems]]

            case _: (elem *: elems) =>
                deriveHList[elem, elems](columns).asInstanceOf[NonIdenticalUDTCodec[Elems]]

    inline def deriveEmptyTuple(columns: Seq[String]): NonIdenticalUDTCodec[EmptyTuple] =
        new NonIdenticalUDTCodec[EmptyTuple]:
            override def innerToOuter(value: UdtValue): EmptyTuple = EmptyTuple

            override def outerToInner(udt: UdtValue, value: EmptyTuple): UdtValue = udt

    inline def deriveHList[H, T <: Tuple](columns: Seq[String]): NonIdenticalUDTCodec[H *: T] =
        val codec          = summonInline[Codec[H]]
        val tail           = summonInstances[T](columns.tail)
        val column: String = columns.head

        new NonIdenticalUDTCodec[H *: T]:
            override def innerToOuter(value: UdtValue): H *: T =
                value.get(column, codec) *: tail.innerToOuter(value)

            override def outerToInner(udt: UdtValue, value: H *: T): UdtValue =
                tail.outerToInner(udt.set(column, value.head: H, codec), value.tail)
        end new
    end deriveHList

    inline def buildUserDefinedType(session: CqlSession, keyspace: Option[String], name: String): UserDefinedType =
        import scala.jdk.OptionConverters.*

        val actualKeyspace = keyspace.flatMap(session.keyspace).orElse(session.sessionKeyspace)
        (for
            keyspace <- actualKeyspace
            udt <- keyspace.getUserDefinedType(name).toScala
        yield udt).getOrElse:
            throw new IllegalArgumentException(
              s"Cannot create UserDefinedType. Couldn't find type [$name] in keyspace [$keyspace]"
            )
    end buildUserDefinedType

    inline def deriveFromNames[A <: Product](keyspace: String = "", name: String = "")(
        using session: CqlSession,
        mirror: Mirror.ProductOf[A],
        labelling: Labelling[A],
        tag: ClassTag[A],
        namingScheme: ColumnNamingScheme = DefaultColumnNamingScheme
    ): UDTCodec[A] =
        deriveFromType[A](buildUserDefinedType(
          session,
          Option(keyspace).filter(_.trim().nonEmpty),
          Option(name).filter(_.trim().nonEmpty).getOrElse(namingScheme.map(labelling.label))
        ))

    inline def deriveFromType[A <: Product](udt: UserDefinedType)(
        using mirror: Mirror.ProductOf[A],
        labelling: Labelling[A],
        tag: ClassTag[A],
        namingScheme: ColumnNamingScheme = DefaultColumnNamingScheme
    ): UDTCodec[A] = derivedFn[A](udt)

    inline def derivedFn[A <: Product](
        using mirror: Mirror.ProductOf[A],
        labelling: Labelling[A],
        tag: ClassTag[A],
        namingScheme: ColumnNamingScheme
    ): UserDefinedType => UDTCodec[A] = (udt: UserDefinedType) =>
        val codec       = summonInstances[mirror.MirroredElemTypes](labelling.elemLabels.map(namingScheme.map))
        val clazz       = tag.runtimeClass.asInstanceOf[Class[A]]
        val dseCodec    = new DseUdtCodec(udt)
        val genericType = GenericType.of(new ScalaTypeToken[A] {}.getType()).asInstanceOf[GenericType[A]]

        new MappingCodec[UdtValue, A](dseCodec, genericType) with UDTCodec[A]:

            override def fields: Seq[(String, DataType)] =
                import scala.jdk.CollectionConverters.*
                udt.getFieldNames().asScala.zip(udt.getFieldTypes().asScala).map(_.asInternal() -> _).toSeq

            override val getCqlType: UserDefinedType =
                super.getCqlType.asInstanceOf[UserDefinedType]

            override def innerToOuter(value: UdtValue): A =
                mirror.fromTuple(codec.innerToOuter(value))

            override def outerToInner(value: A): UdtValue =
                codec.outerToInner(getCqlType.newValue(), Tuple.fromProductTyped(value))

            override lazy val toString: String =
                s"UtdCodec[${clazz.getSimpleName}]"
        end new
    end derivedFn

end NonIdenticalUDTCodec
