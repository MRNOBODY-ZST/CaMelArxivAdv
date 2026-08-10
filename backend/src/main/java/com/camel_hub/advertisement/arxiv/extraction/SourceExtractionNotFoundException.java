package com.camel_hub.advertisement.arxiv.extraction;

public class SourceExtractionNotFoundException extends RuntimeException {
	public SourceExtractionNotFoundException() {
		super("One or more papers do not exist");
	}
}
