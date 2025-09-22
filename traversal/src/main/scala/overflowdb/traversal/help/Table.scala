package overflowdb.traversal.help

import java.io.{ByteArrayOutputStream, PrintStream}
import java.nio.charset.StandardCharsets
import scala.util.Using

case class Table(columnNames: Iterable[String], rows: Iterable[Iterable[String]]):

    lazy val render: String =
        Using.Manager { use =>
            val charset     = StandardCharsets.UTF_8
            val baos        = use(new ByteArrayOutputStream)
            val ps          = use(new PrintStream(baos, true, charset.name))
            val rowsAsArray = rows.map(_.map(_ + " ").toArray.asInstanceOf[Array[Object]]).toArray
            new TextTable(columnNames.toArray, rowsAsArray).printTable(ps, 0)
            new String(baos.toByteArray, charset)
        }.get

class TextTable(columnNames: Array[String], data: Array[Array[Object]]):

    def printTable(ps: PrintStream, indent: Int): Unit =
        val numRows   = data.length
        val numCols   = columnNames.length
        val colWidths = new Array[Int](numCols)
        for i <- columnNames.indices do
            colWidths(i) = columnNames(i).length
        for row <- data do
            for i <- 0 until math.min(row.length, numCols) do
                val cellLength = if row(i) != null then row(i).toString.length else 0
                if cellLength > colWidths(i) then
                    colWidths(i) = cellLength
        printRow(ps, indent, columnNames, colWidths)
        val separator = colWidths.map(w => "-" * w).mkString("|", "|", "|")
        ps.println(" " * indent + separator)
        for row <- data do
            val stringRow = new Array[String](row.length)
            for i <- row.indices do
                stringRow(i) = if row(i) != null then row(i).toString else ""
            printRow(ps, indent, stringRow, colWidths)
    end printTable

    private def printRow(
      ps: PrintStream,
      indent: Int,
      row: Array[String],
      widths: Array[Int]
    ): Unit =
        val paddedCells = for (i <- row.indices) yield padRight(row(i), widths(i))
        ps.println(" " * indent + paddedCells.mkString("|", "|", "|"))

    private def padRight(s: String, width: Int): String =
        val padding = width - s.length
        if padding > 0 then s + " " * padding else s
end TextTable
