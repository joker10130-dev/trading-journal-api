package com.trading.journal.context;

import java.util.List;
import java.util.Locale;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class TagService {

	private final TagRepository tags;
	private final TradeTagMappingRepository mappings;

	public TagService(TagRepository tags, TradeTagMappingRepository mappings) {
		this.tags = tags;
		this.mappings = mappings;
	}

	@Transactional(readOnly = true)
	public List<TagResponse> list(Long userId) {
		return tags.findAllByUserIdOrderByName(userId).stream().map(TagResponse::from).toList();
	}

	@Transactional
	public TagResponse create(Long userId, TagRequest request) {
		NormalizedTag value = normalize(request);
		assertNameAvailable(userId, value.name(), null);
		try {
			return TagResponse.from(tags.saveAndFlush(
					new TagEntity(userId, value.name(), value.category(), value.sentiment())));
		} catch (DataIntegrityViolationException exception) {
			throw duplicateName();
		}
	}

	@Transactional
	public TagResponse update(Long userId, Long tagId, TagRequest request) {
		TagEntity tag = tags.findByIdAndUserId(tagId, userId)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tag was not found."));
		NormalizedTag value = normalize(request);
		assertNameAvailable(userId, value.name(), tagId);
		try {
			tag.update(value.name(), value.category(), value.sentiment());
			return TagResponse.from(tags.saveAndFlush(tag));
		} catch (DataIntegrityViolationException exception) {
			throw duplicateName();
		}
	}

	@Transactional
	public void delete(Long userId, Long tagId) {
		TagEntity tag = tags.findByIdAndUserId(tagId, userId)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tag was not found."));
		mappings.deleteAllByTagIdAndUserId(tag.getId(), userId);
		tags.delete(tag);
	}

	private NormalizedTag normalize(TagRequest request) {
		String name = request.name().trim();
		String category = request.category().trim().toUpperCase(Locale.ROOT);
		String sentiment = request.sentiment() == null || request.sentiment().isBlank()
				? null
				: request.sentiment().trim().toUpperCase(Locale.ROOT);
		if (!List.of("SETUP", "MISTAKE", "EMOTION", "OTHER").contains(category)) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tag category is invalid.");
		}
		if (sentiment != null && !List.of("POSITIVE", "NEGATIVE", "NEUTRAL").contains(sentiment)) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tag sentiment is invalid.");
		}
		if ("EMOTION".equals(category) && sentiment == null) {
			throw new ResponseStatusException(
					HttpStatus.BAD_REQUEST, "Emotion tags require positive, negative, or neutral sentiment.");
		}
		return new NormalizedTag(name, category, sentiment);
	}

	private void assertNameAvailable(Long userId, String name, Long exceptId) {
		boolean exists = exceptId == null
				? tags.existsByUserIdAndNameIgnoreCase(userId, name)
				: tags.existsByUserIdAndNameIgnoreCaseAndIdNot(userId, name, exceptId);
		if (exists) throw duplicateName();
	}

	private ResponseStatusException duplicateName() {
		return new ResponseStatusException(HttpStatus.CONFLICT, "A tag with this name already exists.");
	}

	private record NormalizedTag(String name, String category, String sentiment) {
	}

	public record TagRequest(String name, String category, String sentiment) {
	}

	public record TagResponse(Long id, String name, String category, String sentiment) {

		static TagResponse from(TagEntity tag) {
			return new TagResponse(tag.getId(), tag.getName(), tag.getCategory(), tag.getSentiment());
		}
	}
}
