/*
 * Copyright 2021 the original author or authors
 *
 * SPDX-License-Identifier: MIT
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
