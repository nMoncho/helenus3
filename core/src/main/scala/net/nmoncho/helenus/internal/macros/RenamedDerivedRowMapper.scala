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

package net.nmoncho.helenus.internal.macros

import scala.deriving.Mirror
import scala.quoted.*

import com.datastax.oss.driver.api.core.cql.Row
import net.nmoncho.helenus.api.ColumnNamingScheme
import net.nmoncho.helenus.api.DefaultColumnNamingScheme
import net.nmoncho.helenus.api.RowMapper
import net.nmoncho.helenus.internal.DerivedRowMapper
import net.nmoncho.helenus.internal.Labelling

object RenamedDerivedRowMapper:

    def renamedImpl[A <: Product: Type](first: Expr[A => (Any, String)], rest: Expr[Seq[A => (Any, String)]])(using
    qctx: Quotes): Expr[RowMapper[A]] =
        import qctx.reflect.*

        def summonOrFail[T: Type]: Expr[T] =
            Expr.summon[T] match
                case Some(imp) => imp
                case None => report.errorAndAbort("Could not find an implicit for " + Type.show[T])

        def extract(field: Expr[A => (Any, String)]): (String, String) =
            field.asTerm match
                case Block(
                      List(
                        DefDef(
                          _,
                          _,
                          _,
                          Some(
                            Apply(
                              TypeApply(Select(Apply(_, List(Select(_, name))), _), _),
                              List(
                                Literal(StringConstant(renamed))
                              )
                            )
                          )
                        )
                      ),
                      _
                    ) => name -> renamed

                case Inlined(
                      _,
                      _,
                      block: Block
                    ) => extract(block.asExprOf[A => (Any, String)])

                case term =>
                    report.errorAndAbort(
                      """Renamed fields should be used with placeholder function notation. For example `_.name -> "person_name"`"""
                    )

        val namingScheme: Expr[ColumnNamingScheme] = Expr.summon[ColumnNamingScheme] match
            case None => '{ DefaultColumnNamingScheme }
            case Some(value) => value

        val rename: Expr[Map[String, String]] = Expr(rest match
            case Varargs(fieldsExpr) =>
                fieldsExpr.map(extract).toMap + extract(first)
        )

        val renamedFields = '{
            ${ summonOrFail[Labelling[A]] }.elemLabels.map(field =>
                if $rename.contains(field) then $rename(field) else $namingScheme.map(field)
            )
        }

        summonOrFail[Mirror.ProductOf[A]] match
            case '{ $m: Mirror.ProductOf[A] { type MirroredElemTypes = elementTypes } } =>
                '{
                    val instances = RowMapper.summonInstances[elementTypes]($renamedFields)

                    new DerivedRowMapper[A]:
                        override def apply(row: Row): A = $m.fromTuple(instances(row))
                }
        end match
    end renamedImpl
end RenamedDerivedRowMapper
