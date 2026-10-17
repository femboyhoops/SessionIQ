package com.sessioniq.util;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

public final class AtomicFiles {
	private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC);

	private AtomicFiles() {
	}

	public static void writeString(Path target, String content) throws IOException {
		writeBytes(target, content.getBytes(StandardCharsets.UTF_8));
	}

	public static void writeBytes(Path target, byte[] bytes) throws IOException {
		Path parent = target.toAbsolutePath().getParent();
		Files.createDirectories(parent);
		Path temp = parent.resolve(target.getFileName().toString() + ".tmp");
		try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
			java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(bytes);
			while (buffer.hasRemaining()) {
				channel.write(buffer);
			}
			channel.force(true);
		}
		move(temp, target);
	}

	public static void appendLines(Path target, Iterable<String> lines) throws IOException {
		Files.createDirectories(target.toAbsolutePath().getParent());
		StringBuilder sb = new StringBuilder();
		for (String line : lines) {
			sb.append(line).append('\n');
		}
		if (Files.exists(target) && Files.size(target) > 0) {
			try (FileChannel reader = FileChannel.open(target, StandardOpenOption.READ)) {
				java.nio.ByteBuffer last = java.nio.ByteBuffer.allocate(1);
				reader.read(last, reader.size() - 1);
				if (last.get(0) != '\n') {
					sb.insert(0, '\n');
				}
			}
		}
		byte[] bytes = sb.toString().getBytes(StandardCharsets.UTF_8);
		try (FileChannel channel = FileChannel.open(target, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
			java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(bytes);
			while (buffer.hasRemaining()) {
				channel.write(buffer);
			}
			channel.force(false);
		}
	}

	public static void move(Path source, Path target) throws IOException {
		try {
			Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	public static Path quarantine(Path file, Path quarantineDir) throws IOException {
		Files.createDirectories(quarantineDir);
		Path target = quarantineDir.resolve(STAMP.format(Instant.now()) + "-" + file.getFileName());
		Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
		return target;
	}

	public static void copyStream(java.io.InputStream in, OutputStream out) throws IOException {
		in.transferTo(out);
	}
}
