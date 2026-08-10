package com.camel_hub.advertisement.arxiv.savedsearch;

public class SavedSearchNotFoundException extends RuntimeException {

	public SavedSearchNotFoundException() {
		super("Saved search was not found");
	}
}
