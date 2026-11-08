package com.trading.journal.context;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class FileSystemImageStorage implements ImageStorage {

	private final Path directory;

	public FileSystemImageStorage(
			@Value("${journal.image-storage.path:${user.home}/.trading-journal/trade-images}") String directory) {
		this.directory = Path.of(directory).toAbsolutePath().normalize();
	}

	@Override
	public String store(byte[] content) throws IOException {
		Files.createDirectories(directory);
		String key = UUID.randomUUID().toString();
		Files.write(directory.resolve(key), content, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
		return key;
	}

	@Override
	public byte[] read(String key) throws IOException {
		return Files.readAllBytes(resolve(key));
	}

	@Override
	public void delete(String key) throws IOException {
		Files.deleteIfExists(resolve(key));
	}

	private Path resolve(String key) {
		if (key == null || !key.matches("[0-9a-fA-F-]{36}")) {
			throw new IllegalArgumentException("Invalid image storage key.");
		}
		Path file = directory.resolve(key).normalize();
		if (!file.getParent().equals(directory)) {
			throw new IllegalArgumentException("Invalid image storage key.");
		}
		return file;
	}
}
