package dev.ilyapple.ghc.mod.debug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

class EventHubTest {
	private static JsonObject event(String type, int n) {
		JsonObject event = new JsonObject();
		event.addProperty("type", type);
		event.addProperty("n", n);
		return event;
	}

	@Test
	void streamsReplaysSnapshotsAndWritesTheFile(@TempDir Path dir) throws Exception {
		EventHub hub = new EventHub();
		JsonObject state = new JsonObject();
		state.addProperty("inGarden", true);
		hub.start(0, dir, query -> CompletableFuture.completedFuture(state));
		try {
			assertTrue(hub.running());
			hub.publish(event("look", 1));
			hub.publish(event("look", 2));
			hub.publish(event("tick", 3));

			HttpURLConnection events = (HttpURLConnection) URI.create("http://127.0.0.1:" + hub.port() + "/events?recent=2").toURL().openConnection();
			events.setReadTimeout(5000);
			try (BufferedReader reader = new BufferedReader(new InputStreamReader(events.getInputStream(), StandardCharsets.UTF_8))) {
				assertEquals("hello", JsonParser.parseString(reader.readLine()).getAsJsonObject().get("type").getAsString());
				assertEquals(2, JsonParser.parseString(reader.readLine()).getAsJsonObject().get("n").getAsInt());
				assertEquals(3, JsonParser.parseString(reader.readLine()).getAsJsonObject().get("n").getAsInt());
				// Live: published after the listener connected
				for (int i = 0; i < 50 && hub.subscribers() == 0; i++) Thread.sleep(20);
				hub.publish(event("swap", 4));
				JsonObject live = JsonParser.parseString(reader.readLine()).getAsJsonObject();
				assertEquals("swap", live.get("type").getAsString());
				assertTrue(live.has("seq") && live.has("t"));
			}

			HttpURLConnection snapshot = (HttpURLConnection) URI.create("http://127.0.0.1:" + hub.port() + "/state").toURL().openConnection();
			JsonObject read = JsonParser.parseString(new String(snapshot.getInputStream().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
			assertTrue(read.get("inGarden").getAsBoolean());

			HttpURLConnection post = (HttpURLConnection) URI.create("http://127.0.0.1:" + hub.port() + "/state").toURL().openConnection();
			post.setRequestMethod("POST");
			assertEquals(405, post.getResponseCode());

			// A page on another host name (DNS rebinding) is refused
			try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), hub.port())) {
				OutputStream out = socket.getOutputStream();
				out.write("GET /state HTTP/1.1\r\nHost: evil.example:80\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
				out.flush();
				String status = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII)).readLine();
				assertTrue(status.contains("403"), status);
			}
		} finally {
			hub.stop();
		}
		assertFalse(hub.running());
		assertEquals(4, Files.readAllLines(hub.file()).size());
	}

	@Test
	void hostCheck() {
		assertTrue(EventHub.loopbackHost("127.0.0.1:47913"));
		assertTrue(EventHub.loopbackHost("localhost:47913"));
		assertTrue(EventHub.loopbackHost("[::1]:47913"));
		assertFalse(EventHub.loopbackHost("evil.example"));
		assertFalse(EventHub.loopbackHost("127.0.0.1.evil.example:47913"));
		assertFalse(EventHub.loopbackHost(null));
	}
}
