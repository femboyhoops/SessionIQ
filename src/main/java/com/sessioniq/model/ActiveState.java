package com.sessioniq.model;

import com.sessioniq.util.Json;

public final class ActiveState extends Json.Extensible {
	public int schema = 1;
	public String sessionId;
	public long pid;
	public long startedAt;
	public long heartbeatAt;
	public String activeVisitId;
}
