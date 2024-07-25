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

package net.nmoncho.helenus.internal

import scala.collection.immutable.ArraySeq
import scala.compiletime.constValue
import scala.compiletime.erasedValue
import scala.deriving.Mirror
import scala.reflect.ClassTag

inline def summonValuesAsArray[T <: Tuple, E: ClassTag]: Array[E] =
    summonValuesAsArray0[T, E](0, new Array[E](constValue[Tuple.Size[T]]))

inline def summonValuesAsArray0[T, E](i: Int, arr: Array[E]): Array[E] = inline erasedValue[T] match
    case _: EmptyTuple => arr
    case _: (a *: b) =>
        arr(i) = constValue[a & E]
        summonValuesAsArray0[b, E](i + 1, arr)

case class Labelling[T](label: String, elemLabels: IndexedSeq[String])

object Labelling:
    inline given apply[T](using mirror: Mirror { type MirroredType = T }): Labelling[T] =
        Labelling[T](
          constValue[mirror.MirroredLabel & String],
          ArraySeq.unsafeWrapArray(summonValuesAsArray[mirror.MirroredElemLabels, String])
        )
end Labelling
