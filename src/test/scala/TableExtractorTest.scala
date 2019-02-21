package org.bxroberts.tablevulture

import com.snowtide.PDF
import com.snowtide.pdf.Document

import org.scalatest.FunSuite

class TableExtractorSuite extends FunSuite {
  val testFile = "data/DEOCS.pdf"
  val pdf: Document = PDF.open(testFile)
  val q1 = new TableQuestion("""
If a coworker were to report a
sexual assault, my chain of
command/supervision would take
the report seriously.
""")
  val q2 = new TableQuestion("""
If a coworker were to report a
sexual assault, my chain of
command/supervision would keep
the knowledge of the report limited
to those with a need to know.
""")
  val te: TableExtractor = new TableExtractor(pdf)

  test("Can parse multiline questions propertly") {
    assert(q1.topText.length > 0)
    assert(q1.bottomText.length > 0)
  }

  test("Can find table row on single page") {
    val table: TableDesc = new TableDesc(
      "Table 2.13 Sexual Assault Prevention Climate",
      Array(q1)
    )
    val rows: Array[TableRow] = te.findTableRows(table)
    println(f"findTableRows rows: ${rows.length}%s")
    assert(rows.length == 1)
  }

  test("Can find multiple table row on single page") {
    val table: TableDesc = new TableDesc(
      "Table 2.13 Sexual Assault Prevention Climate",
      Array(q1, q2)
    )
    val rows: Array[TableRow] = te.findTableRows(table)
    println(f"findTableRows rows: ${rows.length}%s")
    assert(rows.length == 2)
  }

  test("Can find rows on single page in random order") {
    val table: TableDesc = new TableDesc(
      "Table 2.13 Sexual Assault Prevention Climate",
      Array(q2, q1)
    )
    val rows: Array[TableRow] = te.findTableRows(table)
    println(f"findTableRows rows: ${rows.length}%s")
    assert(rows.length == 2)
  }
}
