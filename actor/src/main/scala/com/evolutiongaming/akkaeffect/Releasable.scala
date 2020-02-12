package com.evolutiongaming.akkaeffect

import cats.effect.Resource
import cats.implicits._
import cats.{Applicative, Monad}
import com.evolutiongaming.catshelper.BracketThrowable

import scala.annotation.tailrec

final case class Releasable[F[_], A](value: A, release: Option[F[Unit]] = None)

object Releasable {

  def fromResource[F[_] : BracketThrowable, A](resource: Resource[F, A]): F[Releasable[F, A]] = {
    resource
      .allocated
      .map { case (a, release) => Releasable(a, release.some) }
  }


  implicit def applicativeReleasable[F[_] : Applicative]: Monad[Releasable[F, *]] = {

    def combine(a: Option[F[Unit]], b: Option[F[Unit]]) = {
      a match {
        case Some(a) => b match {
          case Some(b) => (b *> a).some
          case None    => a.some
        }
        case None    => b
      }
    }

    new Monad[Releasable[F, *]] {

      def pure[A](a: A) = Releasable[F, A](a)

      def flatMap[A, B](fa: Releasable[F, A])(f: A => Releasable[F, B]) = {
        val fb = f(fa.value)
        val release = combine(a = fa.release, b = fb.release)
        fb.copy(release = release)
      }

      def tailRecM[A, B](a: A)(f: A => Releasable[F, Either[A, B]]) = {

        @tailrec
        def loop(fa: Releasable[F, A]): Releasable[F, B] = {
          val fb = f(fa.value)
          fb.value match {
            case Left(b)  => loop(Releasable(b, combine(a = fa.release, b = fb.release)))
            case Right(b) => fb.copy(value = b)
          }
        }

        loop(Releasable(a))
      }
    }
  }


  implicit class ReleasableOps[F[_], A](val self: Releasable[F, A]) extends AnyVal {

    def map[B](f: A => B): Releasable[F, B] = self.copy(value = f(self.value))
  }


  object implicits {

    implicit class ResourceOpsReleasable[F[_], A](val self: Resource[F, A]) extends AnyVal {

      def toReleasable(implicit F: BracketThrowable[F]): F[Releasable[F, A]] = fromResource(self)
    }
  }
}