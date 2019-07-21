package com.evolutiongaming.akkaeffect

import akka.actor.{ActorIdentity, ActorRef, ActorSystem, Identify, ReceiveTimeout}
import akka.testkit.TestActors
import cats.effect.concurrent.Deferred
import cats.effect.{Async, Concurrent, IO, Sync}
import cats.implicits._
import com.evolutiongaming.akkaeffect.IOSuite._
import com.evolutiongaming.catshelper.{FromFuture, ToFuture}
import org.scalatest.{AsyncFunSuite, Matchers}

import scala.concurrent.duration._

class ActorOfSpec extends AsyncFunSuite with ActorSuite with Matchers {
  import ActorOfSpec._

  test("ActorOf") {
    `actorOf`[IO](actorSystem).run()
  }

  def `actorOf`[F[_] : Concurrent : ToFuture : FromFuture](
    actorSystem: ActorSystem
  ): F[Unit] = {

    def receiveOf(receiveTimeout: F[Unit]) = (ctx: ActorCtx.Any[F]) => {

      val receive = new Receive.Any[F] {

        def apply(a: Any, reply: Reply[F, Any]) = {
          a match {
            case a: WithCtx[_, _] =>
              val f = a.asInstanceOf[WithCtx[F, Any]].f
              for {
                a <- f(ctx)
                _ <- reply(a)
              } yield {
                false
              }

            case ReceiveTimeout =>
              for {
                _ <- ctx.setReceiveTimeout(Duration.Inf)
                _ <- receiveTimeout
              } yield false

            case "stop" =>
              for {
                _ <- reply("stopping")
              } yield {
                true
              }

            case _      => false.pure[F]
          }
        }

        def postStop = ().pure[F]
      }

      receive.some.pure[F]
    }

    for {
      receiveTimeout <- Deferred[F, Unit]
      receive         = receiveOf(receiveTimeout.complete(()))
      actorRefOf      = ActorRefOf[F](actorSystem)
      actorEffect     = ActorEffect.of[F](actorRefOf, receive)
      probe           = Probe.of[F](actorSystem)
      resources       = (actorEffect, probe).tupled
      result         <- resources.use { case (actorRef, probe) => `actorOf`[F](actorRef, probe, receiveTimeout.get) }
    } yield {
      result
    }
  }

  def `actorOf`[F[_] : Async : ToFuture : FromFuture](
    actorRef: ActorEffect[F, Any, Any],
    probe: Probe[F],
    receiveTimeout: F[Unit]
  ): F[Unit] = {

    val timeout = 1.second


    def withCtx[A](f: ActorCtx.Any[F] => F[A]): F[A] = {
      for {
        a <- actorRef.ask(WithCtx(f), timeout)
        a <- a.cast[F, A]
      } yield a
    }


    for {
      terminated0 <- probe.watch(actorRef.toUnsafe)
      dispatcher  <- withCtx { _.dispatcher.pure[F] }
      _           <- Sync[F].delay { dispatcher.toString shouldEqual "Dispatcher[akka.actor.default-dispatcher]" }
      a           <- withCtx { _.actorOf(TestActors.blackholeProps, "child".some).allocated }
      (child0, childRelease) = a
      terminated1 <- probe.watch(child0)
      children    <- withCtx { _.children }
      _           <- Sync[F].delay { children.toList shouldEqual List(child0) }
      child        = withCtx { _.child("child") }
      child1      <- child
      _           <- Sync[F].delay { child1 shouldEqual child0.some }
      _           <- childRelease
      _           <- terminated1
      child1      <- child
      _           <- Sync[F].delay { child1 shouldEqual none[ActorRef] }
      children    <- withCtx { _.children }
      _           <- Sync[F].delay { children.toList shouldEqual List.empty }
      identity    <- actorRef.ask(Identify("id"), timeout)
      identity    <- identity.cast[F, ActorIdentity]
      _           <- withCtx { _.setReceiveTimeout(1.millis) }
      _           <- receiveTimeout
      _           <- Sync[F].delay { identity shouldEqual ActorIdentity("id", actorRef.toUnsafe.some) }
      a           <- actorRef.ask("stop", timeout)
      _           <- Sync[F].delay { a shouldEqual "stopping" }
      _           <-terminated0
    } yield {}
  }
}


object ActorOfSpec {

  implicit class AnyOps[A](val self: A) extends AnyVal {

    def cast[F[_] : Sync, B <: A]/*(implicit tag: ClassTag[B])*/: F[B] = {
      try {
        self.asInstanceOf[B].pure[F]
      } catch {
        case error: Throwable => error.raiseError[F, B]
      }
    }
  }

  final case class WithCtx[F[_], A](f: ActorCtx.Any[F] => F[A])
}
