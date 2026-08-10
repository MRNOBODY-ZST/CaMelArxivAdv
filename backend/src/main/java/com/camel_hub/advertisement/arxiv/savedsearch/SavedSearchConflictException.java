package com.camel_hub.advertisement.arxiv.savedsearch;

public class SavedSearchConflictException extends RuntimeException {

	public SavedSearchConflictException() {
		super("A saved search with this name already exists");
	}
}
