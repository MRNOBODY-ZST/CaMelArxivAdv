package com.camel_hub.advertisement.arxiv.paper;

public class PaperNotFoundException extends RuntimeException {

	public PaperNotFoundException() {
		super("Paper was not found");
	}
}
