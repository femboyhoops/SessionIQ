package com.sessioniq.model;

public enum DisconnectCategory {
	INTENTIONAL("Intentional disconnect", false),
	KICKED("Server kick", true),
	TIMEOUT("Connection timeout", true),
	NETWORK_FAILURE("Network failure", true),
	SERVER_CLOSED("Server closed", true),
	CONNECTION_LOST("Connection lost", true),
	PROTOCOL_ERROR("Protocol error", true),
	TRANSFER("Server transfer", false),
	ABNORMAL_TERMINATION("Abnormal termination", true),
	CONNECTION_FAILED("Failed connection", true),
	CONNECTION_ABORTED("Connection cancelled", false),
	UNKNOWN("Unknown", true);

	public final String label;
	public final boolean unexpected;

	DisconnectCategory(String label, boolean unexpected) {
		this.label = label;
		this.unexpected = unexpected;
	}

	public static DisconnectCategory parse(String s) {
		if (s == null) {
			return UNKNOWN;
		}
		try {
			return valueOf(s);
		} catch (IllegalArgumentException e) {
			return UNKNOWN;
		}
	}
}
