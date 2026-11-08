package com.trading.journal.context;

import java.io.IOException;
import java.util.List;
import com.trading.journal.account.CurrentUserService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/trades/{tradeId}")
public class TradeContextController {

	private static final long MAX_IMAGE_SIZE = 5 * 1024 * 1024;

	private final CurrentUserService currentUserService;
	private final TradeContextService context;

	public TradeContextController(CurrentUserService currentUserService, TradeContextService context) {
		this.currentUserService = currentUserService;
		this.context = context;
	}

	@GetMapping("/context")
	public TradeContextService.TradeContextResponse get(@PathVariable Long tradeId) {
		return context.get(tradeId, currentUserService.requireCurrentUser().getId());
	}

	@PutMapping("/tags")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void assignTags(@PathVariable Long tradeId, @Valid @RequestBody TagAssignment request) {
		context.assignTags(tradeId, currentUserService.requireCurrentUser().getId(), request.tagIds());
	}

	@PutMapping("/note")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void saveNote(@PathVariable Long tradeId, @Valid @RequestBody NoteRequest request) {
		context.saveNote(tradeId, currentUserService.requireCurrentUser().getId(), request.text());
	}

	@DeleteMapping("/note")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void deleteNote(@PathVariable Long tradeId) {
		context.deleteNote(tradeId, currentUserService.requireCurrentUser().getId());
	}

	@PostMapping(value = "/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	@ResponseStatus(HttpStatus.CREATED)
	public TradeContextService.ImageMetadata addImage(
			@PathVariable Long tradeId,
			@RequestParam("file") MultipartFile file) throws IOException {
		if (file.isEmpty() || file.getSize() > MAX_IMAGE_SIZE) {
			throw new org.springframework.web.server.ResponseStatusException(
					HttpStatus.BAD_REQUEST, "Image must be non-empty and no larger than 5 MB.");
		}
		return context.addImage(tradeId, currentUserService.requireCurrentUser().getId(), file.getBytes());
	}

	@GetMapping("/images/{imageId}")
	public ResponseEntity<byte[]> getImage(@PathVariable Long tradeId, @PathVariable Long imageId) throws IOException {
		TradeContextService.ImageContent image =
				context.readImage(tradeId, imageId, currentUserService.requireCurrentUser().getId());
		return ResponseEntity.ok()
				.contentType(MediaType.parseMediaType(image.contentType()))
				.cacheControl(CacheControl.noStore())
				.body(image.bytes());
	}

	@DeleteMapping("/images/{imageId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void deleteImage(@PathVariable Long tradeId, @PathVariable Long imageId) {
		context.deleteImage(tradeId, imageId, currentUserService.requireCurrentUser().getId());
	}

	public record TagAssignment(@NotNull List<Long> tagIds) {
	}

	public record NoteRequest(@NotNull String text) {
	}
}
