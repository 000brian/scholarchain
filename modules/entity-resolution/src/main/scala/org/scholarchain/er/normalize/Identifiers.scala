package org.scholarchain.er.normalize

import java.net.URI
import java.util.Locale

import scala.util.Try

import org.scholarchain.core.Doi

/** arXiv identifiers (decision 9). Canonical forms: new style `YYMM.NNNNN` (4 or 5 digits after the
  * dot) and old style `archive(.SC)?/YYMMNNN` (e.g. `cs/0101001`, `math.AG/0601001`), always
  * without the version suffix (`v2`), archive lowercase, subject class uppercase.
  *
  * Sources: DataCite arXiv DOIs (`10.48550/arXiv.<id>`, canonical DOIs are lowercase) and arxiv.org
  * links (`/abs/<id>`, `/pdf/<id>[.pdf]`), with an optional `arXiv:` prefix.
  */
object ArxivId {
  private val NewStyle = """([0-9]{4}\.[0-9]{4,5})(v[0-9]+)?""".r
  private val OldStyle = """([a-z][a-z-]*)(\.[a-z]{2})?/([0-9]{7})(v[0-9]+)?""".r
  private val DoiPrefix = "10.48550/arxiv."

  /** Canonical arXiv ID of a bare identifier (`2101.00001v2`, `arXiv:cs/0101001`) or `None`. */
  def normalize(raw: String): Option[String] =
    if (raw == null) None
    else {
      val t = raw.trim
      val s = if (t.regionMatches(true, 0, "arxiv:", 0, 6)) t.substring(6) else t
      s.toLowerCase(Locale.ROOT) match {
        case NewStyle(id, _)               => Some(id)
        case OldStyle(archive, sc, num, _) =>
          Some(
            archive + Option(sc).fold("")(x => "." + x.substring(1).toUpperCase(Locale.ROOT)) +
              "/" + num
          )
        case _ => None
      }
    }

  /** From a canonical DOI: `10.48550/arxiv.2101.00001 -> 2101.00001`. */
  def fromDoi(doi: String): Option[String] =
    if (doi == null || !doi.startsWith(DoiPrefix)) None
    else normalize(doi.substring(DoiPrefix.length))

  /** From a link: arxiv.org `/abs/` or `/pdf/` URLs, or a DOI resolver URL of an arXiv DOI. */
  def fromLink(link: String): Option[String] =
    Doi.fromDblpEe(link).flatMap(fromDoi).orElse {
      parse(link).flatMap { u =>
        val host = Option(u.getHost).map(_.toLowerCase(Locale.ROOT)).getOrElse("")
        val path = Option(u.getPath).getOrElse("")
        if (host != "arxiv.org" && host != "www.arxiv.org" && host != "export.arxiv.org") None
        else {
          val rest =
            if (path.startsWith("/abs/")) Some(path.substring(5))
            else if (path.startsWith("/pdf/")) Some(path.substring(5).stripSuffix(".pdf"))
            else None
          rest.flatMap(normalize)
        }
      }
    }

  private[normalize] def parse(link: String): Option[URI] =
    if (link == null) None else Try(new URI(link.trim)).toOption.filter(_.getScheme != null)
}

/** Repository or landing-page URLs (decision 9): an exact rule over a normalized URL.
  *
  * Normal form: scheme `http` or `https` folded to `https`; host lowercased without a leading
  * `www.`; default port dropped; path kept case-sensitive with a trailing `/` removed (a bare `/`
  * becomes empty); query kept verbatim; fragment dropped. Only `http(s)` URLs with a host qualify.
  * DOI resolver links and arXiv links are not landing URLs (they are DOIs and arXiv IDs).
  */
object LandingUrl {
  def normalize(raw: String): Option[String] =
    ArxivId.parse(raw).flatMap { u =>
      val scheme = u.getScheme.toLowerCase(Locale.ROOT)
      val host0 = Option(u.getHost).map(_.toLowerCase(Locale.ROOT))
      if ((scheme != "http" && scheme != "https") || host0.forall(_.isEmpty)) None
      else {
        val host = host0.get.stripPrefix("www.")
        val port =
          if (u.getPort == -1 || u.getPort == 80 || u.getPort == 443) "" else s":${u.getPort}"
        val path = Option(u.getRawPath).getOrElse("").stripSuffix("/")
        val query = Option(u.getRawQuery).fold("")("?" + _)
        Some(s"https://$host$port$path$query")
      }
    }

  /** The landing URL of a dblp `<ee>` link, or `None` for DOI and arXiv links and non-URLs. */
  def fromLink(link: String): Option[String] =
    if (Doi.fromDblpEe(link).isDefined || ArxivId.fromLink(link).isDefined) None
    else normalize(link)
}

/** The exact work identifiers of one dblp work (decision 9). */
final case class WorkIdentifiers(doi: Option[String], arxiv: Option[String], urls: List[String])

object WorkIdentifiers {

  /** From the work's dblp DOI and `<ee>` links. arXiv: from an arXiv DOI or any arxiv.org link (the
    * first in source order). URLs: the distinct landing URLs, in source order.
    */
  def of(doi: Option[String], eeLinks: List[String]): WorkIdentifiers = {
    val arxiv =
      doi.flatMap(ArxivId.fromDoi).orElse(eeLinks.iterator.flatMap(ArxivId.fromLink).nextOption())
    WorkIdentifiers(doi, arxiv, eeLinks.flatMap(LandingUrl.fromLink).distinct)
  }
}
