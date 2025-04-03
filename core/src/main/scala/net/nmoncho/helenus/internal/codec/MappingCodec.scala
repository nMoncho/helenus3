/*
 * Copyright 2021 the original author or authors
 *
 * SPDX-License-Identifier: MIT
 */

package net.nmoncho.helenus.internal.codec

import java.nio.ByteBuffer

import com.datastax.oss.driver.api.core.ProtocolVersion
import com.datastax.oss.driver.api.core.`type`.DataType
import com.datastax.oss.driver.api.core.`type`.codec.TypeCodec
import com.datastax.oss.driver.api.core.`type`.reflect.GenericType
import net.nmoncho.helenus.api.`type`.codec.Codec

/** A [[TypeCodec]] that maps instances of `Inner`, a driver supported Java type, to
  * instances of a target `Outer` Java type.
  *
  * <p>This codec can be used to provide support for Java types that are not natively handled by the
  * driver, as long as there is a conversion path to and from another supported Java type.
  */
abstract class MappingCodec[Inner, Outer](inner: TypeCodec[Inner], outerJavaType: GenericType[Outer])
    extends Codec[Outer]:

    /** Converts from an instance of the inner Java type to an instance of the outer Java type. Used
      * when deserializing or parsing.
      *
      * @param value The value to convert; may be null.
      * @return The converted value; may be null.
      */
    protected def innerToOuter(value: Inner): Outer

    /** Converts from an instance of the outer Java type to an instance of the inner Java type. Used
      * when serializing or formatting.
      *
      * @param value The value to convert; may be null.
      * @return The converted value; may be null.
      */
    protected def outerToInner(value: Outer): Inner

    override def decode(bytes: ByteBuffer, protocolVersion: ProtocolVersion): Outer =
        innerToOuter(inner.decode(bytes, protocolVersion))

    override def encode(value: Outer, protocolVersion: ProtocolVersion): ByteBuffer =
        inner.encode(outerToInner(value), protocolVersion)

    override def format(value: Outer): String =
        inner.format(outerToInner(value))

    override def parse(value: String): Outer =
        innerToOuter(inner.parse(value))

    override def getJavaType(): GenericType[Outer] =
        outerJavaType

    override def getCqlType(): DataType =
        inner.getCqlType()

end MappingCodec
