package com.evolutiongaming.akkaeffect

import akka.actor.{Actor, ActorContext, ActorRef, Props, Terminated}
import cats.effect.concurrent.{Deferred, Ref}
import cats.effect.{Concurrent, Resource, Sync}
import cats.implicits._
import com.evolutiongaming.catshelper.CatsHelper._
import com.evolutiongaming.catshelper.{FromFuture, SerialRef, ToFuture}


trait Probe[F[_]] {
  import Probe._

  def actor: ActorEffect[F, Any, Any]

  def expect: F[F[Envelop]]

  def last: F[Option[Envelop]]

  def watch(actorRef: ActorRef): F[F[Unit]]
}

object Probe {

  type Unsubscribe = Boolean

  type Listener[F[_]] = Envelop => F[Unsubscribe]

  final case class Envelop(msg: Any, sender: ActorRef)

  def of[F[_] : Concurrent : ToFuture : FromFuture](
    actorRefOf: ActorRefOf[F]
  ): Resource[F, Probe[F]] = {

    def history(subscribe: Listener[F] => F[Unit]) = {
      for {
        history  <- Ref[F].of(none[Envelop])
        listener  = (a: Envelop) => history.set(a.some).as(false)
        _        <- subscribe(listener)
      } yield history
    }

    val listeners = SerialRef[F].of(Set.empty[Listener[F]])

    for {
      listeners <- Resource.liftF(listeners)
      subscribe  = (listener: Listener[F]) => listeners.update { listeners => (listeners + listener).pure[F] }
      resources <- actorRef(listeners, actorRefOf)
      (actorRef, run) = resources
      history   <- Resource.liftF(history(subscribe))
    } yield {
      new Probe[F] {

        val actor = ActorEffect.fromActor(actorRef)

        val expect = {
          for {
            deferred <- Deferred[F, Envelop]
            listener  = (a: Envelop) => deferred.complete(a).as(true)
            _        <- subscribe(listener)
          } yield {
            deferred.get
          }
        }
        
        def last = history.get

        def watch(actorRef: ActorRef) = {

          def listenerOf(deferred: Deferred[F, Unit]) = {
            a: Envelop => a.msg match {
              case Terminated(`actorRef`) => deferred.complete(()).as(true)
              case _                      => false.pure[F]
            }
          }

          for {
            deferred <- Deferred[F, Unit]
            listener  = listenerOf(deferred)
            _        <- subscribe(listener)
            _        <- run { context => Sync[F].delay {  context.watch(actorRef) }.void }
          } yield {
            deferred.get
          }
        }
      }
    }
  }

  def actorRef[F[_] : Sync : ToFuture : FromFuture](
    listeners: SerialRef[F, Set[Listener[F]]],
    actorRefOf: ActorRefOf[F]
  ): Resource[F, (ActorRef, (ActorContext => F[Unit]) => F[Unit])] = {

    case class Run(f: ActorContext => F[Unit])

    def actor = {

      def receive(envelop: Envelop) = {
        listeners.update { listeners =>
          listeners.foldLeft(listeners.pure[F]) { (listeners, listener) =>
            for {
              listeners   <- listeners
              unsubscribe <- listener(envelop)
            } yield {
              if (unsubscribe) listeners - listener else listeners
            }
          }
        }
      }

      val state = StateVar[F].of(())

      def onAny(a: Any, sender: ActorRef): Unit = {
        state.update { _ => receive(Envelop(a, sender)) }
      }

      new Actor {
        def receive = {
          case Run(f) => f(context).toFuture; ()
          case a: Any => onAny(a, sender())
        }
      }
    }

    val props = Props(actor)
    actorRefOf(props).map { actorRef =>
      val run = (f: ActorContext => F[Unit]) => Sync[F].delay { actorRef.tell(Run(f), ActorRef.noSender) }
      (actorRef, run)
    }
  }
}
