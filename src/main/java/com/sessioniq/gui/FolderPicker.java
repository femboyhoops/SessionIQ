package com.sessioniq.gui;

import com.sessioniq.SessionIQ;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

public final class FolderPicker {
	private static volatile boolean open;

	private FolderPicker() {
	}

	public static void pick(String title, Path initial, Consumer<Path> onPicked, Consumer<String> onError) {
		if (open) {
			return;
		}
		open = true;
		Thread t = new Thread(() -> {
			String chosen = null;
			String error = null;
			try {
				String start = initial != null && Files.isDirectory(initial) ? initial.toAbsolutePath().toString() : null;
				chosen = TinyFileDialogs.tinyfd_selectFolderDialog(title, start);
			} catch (Throwable e) {
				error = "The system folder dialog is not available (" + e.getClass().getSimpleName() + "). Type the folder path instead.";
			} finally {
				open = false;
			}
			String c = chosen;
			String err = error;
			SessionIQ.mainThread().execute(() -> {
				if (err != null) {
					onError.accept(err);
				} else if (c != null && !c.isBlank()) {
					onPicked.accept(Path.of(c));
				}
			});
		}, "SessionIQ-FolderPicker");
		t.setDaemon(true);
		t.start();
	}
}
