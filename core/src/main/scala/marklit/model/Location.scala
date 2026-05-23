package marklit.model

/** Source location in a markdown file */
final case class Location(
    file: String,
    startLine: Int,
    startColumn: Int,
    endLine: Int,
    endColumn: Int
):
  def pretty: String = s"$file:$startLine:$startColumn"

object Location:
  def apply(file: String, line: Int, column: Int): Location =
    Location(file, line, column, line, column)
