package org.bxroberts.tablevulture

import scala.util.control.Breaks._
import collection.mutable.HashMap

import java.io.StringWriter

import com.snowtide.PDF
import com.snowtide.pdf.Document
import com.snowtide.pdf.Page
import com.snowtide.pdf.VisualOutputTarget
import com.snowtide.pdf.RegionOutputTarget

/**
 * A single point on a PDF page
 */
class Coord(_x: Int, _y: Int) {
  def x = _x
  def y = _y
  override def toString(): String = {
    return f"Coord x:${x}%d, y:${y}%d"
  }
}

/**
 * A box for a region of a PDF page.
 */
class Box(_x: Int, _y: Int, _w: Int, _h: Int) {
  def x = _x
  def y = _y
  def w = _w
  def h = _h
  override def toString(): String = {
    return f"Box x:${x}%d, y:${y}%d, w:${w}%d, h:${h}%d"
  }
}

class Size(_w: Int, _h: Int) {
  def w = _w
  def h = _h
  override def toString(): String = {
    return f"Size w:${w}%d, h:${h}%d"
  }
}

class Primitives(pdf: Document) {
  def pages: Int = pdf.getPageCnt

  /**
   * Return a Size with the page width and height
   * filled. Note that in snowtide PDF model, a
   * PDF coordinate system works like this:
   *
   * (0,792)_____ (612,792)
   *       |     |
   *       |     |
   *       |     |
   *       |_____|
   *    (0,0)    (612,0)
   */
  def pageSize(pg: Int): Size = {
    val page = pdf.getPage(pg)
    val w = page.getPageWidth
    val h = page.getPageHeight
    return new Size(w, h)
  }

  /**
   * For a given page, extract its text content using
   * the visual output target handler, which attempts
   * to use spacing to represent the visual layout
   * of the text on a page.
   */
  def extractPageText(pg: Int): String = {
    val page: Page = pdf.getPage(pg)
    val buffer = new StringWriter
    page.pipe(new VisualOutputTarget(buffer))
    return buffer.toString();
  }

  /**
   * For a given page and region on that page, extract and
   * return the text in that box.
   */
  def boxText(pg: Int, box: Box, label: String = "region"): String = {
    val tgt: RegionOutputTarget = new RegionOutputTarget()
    tgt.addRegion(box.x, box.y, box.w, box.h, label)

    val page = pdf.getPage(pg)
    page.pipe(tgt)

    val region: String = tgt.getRegionText(label)
    return region
  }

  /**
   * Starting from the top of the given page, scan down, line-by-line,
   * until the text from the current line matches some condition.
   * The condition function needs to accept a text string and return
   * false (don't stop) or true (condition met, stop scanning).
   *
   * Returns the y position of the top of the text or -1.
   */
  def yScanUntil(
    pg: Int, condition: (String) => Boolean, startY: Int = -1
  ): Int = {
    // start at Y or the top (the height) of the page, work down
    var y: Int = startY
    val size: Size = pageSize(pg)
    if (startY == -1) {
      y = size.h
    }

    // use a very thin scan line to get accurate results
    val scanLineSize = 1
    breakable {
      while (y >= 0) {
        // extract text from a thin line across the page
        val box = new Box(0, y, size.w, scanLineSize)
        val text = boxText(pg, box).replace("\n", "")
        if (condition(text)) break
        y = y - 1
      }
    }

    println(f"Scanned. Found y: ${y}%s")
    return y;
  }

  /**
   * For a give page and y position, scan the x-axis (from
   * right to left) until a condition function returns true.
   */
  def xScanUntil(
    pg: Int, condition: (String) => Boolean, y: Int, startX: Int = -1
  ): Int = {
    val scanLineSize = 1
    val size: Size = pageSize(pg)
    var x = startX;
    if (startX == -1) {
      x = size.w - 1
    }

    breakable {
      while (x >= 0) {
        val box = new Box(x, y, size.w, scanLineSize)
        val text = boxText(pg, box).replace("\n", "")
        if (condition(text)) break
        x = x - 1
      }
    }

    println(f"Scanned. Found x: ${x}%s")
    return x;
  }

  /**
   * Take our title and make some modifications
   */
  def regexify(string: String): String = {
    val replaced =  string.replaceAll(
      "\\s+", "\\\\s*"
    ).replaceAll(
      "’", "."
    )
    return f".*${replaced}%s.*"
  }

  /**
   * Find a string in another string, first using exact contains
   * match and then using a whitespace-ignoring regex match.
   */
  def exactOrRegexMatch(
    needle: String, regexNeedle: String, haystack: String
  ): Boolean = {
    if (haystack contains needle) {
      return true
    }
    if (haystack.replace("\n", "") matches regexNeedle) {
      return true
    }
    return false
  }

  /**
   * Find the coordinates of a given text on a page. It does so using
   * two methods for the x and y axes.
   *
   * 1) To find the Y (vertical position of the text) we scan down
   * from the top of the page with a very thin scanline, grabbing
   * the text we find. We mark the initial spot we find the text
   * we're looking for.
   *
   * 2) Then, once we have the Y position, we scan the X axis
   * from right to left, looking for the first instance of text
   * match and then stopping.
   *
   * This gives us propert X and Y values for our text.
   */
  def findText(
    pg: Int, title: String, startX: Int = -1, startY: Int = -1
  ): Coord = {

    val ptrn = regexify(title)
    println(f"findText: ${title}%s, pg: ${pg}%d")
    def stringFound(text: String): Boolean = {
      val m = exactOrRegexMatch(title, ptrn, text)
      println(f"finding Y: '${text}%s' contains '${title}%s'? ${m}%b")
      return m
    }

    val y = yScanUntil(pg, stringFound, startY)
    println(f"Found y: ${y}%d")

    var lastText = ""
    def stringCaptured(text: String): Boolean = {
      if (lastText.isEmpty) {
        lastText = text
        return false
      }
      val m = exactOrRegexMatch(title, ptrn, text)
      println(f"finding X: '${text}%s' contains '${title}%s'? ${m}%b")
      return m
    }

    val x = xScanUntil(pg, stringCaptured, y)
    println(f"Found x: ${x}%d")

    return new Coord(x, y)
  }

  /**
   * For a given text, find the first page it appears on.
   */
  def identifyPage(text: String): Int = {
    val ptrn = regexify(text)
    for (pg <- 1 to pages - 1) {
      // Extract page text
      val pageText = extractPageText(pg)
      println("================================================")
      println(f"page: ${pg}%d text: ${text}%s ptrn: ${ptrn}%s")
      println(f"pageText:\n${pageText}%s")
      println("------------------------------------------------")

      if (pageText contains text) {
        println("Page text exact match!")
        return pg
      }
      if (pageText.replace("\n", "") matches ptrn) {
        println("Pattern match!")
        return pg
      }
    }

    return -1
  }
}

object PDFTableVulture {
  def main(args: Array[String]) {
    println("==================================================")
    val filename = "data/DEOCS.pdf"
    val tableName = "Table 2.13 Sexual Assault Prevention Climate"
    val page = 17
    val pdf: Document = PDF.open(filename)
    val primitives = new Primitives(pdf)
    val pageText: String = primitives.extractPageText(page)
    val box = new Box(200, 200, 100, 50)
    val boxText = primitives.boxText(page, box)
    println(boxText)
    println("--------------------------------------------------")
  }
}

