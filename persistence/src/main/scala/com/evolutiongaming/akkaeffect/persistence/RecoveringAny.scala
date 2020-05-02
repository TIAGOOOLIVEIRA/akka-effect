package com.evolutiongaming.akkaeffect.persistence

import cats.Monad
import cats.effect.Resource
import cats.implicits._
import com.evolutiongaming.akkaeffect.ReceiveAny

/**
  * Describes "Recovery" phase
  *
  * @tparam S snapshot
  * @tparam C command
  * @tparam E event
  */
trait RecoveringAny[F[_], S, C, E] {
  /**
    * Used to replay events during recovery against passed state,
    * resource will be released when recovery is completed
    */
  def replay: Resource[F, Replay[F, E]]

  /**
    * Called when recovery completed, resource will be released upon actor termination
    *
    * @see [[akka.persistence.RecoveryCompleted]]
    */
  def completed(
    seqNr: SeqNr,
    journaller: Journaller[F, E],
    snapshotter: Snapshotter[F, S]
  ): Resource[F, ReceiveAny[F, C]]
}

object RecoveringAny {

  def empty[F[_]: Monad, S, C, E]: RecoveringAny[F, S, C, E] = new RecoveringAny[F, S, C, E] {

    def replay = Replay.empty[F, E].pure[Resource[F, *]]

    def completed(seqNr: SeqNr, journaller: Journaller[F, E], snapshotter: Snapshotter[F, S]) = {
      ReceiveAny.empty[F, C].pure[Resource[F, *]]
    }
  }


  implicit class RecoveringOps[F[_], S, C, E](val self: RecoveringAny[F, S, C, E]) extends AnyVal {

    def convert[S1, C1, E1](
      sf: S => F[S1],
      cf: C1 => F[C],
      ef: E => F[E1],
      e1f: E1 => F[E])(implicit
      F: Monad[F],
    ): RecoveringAny[F, S1, C1, E1] = new RecoveringAny[F, S1, C1, E1] {

      def replay = self.replay.map { _.convert(e1f) }

      def completed(
        seqNr: SeqNr,
        journaller: Journaller[F, E1],
        snapshotter: Snapshotter[F, S1]
      ) = {
        val journaller1 = journaller.convert(ef)
        val snapshotter1 = snapshotter.convert(sf)
        self
          .completed(seqNr, journaller1, snapshotter1)
          .map { _.convert(cf) }
      }
    }


    def widen[S1 >: S, C1 >: C, E1 >: E](
      cf: C1 => F[C],
      ef: E1 => F[E])(implicit
      F: Monad[F]
    ): RecoveringAny[F, S1, C1, E1] = new RecoveringAny[F, S1, C1, E1] {

      def replay = self.replay.map { _.convert(ef) }

      def completed(
        seqNr: SeqNr,
        journaller: Journaller[F, E1],
        snapshotter: Snapshotter[F, S1]
      ) = {
        self
          .completed(seqNr, journaller, snapshotter)
          .map { _.convert(cf) }
      }
    }


    def typeless(
      cf: Any => F[C],
      ef: Any => F[E])(implicit
      F: Monad[F]
    ): RecoveringAny[F, Any, Any, Any] = widen(cf, ef)
  }
}
