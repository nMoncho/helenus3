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

package net.nmoncho.helenus.zio.macros

import scala.quoted.*

import com.datastax.oss.driver.api.core.ProtocolVersion
import com.datastax.oss.driver.api.core.cql.BoundStatement
import com.datastax.oss.driver.api.core.cql.Row
import com.datastax.oss.driver.internal.core.util.Strings
import net.nmoncho.helenus.api.RowMapper
import net.nmoncho.helenus.api.`type`.codec.Codec
import net.nmoncho.helenus.api.cql.WrappedBoundStatement
import net.nmoncho.helenus.zio.CassandraException
import net.nmoncho.helenus.zio.ZCqlSession
import zio.ZIO

object ZIOCqlQueryInterpolation:

    def cqlImpl(sc: Expr[StringContext], params: Expr[Seq[Any]])(using
        qctx: Quotes): Expr[ZIO[ZCqlSession, CassandraException, WrappedBoundStatement[Row]]] =
        import qctx.reflect.*

        val (parts, terms) = partsAndTerms(sc, params)
        val stmt           = Expr(buildStatement(parts, terms))
        val encodeStmt     = encodeLambda(terms)

        '{
            _root_.zio.ZIO.serviceWithZIO[_root_.net.nmoncho.helenus.zio.ZCqlSession](_.prepare($stmt)).map(pstmt =>
                new WrappedBoundStatement($encodeStmt(pstmt.bind()))(using RowMapper.identity)
            )
        }
    end cqlImpl

    def cqlAsyncImpl(sc: Expr[StringContext], params: Expr[Seq[Any]])(using
        qctx: Quotes): Expr[ZIO[ZCqlSession, CassandraException, WrappedBoundStatement[Row]]] =
        import qctx.reflect.*

        val (parts, terms) = partsAndTerms(sc, params)
        val stmt           = Expr(buildStatement(parts, terms))
        val encodeStmt     = encodeLambda(terms)

        '{
            _root_.zio.ZIO.serviceWithZIO[_root_.net.nmoncho.helenus.zio.ZCqlSession](_.prepareAsync($stmt)).map(
              pstmt =>
                  new WrappedBoundStatement($encodeStmt(pstmt.bind()))(using RowMapper.identity)
            )
        }
    end cqlAsyncImpl

    inline def partsAndTerms(sc: Expr[StringContext], params: Expr[Seq[Any]])(using
        q: Quotes): (Seq[String], List[q.reflect.Term]) =
        import q.reflect.*

        val parts = sc match
            case '{ StringContext(${ Varargs(rawParts) }*) } =>
                Expr.ofSeq(rawParts).valueOrAbort

            case _ =>
                report.errorAndAbort("Invalid use of CQL String Interpolation")

        val terms = params.asTerm match
            case Inlined(_, _, Typed(Repeated(terms, _), _)) =>
                terms

            case _ =>
                report.errorAndAbort(
                  "cql interpolation was expecting varargs as its input parameter, but got something different"
                )

        parts -> terms
    end partsAndTerms

    inline def namedParameter(using q: Quotes)(term: q.reflect.Term): String =
        val symbol = term.symbol.name.toString
        val named  = if Strings.needsDoubleQuotes(symbol) then Strings.doubleQuote(symbol) else symbol

        s":$named"
    end namedParameter

    /** Builds a CQL Statement, considering:
      *   - Constants will be replaced as is
      *   - Other expressions will be considered 'Named Bound Parameters'
      *
      * @param parts String Interpolated parts
      * @param terms String Interpolated parameters
      * @return CQL [[BoundStatement]] without any parameter bound
      */
    inline def buildStatement(using q: Quotes)(parts: Seq[String], terms: List[q.reflect.Term]): String =
        import q.reflect.*

        val paramTokens: List[String] =
            terms.map: term =>
                term.tpe.widenTermRefByName match
                    case ConstantType(c) =>
                        c.value.toString()

                    case _ =>
                        namedParameter(term)

        parts
            .zip(paramTokens)
            .foldLeft(new StringBuilder()):
                case (acc, (part, param)) => acc.append(part).append(param)
            .append(parts.lastOption.getOrElse(""))
            .toString()
    end buildStatement

    /** Sets/Encodes all bind parameter into a [[BoundStatement]]
      *
      * @param bstmt BoundStatement to set the parameters into
      * @param terms list of bind parameters
      * @return fully bound statement
      */
    inline def encode(using q: Quotes)(
        bstmt: Expr[BoundStatement],
        terms: List[q.reflect.Term]
    ): Expr[BoundStatement] =
        import q.reflect.*

        terms.foldLeft(bstmt): (bstmt, term) =>
            term.tpe.widenTermRefByName match
                case ConstantType(_) =>
                    bstmt

                case _ =>
                    encScala3_3_3(using q, term.tpe.widen.asType.asInstanceOf[Type[Any]])(bstmt, term.asExpr)

    // The following code works on Scala 3.4.2 but not on Scala 3.3.3 LTS
    // The offending code is `term.tpe.widen.asType match case '[t] =>`
    // More specifically `'[t]`.
    // I'm keeping this here until it's resolved in a patch version

    // terms.foldLeft(bstmt): (bstmt, term) =>
    //     term.tpe.widenTermRefByName match
    //         case ConstantType(_) =>
    //             bstmt

    //         case _ =>
    //             term.tpe.widen.asType match
    //                 case '[t] => encScala3_4_2[t](bstmt, term.asExprOf[t])
    end encode

    /** Sets/Encodes a single Bind Parameter into a CQL BoundStatement by searching its [[Codec]]
      *
      * @param bstmt BoundStatement to set the parameter into
      * @param expr  parameter to set
      * @tparam T resulting expression type
      * @return resulting expression
      */
    inline def encScala3_4_2[T: Type](using
        q: Quotes)(bstmt: Expr[BoundStatement], expr: Expr[T]): Expr[BoundStatement] =
        import q.reflect.*

        Implicits.search(TypeRepr.of[Codec].appliedTo(expr.asTerm.tpe.widen)) match
            case s: ImplicitSearchSuccess =>
                val codec = s.tree.asExprOf[Codec[T]]
                val name  = Expr(namedParameter(expr.asTerm).substring(1))

                '{
                    $bstmt.setBytesUnsafe($name, $codec.encode($expr, ProtocolVersion.DEFAULT))
                }

            case f: ImplicitSearchFailure =>
                report.errorAndAbort(f.explanation)
        end match
    end encScala3_4_2

    /** Sets/Encodes a single Bind Parameter into a CQL BoundStatement by searching its [[Codec]]
      *
      * @param bstmt BoundStatement to set the parameter into
      * @param expr  parameter to set
      * @tparam T resulting expression type
      * @return resulting expression
      */
    inline def encScala3_3_3(using q: Quotes, t: Type[Any])(
        bstmt: Expr[BoundStatement],
        expr: Expr[Any]
    ): Expr[BoundStatement] =
        import q.reflect.*

        Implicits.search(TypeRepr.of[Codec].appliedTo(expr.asTerm.tpe.widen)) match
            case s: ImplicitSearchSuccess =>
                val codec = s.tree.asExprOf[Codec[t.Underlying]]
                val name  = Expr(namedParameter(expr.asTerm).substring(1))
                val tExpr = expr.asExprOf[t.Underlying](using t)

                '{
                    $bstmt.setBytesUnsafe($name, $codec.encode($tExpr, ProtocolVersion.DEFAULT))
                }

            case f: ImplicitSearchFailure =>
                report.errorAndAbort(f.explanation)
        end match
    end encScala3_3_3

    /** Creates a lambda that given a [[BoundStatement]], it will set/encode all bind parameters
      *
      * @param terms bind parameters as Terms
      * @return lambda expression
      */
    def encodeLambda(using q: Quotes)(terms: List[q.reflect.Term]): Expr[BoundStatement => BoundStatement] =
        import q.reflect.*

        val btpe = TypeRepr.of[BoundStatement]
        val tpe  = MethodType(List("bstmt"))(_ => List(btpe), _ => btpe)
        Lambda(
          owner = Symbol.spliceOwner,
          tpe   = tpe,
          rhsFn = (sym, params) =>
              val bstmt = params.head.asExprOf[BoundStatement]
              encode(bstmt, terms).asTerm.changeOwner(sym)
        ).asExprOf[BoundStatement => BoundStatement]
    end encodeLambda

end ZIOCqlQueryInterpolation
