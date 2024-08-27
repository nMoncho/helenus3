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

package net.nmoncho.helenus.internal.codec.udt

import java.nio.ByteBuffer

import scala.collection.mutable
import scala.deriving.Mirror
import scala.reflect.ClassTag

import com.datastax.oss.driver.api.core.CqlIdentifier
import com.datastax.oss.driver.api.core.ProtocolVersion
import com.datastax.oss.driver.api.core.`type`.DataType
import com.datastax.oss.driver.api.core.`type`.UserDefinedType
import com.datastax.oss.driver.api.core.`type`.codec.TypeCodec
import com.datastax.oss.driver.api.core.`type`.reflect.GenericType
import com.datastax.oss.driver.internal.core.`type`.DefaultUserDefinedType
import com.datastax.oss.driver.internal.core.`type`.codec.ParseUtils
import net.nmoncho.helenus.api.ColumnNamingScheme
import net.nmoncho.helenus.api.DefaultColumnNamingScheme
import net.nmoncho.helenus.internal.Labelling

/** A [[IdenticalUDTCodec]] is one that maps a case class to a UDT, both having the <strong>same</strong>
  * field order, for example:
  *
  * {{{
  * case class IceCream(name: String, numCherries: Int, cone: Boolean)
  *
  * TYPE ice_cream(name TEXT, num_cherries INT, cone BOOLEAN)
  * }}}
  *
  * For case classes and UDTs that don't align on the field order, please use [[NonIdenticalUDTCodec]].
  * See our Developer Notes for more information.
  *
  * This trait follows [[TypeCodec]] interface closely, with some "tail-rec" modifications to handle
  * shapeless encoding.
  *
  * @tparam A case class being encoded
  */
trait IdenticalUDTCodec[A]:

    def columns: List[(String, DataType)]

    /** Encodes an [[A]] value as a [[ByteBuffer]], and appends it to a list
      *
      * @param value           value to be encoded
      * @param protocolVersion DSE Version in use
      * @return accumulated buffers, one per case class component, with the total buffer size
      */
    inline def encode(value: A, protocolVersion: ProtocolVersion): (List[ByteBuffer], Int)

    /** Decodes an [[A]] value from a [[ByteCodec]]
      *
      * @param buffer buffer to be decoded
      * @param protocolVersion DSE Version in use
      * @return a decoded [[A]] value
      */
    inline def decode(buffer: ByteBuffer, protocolVersion: ProtocolVersion): A

    /** Formats an [[A]] value
      *
      * @param value value to be formatted
      * @param sb where to format into
      * @return builder with formatted value
      */
    inline def format(value: A, sb: mutable.StringBuilder): mutable.StringBuilder

    /** Parses an [[A]] value from a String
      *
      * @param value where to parse from
      * @param idx from which index to start parsing
      * @return a parsed [[A]] value, and an index from where to continue parsing
      */
    inline def parse(value: String, idx: Int): (A, Int)

end IdenticalUDTCodec

object IdenticalUDTCodec:
    private val openingChar    = '{'
    private val fieldSeparator = ':'
    private val separator      = ','
    private val closingChar    = '}'

end IdenticalUDTCodec
