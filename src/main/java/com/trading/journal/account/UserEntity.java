package com.trading.journal.account;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "users")
@Getter
@NoArgsConstructor
public class UserEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, unique = true, length = 320)
	private String email;

	@Column(name = "password_hash", length = 100)
	private String passwordHash;

	@Column(nullable = false, length = 64)
	private String timezone;

	public UserEntity(String email) {
		this(email, null);
	}

	public UserEntity(String email, String passwordHash) {
		this.email = email;
		this.passwordHash = passwordHash;
		this.timezone = "Asia/Bangkok";
	}

}
