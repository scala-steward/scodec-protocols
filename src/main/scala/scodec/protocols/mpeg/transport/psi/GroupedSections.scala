package scodec.protocols
package mpeg
package transport
package psi

import scala.reflect.ClassTag

import fs2._

/**
 * Group of sections that make up a logical message.
 *
 * Intermediate representation between sections and tables. All sections must share the same table id.
 */
sealed abstract class GroupedSections[+A <: Section] {
  def tableId: Int

  def head: A
  def tail: List[A]

  def list: List[A]
}

object GroupedSections {
  implicit class InvariantOps[A <: Section](val self: GroupedSections[A]) extends AnyVal {
    def narrow[B <: A : ClassTag]: Option[GroupedSections[B]] = {
      val matched = self.list.foldLeft(true) { (acc, s) => s match { case _: B => true; case _ => false } }
      if (matched) Some(self.asInstanceOf[GroupedSections[B]])
      else None
    }
  }

  private case class DefaultGroupedSections[A <: Section](head: A, tail: List[A]) extends GroupedSections[A] {
    val tableId = head.tableId
    val list = head :: tail
  }

  def apply[A <: Section](head: A, tail: List[A] = Nil): GroupedSections[A] =
    DefaultGroupedSections[A](head, tail)

  final case class ExtendedTableId(tableId: Int, tableIdExtension: Int)
  final case class ExtendedSectionGrouperState[A <: ExtendedSection](accumulatorByIds: Map[ExtendedTableId, SectionAccumulator[A]])

  def groupExtendedSections[A <: ExtendedSection]: Transform[ExtendedSectionGrouperState[A], A, Either[GroupingError, GroupedSections[A]]] = {
    def toKey(section: A): ExtendedTableId = ExtendedTableId(section.tableId, section.extension.tableIdExtension)
    Transform.stateful[ExtendedSectionGrouperState[A], A, Either[GroupingError, GroupedSections[A]]](ExtendedSectionGrouperState(Map.empty)) { (state, section) =>
      val key = toKey(section)
      val (err, acc) = state.accumulatorByIds.get(key) match {
        case None => (None, SectionAccumulator(section))
        case Some(acc) =>
          acc.add(section) match {
            case Right(acc) => (None, acc)
            case Left(err) => (Some(GroupingError(section.tableId, section.extension.tableIdExtension, err)), SectionAccumulator(section))
          }
      }

      acc.complete match {
        case None =>
          val newState = ExtendedSectionGrouperState(state.accumulatorByIds + (key -> acc))
          val out = err.map(e => Chunk.singleton(Left(e))).getOrElse(Chunk.empty)
          (newState, out)
        case Some(sections) =>
          val newState = ExtendedSectionGrouperState(state.accumulatorByIds - key)
          val out = Chunk.seq((Right(sections) :: err.map(e => Left(e)).toList).reverse)
          (newState, out)
      }
    }
  }

  def noGrouping: Transform[Unit, Section, Either[GroupingError, GroupedSections[Section]]] =
    Transform.lift(s => Right(GroupedSections(s)))

  /**
   * Groups sections in to groups.
   *
   * Extended sections, aka sections with the section syntax indicator set to true, are automatically handled.
   * Non-extended sections are emitted as singleton groups.
   */
  def group: Transform[ExtendedSectionGrouperState[ExtendedSection], Section, Either[GroupingError, GroupedSections[Section]]] = {
    groupGeneral((), noGrouping).xmapState(_._2)(s => ((), s))
  }

  /**
   * Groups sections in to groups.
   *
   * Extended sections, aka sections with the section syntax indicator set to true, are automatically handled.
   * The specified `nonExtended` process is used to handle non-extended sections.
   */
  def groupGeneral[NonExtendedState](
    initialNonExtendedState: NonExtendedState,
    nonExtended: Transform[NonExtendedState, Section, Either[GroupingError, GroupedSections[Section]]]
  ): Transform[(NonExtendedState, ExtendedSectionGrouperState[ExtendedSection]), Section, Either[GroupingError, GroupedSections[Section]]] = {
    groupGeneralConditionally(initialNonExtendedState, nonExtended, _ => true)
  }

  /**
   * Groups sections in to groups.
   *
   * Extended sections, aka sections with the section syntax indicator set to true, are automatically handled if `true` is returned from the
   * `groupExtended` function when applied with the section in question.
   *
   * The specified `nonExtended` transducer is used to handle non-extended sections.
   */
  def groupGeneralConditionally[NonExtendedState](
    initialNonExtendedState: NonExtendedState,
    nonExtended: Transform[NonExtendedState, Section, Either[GroupingError, GroupedSections[Section]]],
    groupExtended: ExtendedSection => Boolean = _ => true
  ): Transform[(NonExtendedState, ExtendedSectionGrouperState[ExtendedSection]), Section, Either[GroupingError, GroupedSections[Section]]] = {
    Transform.stateful[(NonExtendedState, ExtendedSectionGrouperState[ExtendedSection]), Section, Either[GroupingError, GroupedSections[Section]]]((initialNonExtendedState, ExtendedSectionGrouperState(Map.empty))) { case ((nonExtendedState, extendedState), section) =>
      section match {
        case s: ExtendedSection if groupExtended(s) =>
          val (newExtendedState, out) = groupExtendedSections.transform(extendedState, s)
          ((nonExtendedState, newExtendedState), out)
        case s: Section =>
          val (newNonExtendedState, out) = nonExtended.transform(nonExtendedState, s)
          ((newNonExtendedState, extendedState), out)
      }
    }
  }
}
