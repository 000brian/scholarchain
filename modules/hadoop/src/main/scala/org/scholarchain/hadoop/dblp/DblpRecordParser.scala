package org.scholarchain.hadoop.dblp

import java.io.StringReader
import javax.xml.stream.{XMLInputFactory, XMLStreamConstants, XMLStreamException}

import scala.collection.mutable.ListBuffer

/** An `<author>` element: text content and the optional `orcid` attribute, both verbatim. */
final case class RawAuthor(text: String, orcid: Option[String])

/** Fields of one dblp record element, verbatim (after entity decoding), in document order. */
final case class DblpRawFields(
    element: String,
    key: Option[String],
    mdate: Option[String],
    publtype: Option[String],
    authors: List[RawAuthor],
    titles: List[String],
    years: List[String],
    booktitles: List[String],
    journals: List[String],
    ees: List[String],
    urls: List[String],
    crossrefs: List[String]
)

/** Parses one complete dblp record element with StAX.
  *
  * DTD processing and external entities are disabled (no XXE, no network access); named entities
  * must already be expanded ([[DblpEntities.expand]]). Text of nested markup inside a field (for
  * example `<i>` or `<sub>` in `<title>`) is concatenated into the field text.
  *
  * Not thread-safe: create one parser per task.
  */
final class DblpRecordParser {

  private val factory: XMLInputFactory = {
    val f = XMLInputFactory.newDefaultFactory()
    f.setProperty(XMLInputFactory.SUPPORT_DTD, false)
    f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
    f.setProperty(XMLInputFactory.IS_COALESCING, true)
    f.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, false)
    f
  }

  def parse(xml: String): Either[String, DblpRawFields] = {
    val r = factory.createXMLStreamReader(new StringReader(xml))
    try {
      var depth = 0
      var element = ""
      var key: Option[String] = None
      var mdate: Option[String] = None
      var publtype: Option[String] = None
      val authors = ListBuffer.empty[RawAuthor]
      val fields = scala.collection.mutable.Map.empty[String, ListBuffer[String]]
      var field: String = null
      var fieldOrcid: Option[String] = None
      val text = new StringBuilder
      var roots = 0
      while (r.hasNext) {
        r.next() match {
          case XMLStreamConstants.START_ELEMENT =>
            depth += 1
            val name = r.getLocalName
            if (depth == 1) {
              roots += 1
              element = name
              key = Option(r.getAttributeValue(null, "key"))
              mdate = Option(r.getAttributeValue(null, "mdate"))
              publtype = Option(r.getAttributeValue(null, "publtype"))
            } else if (depth == 2) {
              field = name
              fieldOrcid =
                if (name == "author") Option(r.getAttributeValue(null, "orcid")) else None
              text.setLength(0)
            }
          case XMLStreamConstants.CHARACTERS | XMLStreamConstants.CDATA |
              XMLStreamConstants.SPACE =>
            if (depth >= 2) text.append(r.getText)
          case XMLStreamConstants.END_ELEMENT =>
            if (depth == 2 && field != null) {
              if (field == "author") authors += RawAuthor(text.toString, fieldOrcid)
              else fields.getOrElseUpdate(field, ListBuffer.empty) += text.toString
              field = null
            }
            depth -= 1
          case XMLStreamConstants.ENTITY_REFERENCE =>
            return Left(s"unexpanded entity reference &${r.getLocalName};")
          case _ => ()
        }
      }
      if (roots != 1) Left(s"expected exactly one record element, found $roots")
      else {
        def all(n: String): List[String] = fields.get(n).map(_.toList).getOrElse(Nil)
        Right(
          DblpRawFields(
            element,
            key,
            mdate,
            publtype,
            authors.toList,
            all("title"),
            all("year"),
            all("booktitle"),
            all("journal"),
            all("ee"),
            all("url"),
            all("crossref")
          )
        )
      }
    } catch {
      case e: XMLStreamException => Left(Option(e.getMessage).getOrElse("XML stream error"))
    } finally r.close()
  }
}
