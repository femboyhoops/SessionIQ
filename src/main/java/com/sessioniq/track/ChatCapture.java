package com.sessioniq.track;

import com.mojang.authlib.GameProfile;
import com.sessioniq.SessionIQ;
import com.sessioniq.config.SiqConfig;
import com.sessioniq.model.ChatCategory;
import com.sessioniq.model.ChatRecord;
import net.minecraft.client.MinecraftClient;
import net.minecraft.network.message.MessageType;
import net.minecraft.registry.RegistryKey;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.text.TextColor;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class ChatCapture {
	public static final int MAX_LENGTH = 4096;

	private final SessionIQ siq;
	private final CommandEchoGuard echoGuard = new CommandEchoGuard();

	public ChatCapture(SessionIQ siq) {
		this.siq = siq;
	}

	public void onCommandSent(String command) {
		echoGuard.onCommandSent(command);
	}

	public void onChatMessage(Text message, GameProfile sender, MessageType.Parameters params) {
		SiqConfig config = siq.config.get();
		if (!config.recordChat || !siq.tracker.isRecording()) {
			return;
		}
		Optional<RegistryKey<MessageType>> key = params.type().getKey();
		UUID self = selfUuid();
		UUID senderId = sender == null ? null : sender.id();
		boolean fromSelf = self != null && self.equals(senderId);
		ChatCategory category;
		if (key.isPresent()) {
			RegistryKey<MessageType> k = key.get();
			if (k.equals(MessageType.MSG_COMMAND_OUTGOING) || k.equals(MessageType.TEAM_MSG_COMMAND_OUTGOING)) {
				return;
			}
			if (fromSelf && !k.equals(MessageType.CHAT)) {
				return;
			}
			if (k.equals(MessageType.CHAT)) {
				category = ChatCategory.CHAT;
			} else if (k.equals(MessageType.MSG_COMMAND_INCOMING)) {
				category = ChatCategory.WHISPER;
			} else if (k.equals(MessageType.TEAM_MSG_COMMAND_INCOMING)) {
				category = ChatCategory.TEAM;
			} else if (k.equals(MessageType.EMOTE_COMMAND)) {
				category = ChatCategory.EMOTE;
			} else if (k.equals(MessageType.SAY_COMMAND)) {
				category = ChatCategory.ANNOUNCEMENT;
			} else {
				category = ChatCategory.PLAYER_OTHER;
			}
		} else {
			category = ChatCategory.PLAYER_OTHER;
		}
		ChatRecord r = base(message, config);
		if (r == null) {
			return;
		}
		r.origin = ChatRecord.ORIGIN_PLAYER;
		r.category = category.id;
		if (sender != null && sender.name() != null && !sender.name().isBlank()) {
			r.sender = sender.name();
		} else {
			String display = Sanitize.oneLine(params.name().getString(), 64);
			r.sender = display == null || display.isBlank() ? null : display;
		}
		r.senderUuid = senderId == null || senderId.getMostSignificantBits() == 0 && senderId.getLeastSignificantBits() == 0 ? null : senderId.toString();
		siq.tracker.recordChat(r);
	}

	public void onGameMessage(Text message, boolean overlay) {
		SiqConfig config = siq.config.get();
		if (overlay || !config.recordChat || !config.recordSystemMessages || !siq.tracker.isRecording()) {
			return;
		}
		ChatRecord r = base(message, config);
		if (r == null) {
			return;
		}
		r.origin = ChatRecord.ORIGIN_SYSTEM;
		r.category = ChatCategory.SYSTEM.id;
		siq.tracker.recordChat(r);
	}

	private ChatRecord base(Text message, SiqConfig config) {
		if (CommandEchoGuard.isCommandFeedback(message)) {
			return null;
		}
		StringBuilder text = new StringBuilder();
		List<int[]> runs = new ArrayList<>();
		boolean[] styled = {false};
		message.visit((style, segment) -> {
			String clean = Sanitize.clean(segment);
			int room = MAX_LENGTH - text.length();
			if (clean.isEmpty() || room <= 0) {
				return Optional.empty();
			}
			if (clean.length() > room) {
				clean = clean.substring(0, room);
			}
			text.append(clean);
			TextColor color = style.getColor();
			int rgb = color == null ? -1 : color.getRgb();
			int flags = flags(style);
			if (rgb != -1 || flags != 0) {
				styled[0] = true;
			}
			if (!runs.isEmpty() && runs.getLast()[1] == rgb && runs.getLast()[2] == flags) {
				runs.getLast()[0] += clean.length();
			} else {
				runs.add(new int[]{clean.length(), rgb, flags});
			}
			return Optional.empty();
		}, Style.EMPTY);
		if (text.toString().isBlank() || echoGuard.isEcho(text.toString())) {
			return null;
		}
		ChatRecord r = new ChatRecord();
		r.at = System.currentTimeMillis();
		r.text = text.toString();
		if (config.recordChatFormatting && styled[0]) {
			r.format = runs;
		}
		return r;
	}

	private static int flags(Style style) {
		int f = 0;
		if (style.isBold()) {
			f |= 1;
		}
		if (style.isItalic()) {
			f |= 2;
		}
		if (style.isUnderlined()) {
			f |= 4;
		}
		if (style.isStrikethrough()) {
			f |= 8;
		}
		return f;
	}

	private static UUID selfUuid() {
		MinecraftClient client = MinecraftClient.getInstance();
		return client.player != null ? client.player.getUuid() : client.getSession().getUuidOrNull();
	}
}
