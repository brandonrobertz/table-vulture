package org.bxroberts.tablevulture

import java.lang.System
import java.io.File
import scala.collection.mutable.ArrayBuffer

import com.snowtide.pdf.Document
import com.github.tototoshi.csv.CSVWriter

/**
 * A survey question object.
 */
class TableQuestion(_text: String) {
  def text = _text

  private def lines = _text.split("\n").map(_.trim).filter(!_.isEmpty)
  def topText(): String = {
    val top = lines(0)
    return top
  }
  def bottomText(): String = {
    val btm = lines(lines.length-1)
    return btm
  }
}

/**
 * A description of a table we're intending on extracting.
 */
class TableDesc(_title: String, _questions: Array[TableQuestion], _nValues: Int = 14) {
  def title = _title
  def questions = _questions
  def nValues = _nValues
}

/**
 * Table row. Holds box and contents of row.
 * NOTE: We may need to make pg and box arrays
 * in case the question spans more than one page.
 */
class TableRow(_pg: Int, _box: Box) {
  def pg = _pg
  def box = _box
  override def toString(): String = {
    return f"TableRow pg: ${pg}%d, ${box.toString}%s"
  }
}

/**
 * All the things we need for table extaction.
 */
class TableExtractor(pdf: Document) {
  def p = new Primitives(pdf)

  def findTableRow(
    pg: Int, question: TableQuestion, startY: Int, depth: Int = 0
  ): (TableRow, Box, Int) = {
    var pgSize: Size = p.pageSize(pg)
    var minHeight = 25;

    if (depth > 3) {
      println("Max find row depth met, failing extraction!")
      System.exit(1)
    }

    // val question = table.questions(i)
    println("findTableRow -----------------------------------------------")
    println(f"Question text: ${question.text}")
    println(f"Finding question top: ${question.topText}%s")
    println(f"Start Y: ${startY}%d")
    val topCoord = p.findText(
      pg, question.topText, 0, startY=startY
    )
    println(f"Found top coord: ${topCoord.toString}%s")

    if (topCoord.y == -1) {
      println("Not found, checking next page")
      return findTableRow(pg + 1, question, pgSize.h, depth + 1)
    }

    // use the topY as start (not topY-1) in case we have
    // single line question
    println("------------------------------------------------------------")
    println(f"Finding question bottom: ${question.bottomText}%s")
    val btmCoord = p.findText(
      pg, question.bottomText, 0, startY=topCoord.y
    )
    println(f"Found bottom coord: ${btmCoord.toString}%s")

    // build a box using our two Y coords, assume 100% width
    println("------------------------------------------------------------")
    println("Building box")
    var height = topCoord.y - btmCoord.y
    if (height < minHeight) {
      height = minHeight
    }
    val box = new Box(
      pg, 0, topCoord.y, pgSize.w, -height
    )
    println(f"box: ${box.toString}%s")

    println("------------------------------------------------------------")
    println("Building tableRow")
    val tableRow = new TableRow(pg, box)
    println(f"tableRow: ${tableRow.toString}%s")
    return (tableRow, box, btmCoord.y)
  }

  /**
   * For a given table description, find the TableRows
   * identifying each row in the table.
   */
  def findTableRows(table: TableDesc): Array[TableRow] = {
    println("%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%% Identifying title page")
    var pg: Int = p.identifyPage(table.title)
    println(f"Page: ${pg}%d")
    val titleCoord: Coord = p.findText(pg, table.title)
    println(f"Title coord: ${titleCoord.toString}%s")

    println(f"findTableRows pg: ${pg}%d")
    println(f"titleCoord: ${titleCoord.toString}%s")
    println(f"Number of questions: ${table.questions.length}%d")

    // Identify our table's question row blocks
    // TODO: This search needs to span pages.
    var lastY: Int = titleCoord.y - 1
    val nQuestions: Int = table.questions.length
    var rows: Array[TableRow] = Array.empty
    for (i <- 0 to nQuestions - 1) {
      println(f"=================================================== ROW ${i}%d")
      val question = table.questions(i)
      val (tableRow, box, btmY) = findTableRow(pg, question, lastY)
      rows ++= Array(tableRow)

      // save for next run, to avoid repeating rows for
      // texts that have duplicate starts of questions
      lastY = btmY - 1
      // save the page we found the text on
      pg = box.pg

      println("---------------------------------------------------")
      val boxText = p.boxText(tableRow.box)
      println(f"tableRow Box Text: '${boxText}%s'")
    }

    return rows
  }

  def cleanCellValue(text: String, stripChar: Boolean = false): String = {
    var tmpTxt = text
    if (stripChar) {
      tmpTxt = text.replaceAll("[^0-9\\(\\)%\\s\\.]+", "")
    }
    return tmpTxt.replace(
      "\n", " "
    ).replaceAll(
      "\\s+", " "
    ).trim
  }

