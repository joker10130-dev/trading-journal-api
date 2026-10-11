package com.trading.journal;

import org.springframework.boot.SpringApplication;

public class TestTradingJournalApiApplication {

	public static void main(String[] args) {
		SpringApplication.from(TradingJournalApiApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
