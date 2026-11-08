package com.trading.journal.context;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import com.trading.journal.trade.TradeEntity;
import com.trading.journal.trade.TradeRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class TradeContextService {

	private final TradeRepository trades;
	private final TagRepository tags;
	private final TradeTagMappingRepository mappings;
	private final TradeNoteRepository notes;
	private final TradeImageRepository images;
	private final ImageStorage storage;
	private final ApplicationEventPublisher events;

	public TradeContextService(
			TradeRepository trades,
			TagRepository tags,
			TradeTagMappingRepository mappings,
			TradeNoteRepository notes,
			TradeImageRepository images,
			ImageStorage storage,
			ApplicationEventPublisher events) {
		this.trades = trades;
		this.tags = tags;
		this.mappings = mappings;
		this.notes = notes;
		this.images = images;
		this.storage = storage;
		this.events = events;
	}

	@Transactional(readOnly = true)
	public TradeContextResponse get(Long tradeId, Long userId) {
		TradeEntity trade = requireTrade(tradeId, userId);
		List<Long> tagIds = mappings.findAllByTradeIdAndUserId(tradeId, userId).stream()
				.map(TradeTagMappingEntity::getTagId)
				.toList();
		List<TagService.TagResponse> tradeTags = tagIds.isEmpty()
				? List.of()
				: tags.findAllByIdInAndUserId(tagIds, userId).stream()
						.map(TagService.TagResponse::from)
						.sorted(java.util.Comparator.comparing(TagService.TagResponse::name))
						.toList();
		String note = notes.findByTradeIdAndUserId(tradeId, userId).map(TradeNoteEntity::getText).orElse("");
		List<ImageMetadata> tradeImages = images.findAllByTradeIdAndUserIdOrderByCreatedAt(tradeId, userId).stream()
				.map(ImageMetadata::from)
				.toList();
		return new TradeContextResponse(tradeTags, note, tradeImages);
	}

	@Transactional
	public void assignTags(Long tradeId, Long userId, List<Long> tagIds) {
		TradeEntity trade = requireTrade(tradeId, userId);
		if (tagIds != null && tagIds.stream().anyMatch(Objects::isNull)) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tag IDs must not be null.");
		}
		List<Long> selectedIds = tagIds == null ? List.of() : tagIds.stream().distinct().toList();
		List<TagEntity> ownedTags = tags.findAllByIdInAndUserId(selectedIds, userId);
		if (ownedTags.size() != selectedIds.size()) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "One or more tags were not found.");
		}
		mappings.deleteAllByTradeIdAndUserId(tradeId, userId);
		mappings.saveAll(selectedIds.stream()
				.map(tagId -> new TradeTagMappingEntity(tradeId, tagId, trade.getTradingAccountId(), userId))
				.toList());
	}

	@Transactional
	public void saveNote(Long tradeId, Long userId, String text) {
		TradeEntity trade = requireTrade(tradeId, userId);
		TradeNoteEntity note = notes.findByTradeIdAndUserId(tradeId, userId)
				.orElseGet(() -> new TradeNoteEntity(tradeId, trade.getTradingAccountId(), userId, text));
		if (note.getId() != null) note.update(text);
		notes.save(note);
	}

	@Transactional
	public void deleteNote(Long tradeId, Long userId) {
		requireTrade(tradeId, userId);
		notes.deleteByTradeIdAndUserId(tradeId, userId);
	}

	@Transactional
	public ImageMetadata addImage(Long tradeId, Long userId, byte[] content) throws IOException {
		TradeEntity trade = requireTrade(tradeId, userId);
		String contentType = detectContentType(content);
		if (contentType == null) {
			throw new ResponseStatusException(
					HttpStatus.BAD_REQUEST, "Image must be a PNG, JPEG, or WebP file.");
		}
		String key = storage.store(content);
		try {
			return ImageMetadata.from(images.saveAndFlush(new TradeImageEntity(
					tradeId, trade.getTradingAccountId(), userId, key, contentType)));
		} catch (DataAccessException exception) {
			try {
				storage.delete(key);
			} catch (IOException cleanupException) {
				exception.addSuppressed(cleanupException);
			}
			throw exception;
		}
	}

	@Transactional(readOnly = true)
	public ImageContent readImage(Long tradeId, Long imageId, Long userId) throws IOException {
		requireTrade(tradeId, userId);
		TradeImageEntity image = images.findByIdAndTradeIdAndUserId(imageId, tradeId, userId)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Trade image was not found."));
		return new ImageContent(image.getContentType(), storage.read(image.getStorageKey()));
	}

	@Transactional
	public void deleteImage(Long tradeId, Long imageId, Long userId) {
		requireTrade(tradeId, userId);
		TradeImageEntity image = images.findByIdAndTradeIdAndUserId(imageId, tradeId, userId)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Trade image was not found."));
		images.delete(image);
		events.publishEvent(new ImageKeysDeleted(List.of(image.getStorageKey())));
	}

	@Transactional
	public void purgeForHardDelete(Long tradeId, Long userId) {
		List<String> keys = images.findAllByTradeIdAndUserIdOrderByCreatedAt(tradeId, userId).stream()
				.map(TradeImageEntity::getStorageKey)
				.toList();
		mappings.deleteAllByTradeIdAndUserId(tradeId, userId);
		notes.deleteByTradeIdAndUserId(tradeId, userId);
		images.deleteAllByTradeIdAndUserId(tradeId, userId);
		if (!keys.isEmpty()) events.publishEvent(new ImageKeysDeleted(keys));
	}

	private TradeEntity requireTrade(Long tradeId, Long userId) {
		return trades.findByIdAndUserIdAndDeletedAtIsNull(tradeId, userId)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Trade was not found."));
	}

	private String detectContentType(byte[] content) {
		if (content.length >= 8
				&& content[0] == (byte) 0x89
				&& content[1] == 0x50
				&& content[2] == 0x4e
				&& content[3] == 0x47
				&& content[4] == 0x0d
				&& content[5] == 0x0a
				&& content[6] == 0x1a
				&& content[7] == 0x0a) return "image/png";
		if (content.length >= 3
				&& content[0] == (byte) 0xff
				&& content[1] == (byte) 0xd8
				&& content[2] == (byte) 0xff) return "image/jpeg";
		if (content.length >= 12
				&& content[0] == 'R'
				&& content[1] == 'I'
				&& content[2] == 'F'
				&& content[3] == 'F'
				&& content[8] == 'W'
				&& content[9] == 'E'
				&& content[10] == 'B'
				&& content[11] == 'P') return "image/webp";
		return null;
	}

	public record TradeContextResponse(
			List<TagService.TagResponse> tags,
			String note,
			List<ImageMetadata> images) {
	}

	public record ImageMetadata(Long id, String contentType, java.time.Instant createdAt) {

		private static ImageMetadata from(TradeImageEntity image) {
			return new ImageMetadata(image.getId(), image.getContentType(), image.getCreatedAt());
		}
	}

	public record ImageContent(String contentType, byte[] bytes) {
	}
}
