package com.sessioniq.model;

import com.google.gson.annotations.SerializedName;

public final class SampleRecord {
	public static final String KIND_PING = "ping";
	public static final String KIND_MEMORY = "mem";

	@SerializedName("t")
	public long at;
	@SerializedName("k")
	public String kind;
	@SerializedName("v")
	public String visitId;
	@SerializedName("x")
	public double value;
	@SerializedName("y")
	public Double max;
}
