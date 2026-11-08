package com.trading.journal.context;

import java.io.IOException;

public interface ImageStorage {

	String store(byte[] content) throws IOException;

	byte[] read(String key) throws IOException;

	void delete(String key) throws IOException;
}
