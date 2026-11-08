package com.trading.journal.context;

import java.io.IOException;
import java.io.UncheckedIOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class ImageDeletionListener {

	private static final Logger logger = LoggerFactory.getLogger(ImageDeletionListener.class);

	private final ImageStorage storage;

	public ImageDeletionListener(ImageStorage storage) {
		this.storage = storage;
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void deleteStoredImages(ImageKeysDeleted event) {
		IOException failure = null;
		for (String key : event.keys()) {
			try {
				storage.delete(key);
			} catch (IOException exception) {
				logger.error("Could not remove deleted trade image {} from storage.", key, exception);
				if (failure == null) failure = exception;
				else failure.addSuppressed(exception);
			}
		}
		if (failure != null) throw new UncheckedIOException(failure);
	}
}
