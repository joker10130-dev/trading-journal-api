package com.trading.journal.imports;

import com.trading.journal.account.CurrentUserService;
import com.trading.journal.account.UserEntity;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/trading-accounts/{accountId}/imports/batches")
public class ImportBatchController {

	private final CurrentUserService currentUserService;
	private final ImportBatchDeletionService deletionService;

	public ImportBatchController(
			CurrentUserService currentUserService,
			ImportBatchDeletionService deletionService) {
		this.currentUserService = currentUserService;
		this.deletionService = deletionService;
	}

	@DeleteMapping("/{batchId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable Long accountId, @PathVariable Long batchId) {
		UserEntity user = currentUserService.requireCurrentUser();
		deletionService.delete(accountId, batchId, user.getId());
	}

}
