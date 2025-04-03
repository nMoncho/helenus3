/*
 * Copyright 2021 the original author or authors
 *
 * SPDX-License-Identifier: MIT
 */

package net.nmoncho.helenus.internal.codec
package collection

import java.nio.ByteBuffer

import scala.collection.Factory
import scala.collection.mutable as mutablecoll

import com.datastax.oss.driver.api.core.ProtocolVersion
import com.datastax.oss.driver.api.core.`type`.DataType
import com.datastax.oss.driver.api.core.`type`.codec.TypeCodec
import com.datastax.oss.driver.internal.core.`type`.DefaultListType
import com.datastax.oss.driver.internal.core.`type`.DefaultSetType
import com.datastax.oss.driver.internal.core.`type`.codec.ParseUtils
import net.nmoncho.helenus.api.`type`.codec.Codec

abstract class IterableCodec[T, M[T] <: Iterable[T]](
    inner: TypeCodec[T],
    openingChar: Char,
    closingChar: Char
)(
    implicit factory: Factory[T, M[T]]
) extends Codec[M[T]]:

    private val separator: Char = ','

    override def encode(value: M[T], protocolVersion: ProtocolVersion): ByteBuffer =
        if value == null then null
        else
            // using mutable local state yield performance closer to DSE Java Driver
            var count   = 0
            var size    = 0
            val buffers = mutablecoll.ListBuffer[ByteBuffer]()
            for item <- value do
                if item == null then
                    throw new IllegalArgumentException("Collection elements cannot be null")

                val element = inner.encode(item, protocolVersion)
                if element == null then
                    throw new NullPointerException("Collection elements cannot encode to CQL NULL")

                buffers.append(element)
                size += (if element == null then 4 else 4 + element.remaining())
                count += 1
            end for

            val result = ByteBuffer.allocate(4 + size)
            result.putInt(count)

            for value <- buffers do
                if value == null then
                    result.putInt(-1)
                else
                    result.putInt(value.remaining().toShort)
                    result.put(value.duplicate())
            end for

            result.flip()

    override def decode(bytes: ByteBuffer, protocolVersion: ProtocolVersion): M[T] =
        val builder = factory.newBuilder

        if bytes == null || bytes.remaining == 0 then builder.result()
        else
            val input = bytes.duplicate()
            val size  = input.getInt()
            for _ <- 0 until size do
                val size = input.getInt()

                val value =
                    if size < 0 then null
                    else
                        val copy = input.duplicate()
                        copy.limit(copy.position() + size)
                        input.position(input.position() + size)

                        copy

                builder += inner.decode(value, protocolVersion)
            end for

            builder.result()
        end if
    end decode

    override def format(value: M[T]): String =
        if value == null then
            NULL
        else
            val sb   = new mutablecoll.StringBuilder().append(openingChar)
            var tail = false
            for item <- value do
                if tail then sb.append(separator)
                else tail = true

                sb.append(inner.format(item))
            end for
            sb.append(closingChar).toString()

    @SuppressWarnings(Array("DisableSyntax.return"))
    override def parse(value: String): M[T] =
        if value == null || value.isEmpty || value.equalsIgnoreCase(NULL) then
            null.asInstanceOf[M[T]]
        else
            val builder = factory.newBuilder
            var idx     = skipSpacesAndExpect(value, 0, openingChar)

            if value.charAt(idx) == closingChar then
                builder.result()
            else
                while idx < value.length do
                    val (element, n) = parseWithCodec(value, inner, idx)

                    builder += element

                    idx = ParseUtils.skipSpaces(value, n)
                    if isParseFinished(value, idx, closingChar, separator) then
                        return builder.result()

                    idx = ParseUtils.skipSpaces(value, idx + 1)
                end while

                throw new IllegalArgumentException(
                  s"Malformed collection value '$value', missing closing '$closingChar'"
                )
            end if

    override def accepts(value: Any): Boolean = value match
        case l: M[?] @unchecked => l.headOption.fold(true)(inner.accepts)
        case _ => false

end IterableCodec

abstract class AbstractSeqCodec[T, M[T] <: scala.collection.Seq[T]](
    inner: TypeCodec[T],
    frozen: Boolean
)(implicit factory: Factory[T, M[T]])
    extends IterableCodec[T, M](inner, '[', ']'):

    override val getCqlType: DataType = new DefaultListType(inner.getCqlType, frozen)
end AbstractSeqCodec

abstract class AbstractSetCodec[T, M[T] <: scala.collection.Set[T]](
    inner: TypeCodec[T],
    frozen: Boolean
)(implicit factory: Factory[T, M[T]])
    extends IterableCodec[T, M](inner, '{', '}'):

    override val getCqlType: DataType = new DefaultSetType(inner.getCqlType, frozen)
end AbstractSetCodec
