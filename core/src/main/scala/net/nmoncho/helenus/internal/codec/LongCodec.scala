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

object LongCodec extends Codec[Long]:

    def encode(value: Long, protocolVersion: ProtocolVersion): ByteBuffer =
        ByteBuffer.allocate(8).putLong(0, value)

    def decode(bytes: ByteBuffer, protocolVersion: ProtocolVersion): Long =
        if bytes == null || bytes.remaining == 0 then 0
        else if bytes.remaining != 8 then
            throw new IllegalArgumentException(
              s"Invalid 64-bits integer value, expecting 8 bytes but got [${bytes.remaining}]"
            )
        else bytes.getLong(bytes.position)

    val getCqlType: DataType = DataTypes.BIGINT

    val getJavaType: GenericType[Long] = GenericType.of(classOf[Long])

    def format(value: Long): String =
        value.toString

    def parse(value: String): Long =
        try
            if value == null || value.isEmpty || value.equalsIgnoreCase(NULL) then 0
            else value.toLong
        catch
            case e: NumberFormatException =>
                throw new IllegalArgumentException(
                  s"Cannot parse 64-bits integer value from [$value]",
                  e
                )

    override def accepts(javaClass: Class[?]): Boolean = javaClass == classOf[Long]

    override def accepts(javaType: GenericType[?]): Boolean = javaType == getJavaType

    override def accepts(value: Any): Boolean = value.isInstanceOf[Long]

end LongCodec
