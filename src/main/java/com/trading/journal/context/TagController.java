package com.trading.journal.context;

import java.util.List;
import com.trading.journal.account.CurrentUserService;
import com.trading.journal.account.UserEntity;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/tags")
public class TagController {

	private final CurrentUserService currentUserService;
	private final TagService tagService;

	public TagController(CurrentUserService currentUserService, TagService tagService) {
		this.currentUserService = currentUserService;
		this.tagService = tagService;
	}

	@GetMapping
	public List<TagService.TagResponse> list() {
		return tagService.list(currentUserService.requireCurrentUser().getId());
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public TagService.TagResponse create(@Valid @RequestBody TagRequest request) {
		return tagService.create(currentUserService.requireCurrentUser().getId(), request.toServiceRequest());
	}

	@PutMapping("/{tagId}")
	public TagService.TagResponse update(@PathVariable Long tagId, @Valid @RequestBody TagRequest request) {
		return tagService.update(currentUserService.requireCurrentUser().getId(), tagId, request.toServiceRequest());
	}

	@DeleteMapping("/{tagId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable Long tagId) {
		tagService.delete(currentUserService.requireCurrentUser().getId(), tagId);
	}

	public record TagRequest(
			@NotBlank @Size(max = 64) String name,
			@NotBlank String category,
			@Size(max = 8) String sentiment) {

		private TagService.TagRequest toServiceRequest() {
			return new TagService.TagRequest(name, category, sentiment);
		}
	}
}
