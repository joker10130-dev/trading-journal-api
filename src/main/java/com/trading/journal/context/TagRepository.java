package com.trading.journal.context;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TagRepository extends JpaRepository<TagEntity, Long> {

	List<TagEntity> findAllByUserIdOrderByName(Long userId);

	List<TagEntity> findAllByIdInAndUserId(Collection<Long> ids, Long userId);

	Optional<TagEntity> findByIdAndUserId(Long id, Long userId);

	boolean existsByUserIdAndNameIgnoreCase(Long userId, String name);

	boolean existsByUserIdAndNameIgnoreCaseAndIdNot(Long userId, String name, Long id);
}
