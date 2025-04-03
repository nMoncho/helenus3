/*
 * Copyright 2021 the original author or authors
 *
 * SPDX-License-Identifier: MIT
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
