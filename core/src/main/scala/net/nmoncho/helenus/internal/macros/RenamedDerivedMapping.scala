/*
 * Copyright 2021 the original author or authors
 *
 * SPDX-License-Identifier: MIT
 */

package net.nmoncho.helenus.internal.macros

import scala.compiletime.erasedValue
import scala.deriving.Mirror
import scala.quoted.Expr
import scala.quoted.Quotes
import scala.quoted.Type
import scala.quoted.Varargs

import com.datastax.oss.driver.api.core.cql.PreparedStatement
import com.datastax.oss.driver.api.core.cql.Row
import net.nmoncho.helenus.api.ColumnNamingScheme
import net.nmoncho.helenus.api.DefaultColumnNamingScheme
import net.nmoncho.helenus.api.cql.Mapping
import net.nmoncho.helenus.api.cql.Mapping.Binder
import net.nmoncho.helenus.api.cql.Mapping.DefaultCaseClassDerivedMapping
import net.nmoncho.helenus.api.cql.Mapping.FieldCollector
import net.nmoncho.helenus.api.cql.Mapping.summonInstances
import net.nmoncho.helenus.api.cql.ScalaPreparedStatement
import net.nmoncho.helenus.internal.Labelling

object RenamedDerivedMapping:

    def renamedImpl[A <: Product: Type](first: Expr[A => (Any, String)], rest: Expr[Seq[A => (Any, String)]])(using
        qctx: Quotes): Expr[Mapping[A]] =
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
                fieldsExpr.map(extract).toMap + extract(first))

        val renamedFields = '{
            ${ summonOrFail[Labelling[A]] }.elemLabels.map(field =>
                if $rename.contains(field) then $rename(field) else $namingScheme.map(field)
            )
        }

        summonOrFail[Mirror.ProductOf[A]] match
            case '{ $m: Mirror.ProductOf[A] { type MirroredElemTypes = elementTypes } } =>
                '{
                    val elementsCollector = Mapping.summonInstances[elementTypes]($renamedFields)

                    val collector = new FieldCollector[A]:
                        override protected val column: String = "<not-used>"
                        override val columns: Set[String]     = elementsCollector.columns

                        override def usedParameters(pstmt: PreparedStatement, accumulated: Set[String]): Set[String] =
                            elementsCollector.usedParameters(pstmt, accumulated)

                        override def apply(row: Row): A =
                            $m.fromTuple(elementsCollector(row))

                        override def apply[Out](pstmt: ScalaPreparedStatement[A, Out]): Binder[A, Out] =
                            val binder =
                                elementsCollector(pstmt.asInstanceOf[ScalaPreparedStatement[elementTypes, Out]])

                            (bstmt, a) => binder(bstmt, Tuple.fromProductTyped(a)(using $m))
                        end apply

                    new DefaultCaseClassDerivedMapping[A](collector, Map.empty)
                }
        end match
    end renamedImpl

end RenamedDerivedMapping
