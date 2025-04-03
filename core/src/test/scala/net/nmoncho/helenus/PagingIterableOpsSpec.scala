/*
 * Copyright 2021 the original author or authors
 *
 * SPDX-License-Identifier: MIT
 */

package net.nmoncho.helenus

import com.datastax.oss.driver.api.core.PagingIterable
import org.mockito.Mockito.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class PagingIterableOpsSpec extends AnyWordSpec with Matchers:

    "PagingIterableOps" should {
        "provide 'headOption'" in {
            val nonEmpty = mockPagingIterable(List(1, 2, 3, 4, 5))
            nonEmpty.nextOption() shouldBe Some(1)

            val empty = mockPagingIterable(List.empty[Int])
            empty.nextOption() shouldBe None
        }

        "provide a Scala Iterator" in {
            val pagingIterable = mockPagingIterable(List(1, 2, 3, 4, 5))

            pagingIterable.iter shouldBe a[scala.collection.Iterator[?]]
        }

        "convert to a collection" in {
            def pagingIterable = mockPagingIterable(List(1, 2, 3, 4, 5))

            pagingIterable.to(List) shouldBe List(1, 2, 3, 4, 5)
            pagingIterable.to(Vector) shouldBe Vector(1, 2, 3, 4, 5)
            pagingIterable.to(Set) shouldBe Set(1, 2, 3, 4, 5)
        }
    }

    private def mockPagingIterable[T](iterable: Iterable[T]): PagingIterable[T] =
        import scala.jdk.CollectionConverters.*

        val pi = mock(classOf[PagingIterable[T]])

        when(pi.one()).thenCallRealMethod()
        when(pi.iterator()).thenReturn(iterable.iterator.asJava)

        pi
    end mockPagingIterable

end PagingIterableOpsSpec
