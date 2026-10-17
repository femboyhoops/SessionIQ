package com.sessioniq.storage;

import com.sessioniq.util.AtomicFiles;
import com.sessioniq.util.SiqLog;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class StorageService {
	private final ExecutorService io;
	private final ExecutorService query;
	private final Object pendingLock = new Object();
	private final Map<Path, String> pendingWrites = new LinkedHashMap<>();
	private final List<Issue> issues = new CopyOnWriteArrayList<>();
	private final AtomicInteger failedWrites = new AtomicInteger();
	private volatile Issue lastWriteError;
	private volatile boolean shutdown;

	public StorageService() {
		this.io = Executors.newSingleThreadExecutor(r -> daemon(r, "SessionIQ-IO"));
		this.query = Executors.newSingleThreadExecutor(r -> daemon(r, "SessionIQ-Query"));
	}

	private static Thread daemon(Runnable r, String name) {
		Thread t = new Thread(r, name);
		t.setDaemon(true);
		t.setPriority(Thread.NORM_PRIORITY - 1);
		return t;
	}

	public void writeAtomic(Path file, String content) {
		boolean schedule;
		synchronized (pendingLock) {
			schedule = !pendingWrites.containsKey(file);
			pendingWrites.put(file, content);
		}
		if (schedule) {
			runIo(() -> {
				String latest;
				synchronized (pendingLock) {
					latest = pendingWrites.remove(file);
				}
				if (latest != null) {
					try {
						AtomicFiles.writeString(file, latest);
					} catch (IOException e) {
						writeFailed(file, e);
					}
				}
			});
		}
	}

	public void appendLines(Path file, List<String> lines) {
		if (lines.isEmpty()) {
			return;
		}
		List<String> copy = new ArrayList<>(lines);
		runIo(() -> {
			try {
				AtomicFiles.appendLines(file, copy);
			} catch (IOException e) {
				writeFailed(file, e);
			}
		});
	}

	public void runIo(Runnable task) {
		if (shutdown) {
			task.run();
			return;
		}
		io.execute(() -> {
			try {
				task.run();
			} catch (Throwable t) {
				report("Storage task failed", t.toString());
				SiqLog.error("SessionIQ storage task failed", t);
			}
		});
	}

	public <T> CompletableFuture<T> submitIo(Callable<T> task) {
		return submit(io, task);
	}

	public <T> CompletableFuture<T> submitQuery(Callable<T> task) {
		return submit(query, task);
	}

	private <T> CompletableFuture<T> submit(ExecutorService executor, Callable<T> task) {
		CompletableFuture<T> future = new CompletableFuture<>();
		Runnable r = () -> {
			try {
				future.complete(task.call());
			} catch (Throwable t) {
				future.completeExceptionally(t);
			}
		};
		if (shutdown) {
			r.run();
		} else {
			executor.execute(r);
		}
		return future;
	}

	public boolean flush(long timeoutMs) {
		if (shutdown) {
			return true;
		}
		CompletableFuture<Void> marker = new CompletableFuture<>();
		io.execute(() -> marker.complete(null));
		try {
			marker.get(timeoutMs, TimeUnit.MILLISECONDS);
			return true;
		} catch (Exception e) {
			SiqLog.warn("SessionIQ storage flush did not finish within {} ms", timeoutMs);
			return false;
		}
	}

	public void shutdown(long timeoutMs) {
		flush(timeoutMs);
		shutdown = true;
		io.shutdown();
		query.shutdownNow();
		try {
			io.awaitTermination(timeoutMs, TimeUnit.MILLISECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private void writeFailed(Path file, IOException e) {
		failedWrites.incrementAndGet();
		String message = describe(e);
		lastWriteError = new Issue(System.currentTimeMillis(), "Could not write " + file.getFileName(), message);
		report("Could not write " + file.getFileName(), message);
		SiqLog.warn("SessionIQ could not write {}: {}", file, message);
	}

	public static String describe(IOException e) {
		String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
		if (e instanceof java.nio.file.AccessDeniedException) {
			return "Permission denied: " + msg;
		}
		if (msg.toLowerCase(java.util.Locale.ROOT).contains("space")) {
			return "Disk full: " + msg;
		}
		return e.getClass().getSimpleName() + ": " + msg;
	}

	public void report(String title, String detail) {
		issues.add(new Issue(System.currentTimeMillis(), title, detail));
		while (issues.size() > 200) {
			issues.remove(0);
		}
	}

	public List<Issue> issues() {
		return issues;
	}

	public int failedWrites() {
		return failedWrites.get();
	}

	public Issue lastWriteError() {
		return lastWriteError;
	}

	public void clearWriteError() {
		lastWriteError = null;
	}

	public record Issue(long at, String title, String detail) {
	}
}
