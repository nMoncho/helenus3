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

object IntCodec extends Codec[Int]:

    def encode(value: Int, protocolVersion: ProtocolVersion): ByteBuffer =
        ByteBuffer.allocate(4).putInt(0, value)

    def decode(bytes: ByteBuffer, protocolVersion: ProtocolVersion): Int =
        if bytes == null || bytes.remaining == 0 then 0
        else if bytes.remaining != 4 then
            throw new IllegalArgumentException(
              s"Invalid 32-bits integer value, expecting 4 bytes but got [${bytes.remaining}]"
            )
        else bytes.getInt(bytes.position)

    val getCqlType: DataType = DataTypes.INT

    val getJavaType: GenericType[Int] = GenericType.of(classOf[Int])

    def format(value: Int): String =
        value.toString

    def parse(value: String): Int =
        try
            if value == null || value.isEmpty || value.equalsIgnoreCase(NULL) then 0
            else value.toInt
        catch
            case e: NumberFormatException =>
                throw new IllegalArgumentException(
                  s"Cannot parse 32-bits integer value from [$value]",
                  e
                )

    override def accepts(javaClass: Class[?]): Boolean = javaClass == classOf[Int]

    override def accepts(javaType: GenericType[?]): Boolean = javaType == getJavaType

    override def accepts(value: Any): Boolean = value.isInstanceOf[Int]

end IntCodec
