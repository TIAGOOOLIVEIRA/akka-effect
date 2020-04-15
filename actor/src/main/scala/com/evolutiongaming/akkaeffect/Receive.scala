package com.evolutiongaming.akkaeffect

import akka.actor.ActorRef
import cats.implicits._
import cats.{Applicative, FlatMap, Monad, ~>}

/**
  * Typesafe api for Actor.receive
  *
  * @see [[akka.actor.Actor.receive]]
  * @tparam A message
  * @tparam B reply
  */
trait Receive[F[_], -A, B] {
  import Receive._

  /**
    * Called strictly sequentially, next message will be processed only after we've done with the previous one
    * This basically preserves the semantic of Actors
    */
  def apply(msg: A, reply: Reply[F, B], sender: ActorRef /*TODO remove*/): F[Stop]
}

object Receive {

  type Stop = Boolean


  def empty[F[_]: Applicative, A, B]: Receive[F, A, B] = const(false.pure[F])

  def stop[F[_]: Applicative, A, B]: Receive[F, A, B] = const(true.pure[F])

  def const[F[_]: Applicative, A, B](stop: F[Stop]): Receive[F, A, B] = (_: A, _: Reply[F, B], _) => stop


  implicit class ReceiveOps[F[_], A, B](val self: Receive[F, A, B]) extends AnyVal {

    def mapK[G[_]](fg: F ~> G, gf: G ~> F): Receive[G, A, B] = {
      (msg: A, reply: Reply[G, B], sender) => {
        fg(self(msg, reply.mapK(gf), sender))
      }
    }


    def collect[AA](f: AA => F[Option[A]])(implicit F: Monad[F]): Receive[F, AA, B] = {
      new Receive[F, AA, B] {

        def apply(msg: AA, reply: Reply[F, B], sender: ActorRef) = {
          for {
            msg  <- f(msg)
            stop <- msg.fold(false.pure[F]) { msg => self(msg, reply, sender) }
          } yield stop
        }
      }
    }


    def convert[A1, B1](
      af: A1 => F[A],
      bf: B => F[B1])(implicit
      F: FlatMap[F],
    ): Receive[F, A1, B1] = {
      (msg: A1, reply: Reply[F, B1], sender: ActorRef) => {
        for {
          msg  <- af(msg)
          stop <- self(msg, reply.convert(bf), sender)
        } yield stop
      }
    }


    def convertMsg[A1](f: A1 => F[A])(implicit F: FlatMap[F]): Receive[F, A1, B] = {
      (msg: A1, reply: Reply[F, B], sender: ActorRef) => {
        for {
          msg  <- f(msg)
          stop <- self(msg, reply, sender)
        } yield stop
      }
    }


    def widen[A1 >: A, B1 >: B](f: A1 => F[A])(implicit F: FlatMap[F]): Receive[F, A1, B1] = {
      (msg: A1, reply: Reply[F, B1], sender: ActorRef) => {
        for {
          msg  <- f(msg)
          stop <- self(msg, reply, sender)
        } yield stop
      }
    }


    def typeless(f: Any => F[A])(implicit F: FlatMap[F]): Receive[F, Any, Any] = widen(f)
  }
}