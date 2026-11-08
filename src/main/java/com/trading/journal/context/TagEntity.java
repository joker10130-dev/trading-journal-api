package com.trading.journal.context;

import java.time.Instant;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "tags")
@Getter
@NoArgsConstructor
public class TagEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "user_id", nullable = false)
	private Long userId;

	@Column(nullable = false, length = 64)
	private String name;

	@Column(nullable = false, length = 16)
	private String category;

	@Column(length = 8)
	private String sentiment;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	public TagEntity(Long userId, String name, String category, String sentiment) {
		this.userId = userId;
		this.name = name;
		this.category = category;
		this.sentiment = sentiment;
		this.createdAt = Instant.now();
	}

	public void update(String name, String category, String sentiment) {
		this.name = name;
		this.category = category;
		this.sentiment = sentiment;
	}
}
