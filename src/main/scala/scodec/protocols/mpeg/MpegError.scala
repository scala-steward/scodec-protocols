package scodec.protocols
package mpeg

import fs2._
import scodec.Err
import scodec.bits.BitVector

import process1ext._

trait MpegError {
  def message: String
}

object MpegError {

  case class General(message: String) extends MpegError {
    override def toString = message
  }
  case class Decoding(data: BitVector, err: Err) extends MpegError {
    def message = s"error encountered when decoding: $err ${data.toHex}"
    override def toString = message
  }

  def joinErrors[A, B](p: Process1[A, Either[MpegError, B]]): Process1[Either[MpegError, A], Either[MpegError, B]] =
    p.conditionallyFeed {
      case Right(a) => Left(a)
      case e @ Left(_) => Right(e.asInstanceOf[Either[MpegError, B]])
    }

  def passErrors[A, B](p: Process1[A, B]): Process1[Either[MpegError, A], Either[MpegError, B]] =
    p.liftR
}
