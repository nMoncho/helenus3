/*
 * Copyright 2021 the original author or authors
 *
 * SPDX-License-Identifier: MIT
 */

package net.nmoncho.helenus.internal.macros

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.quoted.*

import com.datastax.oss.driver.api.core.CqlSession
import com.datastax.oss.driver.api.core.ProtocolVersion
import com.datastax.oss.driver.api.core.cql.BoundStatement
import com.datastax.oss.driver.api.core.cql.Row
import com.datastax.oss.driver.internal.core.util.Strings
import net.nmoncho.helenus.api.RowMapper
import net.nmoncho.helenus.api.`type`.codec.Codec
import net.nmoncho.helenus.api.cql.WrappedBoundStatement

object CqlQueryInterpolation:

    def cqlImpl(sc: Expr[StringContext], params: Expr[Seq[Any]], session: Expr[CqlSession])(using
        qctx: Quotes): Expr[WrappedBoundStatement[Row]] =
        import qctx.reflect.*

        val (parts, terms) = partsAndTerms(sc, params)
        val stmt           = Expr(buildStatement(parts, terms))
        val bstmt          = '{ $session.prepare($stmt).bind() }
        val estmt          = encode(bstmt, terms)

        '{
            new WrappedBoundStatement($estmt)(using RowMapper.identity)
        }
    end cqlImpl

    def cqlAsyncImpl(
        sc: Expr[StringContext],
        params: Expr[Seq[Any]],
        session: Expr[Future[CqlSession]],
        ec: Expr[ExecutionContext]
    )(using qctx: Quotes): Expr[Future[WrappedBoundStatement[Row]]] =
        import qctx.reflect.*

        val (parts, terms) = partsAndTerms(sc, params)
        val stmt           = Expr(buildStatement(parts, terms))
        val encodeStmt     = encodeLambda(terms)

        '{
            $session.flatMap(sess =>
                scala.jdk.javaapi.FutureConverters.asScala(sess.prepareAsync($stmt)).map { pstmt =>
                    new WrappedBoundStatement($encodeStmt(pstmt.bind()))(using RowMapper.identity)
                }($ec)
            )($ec)
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
    inline def encode(using q: Quotes)(bstmt: Expr[BoundStatement], terms: List[q.reflect.Term]): Expr[BoundStatement] =
        import q.reflect.*

        terms.foldLeft(bstmt): (bstmt, term) =>
            term.tpe.widenTermRefByName match
                case ConstantType(_) =>
                    bstmt

                case _ =>
                    encScala3_3_3(using q, term.tpe.widen.asType.asInstanceOf[Type[Any]])(bstmt, term.asExpr)

        // The following code works on Scala 3.4.2 but not on Scala 3.3.6 LTS
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
      * @param expr parameter to set
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
      * @param expr parameter to set
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

end CqlQueryInterpolation
