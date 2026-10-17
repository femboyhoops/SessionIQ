package com.sessioniq.model;

import com.sessioniq.util.Json;

public final class MarkerRecord extends Json.Extensible {
	public static final String TYPE_DEATH = "death";

	public String id;
	public long at;
	public String type;
	public String visitId;
	public String serverKey;
	public String text;
}
