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

package net.nmoncho.helenus.api.cql

import scala.deriving.Mirror

/** Adapts a type `A` into a type `B`.
  *
  * Useful when wanting to adapt a case class instance into a tuple (e.g. during insert)
  *
  * @tparam A original type
  * @tparam B target type
  */
trait Adapter[A, B] extends Serializable:
    def apply(a: A): B

object Adapter:

    def apply[A <: Product](using m: Mirror.ProductOf[A]): Adapter[A, m.MirroredElemTypes] =
        new Adapter[A, m.MirroredElemTypes]:
            def apply(a: A): m.MirroredElemTypes =
                Tuple.fromProductTyped(a)

    def builder[A <: Product](using m: Mirror.ProductOf[A]): Builder[A, m.MirroredElemTypes] =
        new Builder[A, m.MirroredElemTypes](Tuple.fromProductTyped)

    class Builder[A, B <: Tuple](to: A => B):

        /** Builds an [[Adapter]] which transforms type `A` into tuple `B`
          *
          * @param tupler transforms representation `R` to tuple `B`
          * @tparam B target tuple type for `Adapter`
          * @return adapter
          */
        def build(): Adapter[A, B] = (a: A) => to(a)

        /** Adds a computed column for this adapter
          *
          * @param fn how to compute the column from the original type
          * @param p  computed columns are appended to the end
          * @tparam Col computed column type
          * @return a new [[Builder]] with a computed column
          */
        def withComputedColumn[Col](fn: A => Col): Builder[A, Tuple.Append[B, Col]] =
            new Builder(a => to(a) :* fn(a))

    end Builder

end Adapter
