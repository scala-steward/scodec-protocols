package scodec.protocols
package mpeg
package transport
package psi

import fs2._
import fs2.pipe.Stepper

import scodec.bits.BitVector

import psi.{ Table => TableMessage }

import pipes._

abstract class TransportStreamEvent

object TransportStreamEvent {
  case class Pes(pid: Pid, pes: PesPacket) extends TransportStreamEvent
  case class Table(pid: Pid, table: TableMessage) extends TransportStreamEvent
  case class ScrambledPayload(pid: Pid, payload: BitVector) extends TransportStreamEvent
  case class Metadata[A](pid: Option[Pid], metadata: A) extends TransportStreamEvent
  case class Error(pid: Option[Pid], err: MpegError) extends TransportStreamEvent

  def pes(pid: Pid, pes: PesPacket): TransportStreamEvent = Pes(pid, pes)
  def table(pid: Pid, table: TableMessage): TransportStreamEvent = Table(pid, table)
  def scrambledPayload(pid: Pid, content: BitVector): TransportStreamEvent = ScrambledPayload(pid, content)
  def metadata[A](md: A): TransportStreamEvent = Metadata(None, md)
  def metadata[A](pid: Pid, md: A): TransportStreamEvent = Metadata(Some(pid), md)
  def error(pid: Pid, e: MpegError): TransportStreamEvent = Error(Some(pid), e)
  def error(pid: Option[Pid], e: MpegError): TransportStreamEvent = Error(pid, e)

  private def sectionsToTables(
    group: Pipe[Pure, Section, Either[GroupingError, GroupedSections[Section]]], tableBuilder: TableBuilder
  ): Pipe[Pure, PidStamped[Either[MpegError, Section]], TransportStreamEvent] = {

    import MpegError._

    type In = PidStamped[Either[MpegError, Section]]
    type Out = PidStamped[Either[MpegError, TableMessage]]
    type SectionsToTables = Pipe[Pure, In, Out]

    val sectionsToTables: SectionsToTables = {
      def newSectionsToTablesForPid: Option[Chunk[In]] => Stepper[In, Out] = {
        val transducer: Pipe[Pure, In, Out] = PidStamped.preservePidStamps(joinErrors(group) andThen joinErrors(tableBuilder.sectionsToTables))
        // Safe to cast the step here because the various components that make up the transducer won't fail, end, or output until they receive a value
        pipe.stepper(transducer).step.asInstanceOf[Stepper.Await[In, Out]].receive
      }

      type ThisHandle = Handle[Pure, In]
      type ThisPull = Pull[Pure, Out, ThisHandle]

      def gather(s: Stepper[In, Out], acc: Vector[Out]): Either[Throwable, Vector[Out]] = s.step match {
        case Stepper.Done => Right(acc)
        case Stepper.Fail(err) => Left(err)
        case Stepper.Emits(out, next) => gather(next, acc ++ out.toVector)
        case Stepper.Await(receive) => Right(acc)
      }

      def go(state: Map[Pid, Option[Chunk[In]] => Stepper[In, Out]]): ThisHandle => ThisPull = h => {
        h.await1Option.flatMap {
          case None =>
            val allOut = state.values.foldLeft(Right(Vector.empty): Either[Throwable, Vector[Out]]) { (accEither, await) =>
              accEither.right.flatMap { acc =>
                gather(await(None), Vector.empty).right.map { out => acc ++ out }
              }
            }
            (allOut match {
              case Left(err) => Pull.fail(err)
              case Right(out) => Pull.output(Chunk.indexedSeq(out))
            }) >> Pull.done

          case Some((event, tl)) =>
            val await = state.getOrElse(event.pid, newSectionsToTablesForPid)
            await(Some(Chunk.singleton(event))).stepToAwait { (out, nextAwait) =>
              Pull.output(Chunk.indexedSeq(out)) >> go(state + (event.pid -> nextAwait))(tl)
            }
        }
      }

      _ pull go(Map.empty)
    }

    sectionsToTables andThen PidStamped.preservePidStamps(passErrors(TransportStreamIndex.build)) andThen pipe.lift {
      case PidStamped(pid, value) => value match {
        case Left(e) => error(pid, e)
        case Right(Left(tsi)) => metadata(tsi)
        case Right(Right(tbl)) => table(pid, tbl)
      }
    }
  }

  def fromPacketStream(
    sectionCodec: SectionCodec,
    tableBuilder: TableBuilder,
    group: Pipe[Pure, Section, Either[GroupingError, GroupedSections[Section]]] = GroupedSections.group
  ): Pipe[Pure, Packet, TransportStreamEvent] = {
    val demuxed: Pipe[Pure, Packet, TransportStreamEvent] = {
      val demuxed: Pipe[Pure, Packet, PidStamped[Either[DemultiplexerError, Demultiplexer.Result]]] =
        Demultiplexer.demultiplex(sectionCodec)

      demuxed andThen pipes.conditionallyFeed[
        PidStamped[Either[MpegError, Section]],
        TransportStreamEvent,
        PidStamped[Either[DemultiplexerError, Demultiplexer.Result]]
      ](sectionsToTables(group, tableBuilder), {
        case PidStamped(pid, Right(Demultiplexer.SectionResult(section))) =>
          Left(PidStamped(pid, Right(section)))
        case PidStamped(pid, Right(Demultiplexer.PesPacketResult(p))) =>
          Right(pes(pid, p))
        case PidStamped(pid, Left(e)) =>
          Left(PidStamped(pid, Left(e.toMpegError)))
      })
    }

    pipes.conditionallyFeed[Packet, TransportStreamEvent, Packet](demuxed, {
      case Packet(header, _, _, Some(payload)) if header.scramblingControl != 0 =>
        Right(scrambledPayload(header.pid, payload))
      case p @ Packet(_, _, _, _) =>
        Left(p)
    })
  }

  def fromSectionStream(
    tableBuilder: TableBuilder,
    group: Pipe[Pure, Section, Either[GroupingError, GroupedSections[Section]]] = GroupedSections.group
  ): Pipe[Pure, PidStamped[Section], TransportStreamEvent] = {
    pipe.lift((p: PidStamped[Section]) => p map Right[MpegError, Section]) andThen sectionsToTables(group, tableBuilder)
  }
}
