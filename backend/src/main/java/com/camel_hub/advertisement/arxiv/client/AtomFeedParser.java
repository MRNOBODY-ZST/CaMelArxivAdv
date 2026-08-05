package com.camel_hub.advertisement.arxiv.client;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AtomFeedParser {

	private static final String ATOM = "http://www.w3.org/2005/Atom";
	private static final String OPEN_SEARCH = "http://a9.com/-/spec/opensearch/1.1/";
	private static final String ARXIV = "http://arxiv.org/schemas/atom";
	private static final Pattern VERSION = Pattern.compile("v(\\d+)$");
	private static final Pattern ARXIV_ID = Pattern.compile("^[A-Za-z0-9./-]{1,40}$");

	public AtomFeed parse(byte[] payload) {
		if (payload == null || payload.length == 0) {
			throw new IllegalArgumentException("arXiv Atom response is empty");
		}
		try {
			XMLInputFactory factory = secureFactory();
			XMLStreamReader reader = factory.createXMLStreamReader(new ByteArrayInputStream(payload));
			try {
				return parseFeed(reader);
			}
			finally {
				reader.close();
			}
		}
		catch (XMLStreamException | DateTimeParseException exception) {
			throw new IllegalArgumentException("arXiv Atom response is invalid", exception);
		}
	}

	private XMLInputFactory secureFactory() {
		XMLInputFactory factory = XMLInputFactory.newFactory();
		factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
		factory.setProperty("javax.xml.stream.isSupportingExternalEntities", false);
		factory.setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, false);
		factory.setXMLResolver((publicId, systemId, baseUri, namespace) -> {
			throw new XMLStreamException("External XML resources are disabled");
		});
		return factory;
	}

	private AtomFeed parseFeed(XMLStreamReader reader) throws XMLStreamException {
		long total = 0;
		int start = 0;
		int pageSize = 0;
		List<ArxivPaperPreview> papers = new ArrayList<>();
		while (reader.hasNext()) {
			int event = reader.next();
			if (event == XMLStreamConstants.DTD || event == XMLStreamConstants.ENTITY_REFERENCE) {
				throw new XMLStreamException("DTD and entity references are disabled");
			}
			if (event != XMLStreamConstants.START_ELEMENT) {
				continue;
			}
			String namespace = reader.getNamespaceURI();
			String local = reader.getLocalName();
			if (OPEN_SEARCH.equals(namespace) && "totalResults".equals(local)) {
				total = parseLong(text(reader), "total results");
			}
			else if (OPEN_SEARCH.equals(namespace) && "startIndex".equals(local)) {
				start = parseInt(text(reader), "start index");
			}
			else if (OPEN_SEARCH.equals(namespace) && "itemsPerPage".equals(local)) {
				pageSize = parseInt(text(reader), "items per page");
			}
			else if (ATOM.equals(namespace) && "entry".equals(local)) {
				papers.add(parseEntry(reader));
			}
		}
		if (total < 0 || start < 0 || pageSize < 0) {
			throw new XMLStreamException("OpenSearch page values must not be negative");
		}
		return new AtomFeed(total, start, pageSize, List.copyOf(papers));
	}

	private ArxivPaperPreview parseEntry(XMLStreamReader reader) throws XMLStreamException {
		EntryBuilder entry = new EntryBuilder();
		while (reader.hasNext()) {
			int event = reader.next();
			if (event == XMLStreamConstants.END_ELEMENT
					&& ATOM.equals(reader.getNamespaceURI()) && "entry".equals(reader.getLocalName())) {
				return entry.build();
			}
			if (event == XMLStreamConstants.DTD || event == XMLStreamConstants.ENTITY_REFERENCE) {
				throw new XMLStreamException("DTD and entity references are disabled");
			}
			if (event != XMLStreamConstants.START_ELEMENT) {
				continue;
			}
			String namespace = reader.getNamespaceURI();
			String local = reader.getLocalName();
			if (ATOM.equals(namespace)) {
				switch (local) {
					case "id" -> entry.setId(text(reader));
					case "title" -> entry.title = normalize(text(reader));
					case "summary" -> entry.abstractText = normalize(text(reader));
					case "published" -> entry.publishedAt = Instant.parse(text(reader));
					case "updated" -> entry.updatedAt = Instant.parse(text(reader));
					case "author" -> entry.authors.add(parseAuthor(reader));
					case "category" -> entry.addCategory(attribute(reader, "term"));
					case "link" -> entry.addLink(
							attribute(reader, "href"), attribute(reader, "rel"),
							attribute(reader, "type"), attribute(reader, "title"));
					default -> { }
				}
			}
			else if (ARXIV.equals(namespace)) {
				switch (local) {
					case "primary_category" -> entry.primaryCategory = attribute(reader, "term");
					case "doi" -> entry.doi = normalize(text(reader));
					case "journal_ref" -> entry.journalReference = normalize(text(reader));
					case "comment" -> entry.comment = normalize(text(reader));
					default -> { }
				}
			}
		}
		throw new XMLStreamException("Atom entry did not close");
	}

	private ArxivPaperPreview.Author parseAuthor(XMLStreamReader reader) throws XMLStreamException {
		String name = null;
		List<String> affiliations = new ArrayList<>();
		while (reader.hasNext()) {
			int event = reader.next();
			if (event == XMLStreamConstants.END_ELEMENT
					&& ATOM.equals(reader.getNamespaceURI()) && "author".equals(reader.getLocalName())) {
				if (name == null || name.isBlank()) {
					throw new XMLStreamException("Atom author has no name");
				}
				return new ArxivPaperPreview.Author(name, List.copyOf(affiliations));
			}
			if (event == XMLStreamConstants.START_ELEMENT) {
				if (ATOM.equals(reader.getNamespaceURI()) && "name".equals(reader.getLocalName())) {
					name = normalize(text(reader));
				}
				else if (ARXIV.equals(reader.getNamespaceURI()) && "affiliation".equals(reader.getLocalName())) {
					affiliations.add(normalize(text(reader)));
				}
			}
		}
		throw new XMLStreamException("Atom author did not close");
	}

	private String text(XMLStreamReader reader) throws XMLStreamException {
		return reader.getElementText().strip();
	}

	private String attribute(XMLStreamReader reader, String name) {
		return reader.getAttributeValue(null, name);
	}

	private int parseInt(String value, String field) throws XMLStreamException {
		long parsed = parseLong(value, field);
		if (parsed > Integer.MAX_VALUE) {
			throw new XMLStreamException(field + " is too large");
		}
		return (int) parsed;
	}

	private long parseLong(String value, String field) throws XMLStreamException {
		try {
			return Long.parseLong(value);
		}
		catch (NumberFormatException exception) {
			throw new XMLStreamException(field + " is invalid", exception);
		}
	}

	private static String normalize(String value) {
		return value == null ? null : value.strip().replaceAll("\\s+", " ");
	}

	private static final class EntryBuilder {
		private String arxivId;
		private int versionCount = 1;
		private String title;
		private String abstractText;
		private final List<ArxivPaperPreview.Author> authors = new ArrayList<>();
		private String primaryCategory;
		private final List<String> categories = new ArrayList<>();
		private Instant publishedAt;
		private Instant updatedAt;
		private String doi;
		private String journalReference;
		private String comment;
		private String licenseUrl;
		private String pdfUrl;

		private void setId(String rawId) throws XMLStreamException {
			String path;
			try {
				path = URI.create(rawId).getPath();
			}
			catch (IllegalArgumentException exception) {
				throw new XMLStreamException("Atom arXiv ID URI is invalid", exception);
			}
			int marker = path == null ? -1 : path.indexOf("/abs/");
			String versioned = marker < 0 ? null : path.substring(marker + 5);
			if (versioned == null) {
				throw new XMLStreamException("Atom entry ID is not an arXiv abstract URI");
			}
			Matcher matcher = VERSION.matcher(versioned);
			if (matcher.find()) {
				versionCount = Integer.parseInt(matcher.group(1));
				versioned = versioned.substring(0, matcher.start());
			}
			if (!ARXIV_ID.matcher(versioned).matches()) {
				throw new XMLStreamException("Atom arXiv ID is invalid");
			}
			arxivId = versioned;
		}

		private void addCategory(String category) {
			if (category != null && !categories.contains(category)) {
				categories.add(category);
			}
		}

		private void addLink(String href, String rel, String type, String title) {
			if (href == null) {
				return;
			}
			if ("license".equals(rel)) {
				licenseUrl = href;
			}
			if ("application/pdf".equals(type) || "pdf".equalsIgnoreCase(title)) {
				pdfUrl = href;
			}
		}

		private ArxivPaperPreview build() throws XMLStreamException {
			if (arxivId == null || title == null || abstractText == null || primaryCategory == null
					|| publishedAt == null || updatedAt == null || pdfUrl == null || authors.isEmpty()) {
				throw new XMLStreamException("Atom entry is missing required paper metadata");
			}
			addCategory(primaryCategory);
			return new ArxivPaperPreview(
					arxivId, title, abstractText, List.copyOf(authors), primaryCategory,
					List.copyOf(categories), publishedAt, updatedAt, doi, journalReference,
					comment, licenseUrl, pdfUrl, versionCount);
		}
	}
}
