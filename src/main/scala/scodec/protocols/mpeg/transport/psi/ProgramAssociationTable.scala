package scodec.protocols.mpeg
package transport
package psi

import fs2._

import scodec.Codec
import scodec.codecs._

case class ProgramAssociationTable(
  tsid: TransportStreamId,
  version: Int,
  current: Boolean,
  programByPid: Map[ProgramNumber, Pid]
) extends Table {
  def tableId = ProgramAssociationSection.TableId
  def toSections: GroupedSections[ProgramAssociationSection] = ProgramAssociationTable.toSections(this)
}

object ProgramAssociationTable {

  val MaxProgramsPerSection = 253

  def toSections(pat: ProgramAssociationTable): GroupedSections[ProgramAssociationSection] = {
    val entries = pat.programByPid.toVector.sortBy { case (ProgramNumber(n), _) => n }
    val groupedEntries = entries.grouped(MaxProgramsPerSection).toVector
    val lastSection = groupedEntries.size - 1
    val sections = groupedEntries.zipWithIndex.map { case (es, idx) =>
      ProgramAssociationSection(SectionExtension(pat.tsid.value, pat.version, pat.current, idx, lastSection), es)
    }
    if (sections.isEmpty)
      GroupedSections(ProgramAssociationSection(SectionExtension(pat.tsid.value, pat.version, pat.current, 0, 0), Vector.empty))
    else
      GroupedSections(sections.head, sections.tail.toList)
  }

  def fromSections(sections: GroupedSections[ProgramAssociationSection]): Either[String, ProgramAssociationTable] = {
    def extract[A](name: String, f: ProgramAssociationSection => A): Either[String, A] = {
      val extracted = sections.list.map(f).distinct
      if (extracted.size == 1) Right(extracted.head)
      else Left(s"sections have diferring $name: " + extracted.mkString(", "))
    }
    for {
      tsid <- extract("TSIDs", _.tsid).right
      version <- extract("versions", _.extension.version).right
    } yield {
      val current = sections.list.foldLeft(false) { (acc, s) => acc || s.extension.current }
      ProgramAssociationTable(
        tsid,
        version,
        current,
        (for {
          section <- sections.list
          pidMapping <- section.pidMappings
        } yield pidMapping).toMap)
    }
  }

  implicit val tableSupport: TableSupport[ProgramAssociationTable] = new TableSupport[ProgramAssociationTable] {
    def tableId = ProgramAssociationSection.TableId
    def toTable(gs: GroupedSections[Section]) =
      gs.narrow[ProgramAssociationSection].toRight("Not PAT sections").right.flatMap { sections => fromSections(sections) }
    def toSections(pat: ProgramAssociationTable) = ProgramAssociationTable.toSections(pat)
  }
}

case class ProgramAssociationSection(
  extension: SectionExtension,
  pidMappings: Vector[(ProgramNumber, Pid)]
) extends ExtendedSection {
  def tableId = ProgramAssociationSection.TableId
  def tsid: TransportStreamId = TransportStreamId(extension.tableIdExtension)
}

object ProgramAssociationSection {
  val TableId = 0

  private type Fragment = Vector[(ProgramNumber, Pid)]

  private val fragmentCodec: Codec[Fragment] = {
    vector {
      ("program_number" | Codec[ProgramNumber]) ~
      (reserved(3) ~>
      ("pid" | Codec[Pid]))
    }
  }

  implicit val sectionFragmentCodec: SectionFragmentCodec[ProgramAssociationSection] =
    SectionFragmentCodec.psi[ProgramAssociationSection, Vector[(ProgramNumber, Pid)]](
      TableId,
      (ext, mappings) => ProgramAssociationSection(ext, mappings),
      pat => (pat.extension, pat.pidMappings)
    )(fragmentCodec)
}
