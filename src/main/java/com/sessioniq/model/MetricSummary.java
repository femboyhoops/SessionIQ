package com.sessioniq.model;

public final class MetricSummary {
	public int count;
	public double min;
	public double max;
	public double sum;
	public double last;

	public void add(double value) {
		if (count == 0) {
			min = value;
			max = value;
		} else {
			min = Math.min(min, value);
			max = Math.max(max, value);
		}
		count++;
		sum += value;
		last = value;
	}

	public double avg() {
		return count == 0 ? Double.NaN : sum / count;
	}

	public void merge(MetricSummary other) {
		if (other == null || other.count == 0) {
			return;
		}
		if (count == 0) {
			min = other.min;
			max = other.max;
		} else {
			min = Math.min(min, other.min);
			max = Math.max(max, other.max);
		}
		count += other.count;
		sum += other.sum;
		last = other.last;
	}
}
