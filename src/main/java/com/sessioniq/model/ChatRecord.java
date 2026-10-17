package com.sessioniq.model;

import com.google.gson.annotations.SerializedName;

import java.util.List;

public final class ChatRecord {
	public static final String ORIGIN_PLAYER = "player";
	public static final String ORIGIN_SYSTEM = "system";

	@SerializedName("id")
	public String id;
	@SerializedName("t")
	public long at;
	@SerializedName("s")
	public String sessionId;
	@SerializedName("v")
	public String visitId;
	@SerializedName("k")
	public String serverKey;
	@SerializedName("n")
	public String sender;
	@SerializedName("u")
	public String senderUuid;
	@SerializedName("c")
	public String category;
	@SerializedName("o")
	public String origin;
	@SerializedName("m")
	public String text;
	@SerializedName("f")
	public List<int[]> format;

	public boolean isSystem() {
		return ORIGIN_SYSTEM.equals(origin);
	}
}
