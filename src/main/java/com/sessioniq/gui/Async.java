package com.sessioniq.gui;

import com.sessioniq.SessionIQ;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

public final class Async<T> {
	private T value;
	private Throwable error;
	private boolean loading;
	private Object key;
	private int token;

	public void ensure(Object key, Supplier<CompletableFuture<T>> loader) {
		if (java.util.Objects.equals(key, this.key) && (loading || value != null || error != null)) {
			return;
		}
		reload(key, loader);
	}

	public void reload(Object key, Supplier<CompletableFuture<T>> loader) {
		this.key = key;
		this.loading = true;
		int my = ++token;
		CompletableFuture<T> f;
		try {
			f = loader.get();
		} catch (Throwable t) {
			loading = false;
			error = t;
			return;
		}
		f.whenCompleteAsync((v, e) -> {
			if (my != token) {
				return;
			}
			loading = false;
			error = e;
			if (e == null) {
				value = v;
			}
		}, SessionIQ.mainThread());
	}

	public void invalidate() {
		key = null;
		token++;
		loading = false;
	}

	public T get() {
		return value;
	}

	public boolean loading() {
		return loading;
	}

	public Throwable error() {
		return error;
	}

	public boolean hasValue() {
		return value != null;
	}

	public String errorMessage() {
		if (error == null) {
			return null;
		}
		Throwable t = error instanceof java.util.concurrent.CompletionException && error.getCause() != null ? error.getCause() : error;
		return t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
	}
}
