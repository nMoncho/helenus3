/*
 * Copyright 2021 the original author or authors
 *
 * SPDX-License-Identifier: MIT
 */

package net.nmoncho.helenus.internal.codec

import java.nio.ByteBuffer

import com.datastax.oss.driver.api.core.ProtocolVersion
import com.datastax.oss.driver.api.core.`type`.DataType
import com.datastax.oss.driver.api.core.`type`.DataTypes
import com.datastax.oss.driver.api.core.`type`.reflect.GenericType
import net.nmoncho.helenus.api.`type`.codec.Codec

object ShortCodec extends Codec[Short]:

    def encode(value: Short, protocolVersion: ProtocolVersion): ByteBuffer =
        ByteBuffer.allocate(2).putShort(0, value)

    def decode(bytes: ByteBuffer, protocolVersion: ProtocolVersion): Short =
        if bytes == null || bytes.remaining == 0 then 0
        else if bytes.remaining != 2 then
            throw new IllegalArgumentException(
              s"Invalid 16-bits integer value, expecting 2 bytes but got [${bytes.remaining}]"
            )
        else bytes.getShort(bytes.position)

    val getCqlType: DataType = DataTypes.SMALLINT

    val getJavaType: GenericType[Short] = GenericType.of(classOf[Short])

    def format(value: Short): String =
        value.toString

    def parse(value: String): Short =
        try
            if value == null || value.isEmpty || value.equalsIgnoreCase(NULL) then 0
            else value.toShort
        catch
            case e: NumberFormatException =>
                throw new IllegalArgumentException(
                  s"Cannot parse 16-bits int value from [$value]",
                  e
                )

    override def accepts(javaClass: Class[?]): Boolean = javaClass == classOf[Short]

    override def accepts(javaType: GenericType[?]): Boolean = javaType == getJavaType

    override def accepts(value: Any): Boolean = value.isInstanceOf[Short]

end ShortCodec
