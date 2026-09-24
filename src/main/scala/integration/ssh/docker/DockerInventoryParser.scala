package ru.bitec.app.ops
package integration.ssh.docker

final case class DockerInventoryParseError(message: String) extends RuntimeException(message)

object DockerInventoryParser {
  def parse(stdout: String): Either[DockerInventoryParseError, List[DockerContainerInventory]] = {
    val rows = stdout.linesIterator.filter(_.trim.nonEmpty).toList
    rows.zipWithIndex.foldLeft[
      Either[DockerInventoryParseError, (List[DockerContainerInventory], Set[String])]
    ](Right(Nil -> Set.empty)) {
      case (acc, (line, index)) =>
        for {
          state <- acc
          (current, ids) = state
          container <- parseRow(line, index + 1)
          _ <- Either.cond(
            !ids.contains(container.id),
            (),
            DockerInventoryParseError(s"Docker inventory contains duplicate container id on row ${index + 1}")
          )
        } yield (container :: current) -> (ids + container.id)
    }.map { case (containers, _) => containers.reverse }
  }

  private def parseRow(line: String, row: Int): Either[DockerInventoryParseError, DockerContainerInventory] =
    line.split("\\t", -1).toList match {
      case id :: name :: image :: state :: Nil if id.trim.nonEmpty && name.trim.nonEmpty =>
        Right(DockerContainerInventory(id.trim, name.trim, optional(image), optional(state)))
      case _ => Left(DockerInventoryParseError(s"Malformed Docker inventory row $row"))
    }

  private def optional(value: String): Option[String] = Option(value.trim).filter(_.nonEmpty)
}
