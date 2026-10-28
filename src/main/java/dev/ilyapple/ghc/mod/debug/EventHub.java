package dev.ilyapple.ghc.mod.debug;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import org.jspecify.annotations.Nullable;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * The local debug stream: what the mod sees and decides, as newline-separated JSON, for following a play session
 * live from outside the game. Plain Java (no Minecraft classes), so it is tested on its own.
 *
 * <ul>
 * <li>{@code GET /events}: every event as it happens, one JSON object per line, with a {@code ping} line every
 *     15 s. {@code ?recent=N} first replays the last N events (up to {@value #RECENT}).</li>
 * <li>{@code GET /state}: a snapshot of the current state, built on the game thread. {@code ?prints=1} adds every
 *     cell's raw fingerprint.</li>
 * <li>Every event is also written to {@code events-<time>.jsonl} in the given folder, for replaying later.</li>
 * </ul>
 *
 * It only ever listens on the loopback address, only answers GET, and refuses requests whose Host header isn't a
 * loopback name, so a web page can't read it through DNS rebinding. It reads; nothing it serves changes the game.
 */
public final class EventHub {
	public static final int RECENT = 500;
	/** Listeners at once; more are turned away, so nothing on this machine can tie up the game's threads. */
	public static final int MAX_LISTENERS = 4;
	private static final int QUEUE = 4000;
	private static final String STOP = "\u0000stop";
	private static final Gson COMPACT = new GsonBuilder().disableHtmlEscaping().create();
	private static final Gson PRETTY = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();

	private final Deque<String> recent = new ArrayDeque<>();
	private final List<BlockingQueue<String>> subscribers = new CopyOnWriteArrayList<>();
	private final AtomicLong seq = new AtomicLong();
	private volatile @Nullable HttpServer server;
	private @Nullable ExecutorService executor;
	private @Nullable BufferedWriter file;
	private @Nullable Path filePath;
	private Function<Map<String, String>, CompletableFuture<JsonObject>> snapshot = query -> CompletableFuture.completedFuture(new JsonObject());

	public boolean running() {
		return server != null;
	}

	public int port() {
		HttpServer current = server;
		return current == null ? -1 : current.getAddress().getPort();
	}

	public @Nullable Path file() {
		return filePath;
	}

	public int subscribers() {
		return subscribers.size();
	}

	/**
	 * Starts listening on 127.0.0.1:{@code port} (0 picks a free port) and writing events under {@code folder}.
	 * {@code snapshot} builds the {@code /state} answer; it is called from a server thread and should hand the work
	 * to the game thread.
	 */
	public synchronized void start(int port, @Nullable Path folder, Function<Map<String, String>, CompletableFuture<JsonObject>> snapshot) throws IOException {
		if (server != null) return;
		this.snapshot = snapshot;
		HttpServer http = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
		ExecutorService pool = Executors.newCachedThreadPool(runnable -> {
			Thread thread = new Thread(runnable, "ghc-debug-stream");
			thread.setDaemon(true);
			return thread;
		});
		http.setExecutor(pool);
		http.createContext("/", this::handle);
		if (folder != null) {
			Files.createDirectories(folder);
			filePath = folder.resolve("events-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".jsonl");
			file = Files.newBufferedWriter(filePath, StandardCharsets.UTF_8);
		}
		http.start();
		executor = pool;
		server = http;
	}

	public synchronized void stop() {
		HttpServer http = server;
		if (http == null) return;
		server = null;
		for (BlockingQueue<String> queue : subscribers) queue.offer(STOP);
		http.stop(0);
		if (executor != null) executor.shutdownNow();
		executor = null;
		try {
			if (file != null) file.close();
		} catch (IOException ignored) {
			// Nothing more to write
		}
		file = null;
	}

	/** Sends an event to every listener and the file. Does nothing (cheaply) while stopped. */
	public void publish(JsonObject event) {
		if (server == null) return;
		JsonObject stamped = new JsonObject();
		stamped.addProperty("seq", seq.incrementAndGet());
		stamped.addProperty("t", System.currentTimeMillis());
		event.entrySet().forEach(entry -> stamped.add(entry.getKey(), entry.getValue()));
		String line = COMPACT.toJson(stamped);
		synchronized (this) {
			recent.addLast(line);
			while (recent.size() > RECENT) recent.removeFirst();
			if (file != null) {
				try {
					file.write(line);
					file.newLine();
					file.flush();
				} catch (IOException e) {
					file = null;
				}
			}
		}
		for (BlockingQueue<String> queue : subscribers) {
			// A listener that stops reading is dropped rather than slowing the game down
			if (!queue.offer(line)) subscribers.remove(queue);
		}
	}

	private void handle(HttpExchange exchange) throws IOException {
		try (exchange) {
			if (!"GET".equals(exchange.getRequestMethod())) {
				reply(exchange, 405, "text/plain", "Only GET\n");
				return;
			}
			if (!loopbackHost(exchange.getRequestHeaders().getFirst("Host"))) {
				reply(exchange, 403, "text/plain", "Only for localhost\n");
				return;
			}
			URI uri = exchange.getRequestURI();
			Map<String, String> query = query(uri.getRawQuery());
			switch (uri.getPath()) {
				case "/events" -> events(exchange, query);
				case "/state" -> state(exchange, query);
				case "/" -> reply(exchange, 200, "text/plain", """
						Greenhouse Companion debug stream (read-only, this machine only)
						GET /events            newline-separated JSON events as they happen (?recent=N replays the last N first)
						GET /state             the current state (?prints=1 adds every cell's fingerprint)
						""");
				default -> reply(exchange, 404, "text/plain", "Not found\n");
			}
		}
	}

	private void events(HttpExchange exchange, Map<String, String> query) throws IOException {
		if (subscribers.size() >= MAX_LISTENERS) {
			reply(exchange, 503, "text/plain", "Too many listeners (" + MAX_LISTENERS + ")\n");
			return;
		}
		BlockingQueue<String> queue = new ArrayBlockingQueue<>(QUEUE);
		List<String> replay = new ArrayList<>();
		int wanted = Math.max(0, Math.min(RECENT, parse(query.get("recent"))));
		synchronized (this) {
			List<String> all = new ArrayList<>(recent);
			replay.addAll(all.subList(Math.max(0, all.size() - wanted), all.size()));
			subscribers.add(queue);
		}
		exchange.getResponseHeaders().set("Content-Type", "application/x-ndjson; charset=utf-8");
		exchange.getResponseHeaders().set("Cache-Control", "no-cache");
		exchange.sendResponseHeaders(200, 0);
		OutputStream out = exchange.getResponseBody();
		try {
			JsonObject hello = new JsonObject();
			hello.addProperty("type", "hello");
			hello.addProperty("replayed", replay.size());
			write(out, COMPACT.toJson(hello));
			for (String line : replay) write(out, line);
			while (server != null) {
				String line = queue.poll(15, TimeUnit.SECONDS);
				if (line == null) line = "{\"type\":\"ping\",\"t\":" + System.currentTimeMillis() + "}";
				if (line.equals(STOP)) break;
				write(out, line);
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} catch (IOException e) {
			// The listener went away
		} finally {
			subscribers.remove(queue);
		}
	}

	private void state(HttpExchange exchange, Map<String, String> query) throws IOException {
		JsonObject state;
		try {
			state = snapshot.apply(query).get(3, TimeUnit.SECONDS);
		} catch (Exception e) {
			reply(exchange, 503, "text/plain", "The game didn't answer: " + e + "\n");
			return;
		}
		reply(exchange, 200, "application/json; charset=utf-8", PRETTY.toJson(state) + "\n");
	}

	private static void write(OutputStream out, String line) throws IOException {
		out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
		out.flush();
	}

	private static void reply(HttpExchange exchange, int code, String type, String body) throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", type);
		exchange.sendResponseHeaders(code, bytes.length);
		exchange.getResponseBody().write(bytes);
	}

	static boolean loopbackHost(@Nullable String host) {
		if (host == null) return false;
		String name = host.toLowerCase(Locale.ROOT);
		if (name.startsWith("[")) name = name.substring(0, Math.max(1, name.indexOf(']') + 1));
		else if (name.contains(":")) name = name.substring(0, name.indexOf(':'));
		return name.equals("127.0.0.1") || name.equals("localhost") || name.equals("[::1]");
	}

	private static Map<String, String> query(@Nullable String raw) {
		Map<String, String> query = new LinkedHashMap<>();
		if (raw == null) return query;
		for (String part : raw.split("&")) {
			int equals = part.indexOf('=');
			if (equals < 0) query.put(part, "1");
			else query.put(part.substring(0, equals), part.substring(equals + 1));
		}
		return query;
	}

	private static int parse(@Nullable String number) {
		try {
			return number == null ? 0 : Integer.parseInt(number);
		} catch (NumberFormatException e) {
			return 0;
		}
	}
}
