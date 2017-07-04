package scodec.protocols

import language.higherKinds

import fs2._

sealed abstract class Transform[-I,+O] {

  type S
  val initial: S
  val transform: (S,I) => Segment[O,S]

  def toPipe[F[_]]: Pipe[F,I,O] =
    _.scanSegments(initial)((state, segment) => segment.flatMapAccumulate(state)(transform).mapResult(_._2))

  def andThen[O2](t: Transform[O,O2]): Transform.Aux[(S,t.S),I,O2] =
    Transform.stateful[(S,t.S),I,O2]((initial, t.initial)) { case ((s,s2),i) =>
      transform(s,i).flatMapAccumulate(s2)(t.transform)
    }

  def map[O2](f: O => O2): Transform.Aux[S,I,O2] =
    Transform.stateful[S,I,O2](initial)((s,i) => transform(s, i).map(f))

  def contramap[I2](f: I2 => I): Transform.Aux[S,I2,O] =
    Transform.stateful[S,I2,O](initial)((s,i2) => transform(s, f(i2)))

  def xmapState[S2](g: S => S2)(f: S2 => S): Transform.Aux[S2,I,O] =
    Transform.stateful[S2,I,O](g(initial))((s2,i) => transform(f(s2),i).mapResult(g))

  def lens[I2,O2](get: I2 => I, set: (I2, O) => O2): Transform.Aux[S,I2,O2] =
    Transform.stateful[S,I2,O2](initial)((s,i2) => transform(s, get(i2)).map(s => set(i2, s)))

  def first[A]: Transform.Aux[S,(I, A), (O, A)] =
    lens(_._1, (t, o) => (o, t._2))

  def second[A]: Transform.Aux[S,(A, I), (A, O)] =
    lens(_._2, (t, o) => (t._1, o))

  def semilens[I2,O2](extract: I2 => Either[O2, I], inject: (I2, O) => O2): Transform.Aux[S,I2,O2] =
    Transform.stateful[S,I2,O2](initial)((s,i2) => extract(i2).fold(
      o2 => Chunk.singleton(o2).mapResult(_ => s),
      i => transform(s, i).map(s => inject(i2, s))))

  def semipass[I2,O2 >: O](extract: I2 => Either[O2, I]): Transform.Aux[S,I2,O2] = semilens(extract, (_, o) => o)

  def left[A]: Transform.Aux[S, Either[I, A], Either[O, A]] =
    semilens(_.fold(i => Right(i), a => Left(Right(a))), (_, o) => Left(o))

  def right[A]: Transform.Aux[S, Either[A, I], Either[A, O]] =
    semilens(_.fold(a => Left(Left(a)), i => Right(i)), (_, o) => Right(o))

  def choice[I2,O2 >: O](t: Transform[I2,O2]): Transform.Aux[(S,t.S),Either[I,I2],O2] =
    Transform.stateful[(S,t.S),Either[I,I2],O2]((initial, t.initial)) { case ((s,s2),e) =>
      e match {
        case Left(i) => transform(s,i).mapResult(_ -> s2)
        case Right(i2) => t.transform(s2,i2).mapResult(s -> _)
      }
    }

  def either[I2,O2](t: Transform[I2,O2]): Transform.Aux[(S,t.S),Either[I,I2],Either[O,O2]] =
    Transform.stateful[(S,t.S),Either[I,I2],Either[O,O2]]((initial, t.initial)) { case ((s,s2),e) =>
      e match {
        case Left(i) => transform(s,i).map(Left(_)).mapResult(_ -> s2)
        case Right(i2) => t.transform(s2,i2).map(Right(_)).mapResult(s -> _)
      }
    }
}

object Transform {
  type Aux[S0,I,O] = Transform[I,O] { type S = S0 }

  def stateful[S0,I,O](initial0: S0)(f: (S0, I) => Segment[O,S0]): Transform.Aux[S0,I,O] =
    new Transform[I,O] {
      type S = S0
      val initial = initial0
      val transform = f
    }

  def stateful1[S,I,O](initial: S)(f: (S, I) => (S, O)): Transform.Aux[S,I,O] =
    stateful[S,I,O](initial) { (s,i) => val (s2,o) = f(s,i); Segment.singleton(o).asResult(s2) }

  def stateless[I,O](f: I => Segment[O,Unit]): Transform.Aux[Unit,I,O] =
    stateful[Unit,I,O](())((u,i) => f(i))

  def lift[I,O](f: I => O): Transform.Aux[Unit,I,O] =
    stateless(i => Chunk.singleton(f(i)))
}
