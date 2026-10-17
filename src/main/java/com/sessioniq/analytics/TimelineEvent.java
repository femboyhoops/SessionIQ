package com.sessioniq.analytics;

public final class TimelineEvent {
	public enum Type {
		SESSION_START("Session started"),
		SESSION_END("Session ended"),
		SESSION_RECOVERED("Session recovered"),
		SERVER_JOIN("Joined"),
		SERVER_LEAVE("Left"),
		DISCONNECT("Disconnect"),
		CONNECTION_FAILED("Connection failed"),
		RECONNECTED("Reconnected"),
		CHAT("Chat activity"),
		DEATH("Death");

		public final String label;

		Type(String label) {
			this.label = label;
		}
	}

	public Type type;
	public long at;
	public long endAt;
	public String title;
	public String detail;
	public String sessionId;
	public String visitId;
	public String disconnectId;
	public String serverKey;
	public int severity;
	public int count;
	public int chatFrom;
	public int chatTo;
}
