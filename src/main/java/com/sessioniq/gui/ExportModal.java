package com.sessioniq.gui;

import com.sessioniq.SessionIQ;
import com.sessioniq.export.ExportService.Format;
import net.minecraft.util.Util;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

public class ExportModal extends Modal {
	public record Option(String label, String description, List<Format> formats, Function<Format, CompletableFuture<Path>> run) {
	}

	private final SiqScreen screen;
	private final List<Option> options;
	private int option;
	private int format;
	private boolean running;
	private final SessionIQ siq = SessionIQ.get();

	public ExportModal(SiqScreen screen, List<Option> options) {
		this.screen = screen;
		this.options = options;
	}

	@Override
	public String title() {
		return "Export";
	}

	@Override
	public int preferredWidth(Ui ui) {
		return 360;
	}

	@Override
	public int bodyHeight(Ui ui, int w) {
		int h = 8;
		for (Option o : options) {
			h += 16 + ui.paragraphHeight(o.description(), w - 22) + 4;
		}
		return h + 14 + ui.lineH() + 8 + 22 + ui.lineH() + 8 + 20 + ui.lineH() * 2 + 18;
	}

	@Override
	public void renderBody(Ui ui, int x, int y, int w) {
		int cy = y + 8;
		ui.text("WHAT TO EXPORT", x, cy - 2, ui.text3);
		cy += ui.lineH() + 4;
		for (int i = 0; i < options.size(); i++) {
			Option o = options.get(i);
			int rowH = 14 + ui.paragraphHeight(o.description(), w - 22) + 4;
			String rid = "export#opt" + i;
			ui.hit(rid, x - 4, cy - 2, w + 8, rowH);
			float hv = ui.hoverAnim(rid);
			if (i == option) {
				ui.roundRect(x - 4, cy - 2, w + 8, rowH, Math.min(ui.th.radius, 3), ui.selected);
			} else if (hv > 0) {
				ui.roundRect(x - 4, cy - 2, w + 8, rowH, Math.min(ui.th.radius, 3), Ui.withAlpha(ui.hover, hv * 1.4f));
			}
			ui.dot(x + 4, cy + 4, 4, i == option ? ui.accent : ui.border);
			if (i == option) {
				ui.dot(x + 4, cy + 4, 2, ui.onAccent);
			}
			ui.text(o.label(), x + 14, cy, ui.text);
			ui.paragraph(o.description(), x + 14, cy + ui.lineH() + 3, w - 22, ui.text2);
			if (ui.clicked(rid)) {
				option = i;
				format = 0;
			}
			cy += rowH + 2;
		}
		cy += 8;
		Option o = options.get(option);
		ui.text("FORMAT", x, cy, ui.text3);
		cy += ui.lineH() + 4;
		String[] labels = o.formats().stream().map(f -> switch (f) {
			case JSON -> "JSON";
			case CSV -> "CSV";
			case TEXT -> "Plain text";
		}).toArray(String[]::new);
		int sel = ui.segmented("export#format", x, cy, labels, Math.min(format, labels.length - 1));
		if (sel >= 0) {
			format = sel;
		}
		cy += ui.buttonH() + 10;
		ui.text("DESTINATION", x, cy, ui.text3);
		cy += ui.lineH() + 4;
		Path dest = siq.exports.destination();
		int chooseW = ui.buttonWidth("Choose…", Icons.FOLDER);
		ui.textClip(dest.toString(), x, cy + 5, w - chooseW - 8, ui.text2);
		if (ui.button("export#choose", x + w - chooseW, cy, chooseW, ui.buttonH(), "Choose…", Icons.FOLDER, Ui.Btn.SECONDARY, true)) {
			FolderPicker.pick("Export destination", dest, p -> {
				siq.config.get().exportDir = p.toString();
				siq.config.changed();
			}, err -> screen.toast(err, SiqScreen.TOAST_ERROR));
		}
		cy += ui.buttonH() + 6;
		ui.paragraph("Exports are written locally. They can contain server addresses and chat; share them with care.", x, cy, w, ui.text3);
	}

	@Override
	public void renderButtons(Ui ui, int x, int y, int w, int h) {
		String label = running ? "Exporting…" : "Export";
		int bw = ui.buttonWidth(label, Icons.EXPORT);
		if (ui.button("export#go", x + w - bw, y, bw, h, label, Icons.EXPORT, Ui.Btn.PRIMARY, !running)) {
			Option o = options.get(option);
			Format f = o.formats().get(Math.min(format, o.formats().size() - 1));
			running = true;
			CompletableFuture<Path> future;
			try {
				future = o.run().apply(f);
			} catch (Throwable t) {
				future = CompletableFuture.failedFuture(t);
			}
			future.whenCompleteAsync((path, error) -> {
				running = false;
				close();
				if (error != null) {
					Throwable cause = error.getCause() != null ? error.getCause() : error;
					screen.toast("Export failed: " + cause.getMessage(), SiqScreen.TOAST_ERROR);
				} else {
					screen.toast("Exported " + path.getFileName(), SiqScreen.TOAST_SUCCESS, "Open folder",
						() -> Util.getOperatingSystem().open(path.getParent()));
				}
			}, SessionIQ.mainThread());
		}
		if (ui.button("export#cancel", x + w - bw - 66, y, 60, h, "Cancel", Ui.Btn.SECONDARY)) {
			close();
		}
	}
}
