package com.sessioniq.model;

import com.sessioniq.util.Json;

import java.util.ArrayList;
import java.util.List;

public final class DisconnectRecord extends Json.Extensible {
	public String id;
	public long at;
	public String sessionId;
	public String visitId;
	public String serverKey;
	public String serverName;
	public String address;
	public String category;
	public String certainty;
	public String phase;
	public String reason;
	public String reasonKey;
	public String details;
	public List<String> evidence = new ArrayList<>();
	public boolean reconnectAttempt;
	public int reconnectAttempts;
	public Long nextConnectionAt;
	public Long timeToReconnectMs;
	public Boolean returnedToSameServer;
	public String nextVisitId;
	public boolean recovered;
	public String reportFile;

	public DisconnectCategory categoryEnum() {
		return DisconnectCategory.parse(category);
	}

	public boolean isUnexpected() {
		return categoryEnum().unexpected;
	}

	public enum Certainty {
		CONFIRMED,
		INFERRED,
		UNKNOWN;

		public static Certainty parse(String s) {
			try {
				return s == null ? UNKNOWN : valueOf(s);
			} catch (IllegalArgumentException e) {
				return UNKNOWN;
			}
		}
	}
}