  /**
   * Take a question and tableRow and split up the row into
   * the question (label) and individual values. Returns an
   * array of strings, ["label, "val1", ..., "valN"] to be
   * converted to CSV.
   *
   * Split question area algorithm:
   *
   * 1. scan x, from start of Q, until we hit end of question.
   *
   * 2. then scan until we hit something else
   *
   * 3. take the distance between the end and next item, divide
   * by 2 and that's our split point for question from vals
   *
   * 4. accept a given space threshold, scan X from the split point
   * looking for space split thresholds. when one is found, split
   * the values at that point, keep going until nValues is met
   */
  def splitTableRowNative(
    question: TableQuestion, row: TableRow, nValues: Int = 14
  ): ArrayBuffer[String] = {
    println("splitTableRowNative Building row cells...")
    var cells = ArrayBuffer[String]()
    // wait for full first line of question
    def findEndOfQ(text: String): Boolean = {
      val ptrn = p.regexify(question.topText)
      // println(f"searching for: ${question.topText}%s or: ${ptrn}%s")
      // println(f"text: ${text}%s")
      return p.exactOrRegexMatch(question.topText, ptrn, text)
    }
    // scan from the left, beginning of the top part of the question
    // towards the right of the page until we have the whole top line
    // of the question scanned. the end of the top line of the question
    // will be our endQX value
    val endQX = p.xScanUntil(
      row.pg, findEndOfQ, row.box.y, 0, "inc",
      scanLineSize = row.box.h
    )
    println(f"endQX: ${endQX}%d")

    def findStartOfVal(text: String): Boolean = {
      println(f"searching for number in text: ${text}%s")
      return text matches ".*[0-9\\.\\(\\)\\%]+"
    }
    val startVX = p.xScanUntil(
      row.pg, findStartOfVal, row.box.y, endQX + 5, "inc",
      scanLineSize = row.box.h
    )
    println(f"startVX: ${startVX}%d")

    // get the point right before the value matched (should be -1)
    val splitPointX = (endQX + startVX) / 2

    val qBox = new Box(
      row.pg, row.box.x, row.box.y, splitPointX - row.box.x, row.box.h
    )
    val qText: String = cleanCellValue(p.boxText(qBox))
    println(f"Question: ${qText}%s")
    cells += qText

    val vBox = new Box(
      row.pg, splitPointX, row.box.y, row.box.w - splitPointX, row.box.h
    )
    val values: String = cleanCellValue(p.boxText(vBox), stripChar=true)
    println(f"Values: ${values}%s")
    cells ++= values.split("\\s+", nValues)

    return cells
  }

  def findCharSplitPoint(line: String): Int = {
    for (i <- 0 to line.length - 1) {
      val c = line(i)
      if (c.toString() matches "[0-9\\.\\(\\)]") {
        return i
      }
    }
    return -1
  }

  def splitTableRow(
    question: TableQuestion, row: TableRow,
    // optional, for final output
    questionNum: Int = -1,
    pdfName: String = "", nValues: Int = 14
  ): ArrayBuffer[String] = {
    var cells = ArrayBuffer[String]()

    val charBlock = p.boxText(row.box)
    val lines = charBlock.split("\n")

    var qText = ""
    var values = ""
    for (line <- lines) {
      println(f"line: ${line}")
      // divide the numbers from the question
      if (line matches ".*[0-9].*") {
        val i = findCharSplitPoint(line)
        qText += line slice (0, i)
        values += line slice (i, line.length)
      }
      // this is just a question line
      else {
        qText += line
      }
    }

    println(f"row.box: ${row.box.toString}")
    println(f"charBlock: ${charBlock}")
    println(f"qText: ${qText}")
    println(f"values: ${values}")

    // append the individual values
    var splitValues = values.split("\\s+")
    println(f"Actual no. of values: ${splitValues.length}, Expected no.: ${nValues}")
    if (splitValues.length != nValues) {
      println("ERROR: Incorrect number of values detected. Bailing.")
      System.exit(1)
    }

    // save the actual question (for debugging)
    cells += qText.replaceAll("\n", " ").replaceAll("\\s+", " ").trim()
    cells ++= splitValues

    return cells
  }

  def writeCSV(rows: Array[Array[String]], header: Array[String], filename: String) = {
    val f = new File(filename)
    val writer = CSVWriter.open(f)
    writer.writeRow(header)
    for(row <- rows) {
      writer.writeRow(row)
    }
    writer.close()
  }

  /**
   * The algorithm for extracting tables:
   *
   * 1. Find the Y of table title
   *
   * 2. From that Y, for each Q in TableQuestions ...
   *   a. find the top of the first question
   *   b. find the bottom of the first question
   *
   * NOTE: All searches wrap to the next page
   *
   * 3. Take our question rows and for each
   * split the question from the values
   *
   * 4. Taking advantage of the 9 (10%) pattern
   * of the values (use a config), split the
   * values N times
   *
   * 5. This gives us a multi dimensional array
   * that we need to turn into a CSV
   */
  def extractTable(
    table: TableDesc, outputFile: String = "out.csv",
    pdfName: String = ""
  ) = {
    val tableRows: Array[TableRow] = findTableRows(table)
    var rows: ArrayBuffer[Array[String]] = ArrayBuffer.empty
    assert(tableRows.length == table.questions.length)
    var qPlusValCells: Int = 0
    for (i <- 0 to tableRows.length-1) {
      val question = table.questions(i)
      // println(f"Question ${question}%s")
      val row = tableRows(i)
      // val cells = splitTableRowNative(question, row, table.nValues)
      val cells = splitTableRow(
        question, row,
        nValues = table.nValues,
      )

      // TODO: extract date from PDF and put into this field
      // save the report name
      cells += pdfName
      // save the question num because they can have spelling
      // and formatting differences. make this 1-indexed
      cells += f"${i + 1}"

      // for (item <- cells) {
      //   println(f"Item: ${item}%s")
      // }
      rows += cells.toArray
    }

    var header: ArrayBuffer[String] = ArrayBuffer.empty

    header += "question"
    var responses: Int = ((table.nValues / 2) - 1) / 2
    println(f"Building header with ${responses} discrete options")
    for (i <- -responses to responses) {
      header += f"${i}"
      header += f"${i}_pct"
    }
    header += "report"
    header += "question_num"
    writeCSV(rows.toArray, header.toArray, outputFile)
  }
}
