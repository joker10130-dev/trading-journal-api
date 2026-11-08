package com.trading.journal.context;

import java.io.Serializable;
import java.util.Objects;

public class TradeTagMappingId implements Serializable {

	private Long tradeId;
	private Long tagId;

	public TradeTagMappingId() {
	}

	public TradeTagMappingId(Long tradeId, Long tagId) {
		this.tradeId = tradeId;
		this.tagId = tagId;
	}

	@Override
	public boolean equals(Object other) {
		if (this == other) return true;
		if (!(other instanceof TradeTagMappingId that)) return false;
		return Objects.equals(tradeId, that.tradeId) && Objects.equals(tagId, that.tagId);
	}

	@Override
	public int hashCode() {
		return Objects.hash(tradeId, tagId);
	}
}
