package com.streamkap.snowflake.registerqueue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntToLongFunction;

/**
 * Hermetic stand-in for the Snowpipe Streaming classic REST API (ingest SDK 4.x). Uploads land on a
 * LOCAL_FS stage, register calls sleep per a chunk-count latency model, and committed offset tokens
 * are served back through channels/status.
 */
public class FakeSnowpipeStreamingServer implements AutoCloseable {

  /** Called once per channel per successful register, with the channel's committed offset. */
  public interface CommitListener {
    void onCommit(String channelKey, long offset, long commitTimeMs);
  }

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final HttpServer server;
  private final Path stageDir;
  private final IntToLongFunction registerLatencyMsByChunks;
  private final CommitListener commitListener;
  private final String encryptionKey;
  private final Map<String, String> committedOffsets = new ConcurrentHashMap<>();

  public final AtomicLong registerCalls = new AtomicLong();
  public final AtomicLong registeredBlobs = new AtomicLong();
  public final AtomicLong registeredChunks = new AtomicLong();
  public final AtomicLong registerBusyMs = new AtomicLong();
  public final AtomicLong statusCalls = new AtomicLong();

  public FakeSnowpipeStreamingServer(
      IntToLongFunction registerLatencyMsByChunks, CommitListener commitListener)
      throws IOException {
    this.registerLatencyMsByChunks = registerLatencyMsByChunks;
    this.commitListener = commitListener;
    this.stageDir = Files.createTempDirectory("fake-snowpipe-stage");
    byte[] key = new byte[32];
    new SecureRandom().nextBytes(key);
    this.encryptionKey = Base64.getEncoder().encodeToString(key);
    this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    this.server.setExecutor(Executors.newCachedThreadPool());
    this.server.createContext("/", this::handle);
    this.server.start();
  }

  /** Connector-style URL; SnowflakeURL needs a dotted host, which 127.0.0.1 satisfies. */
  public String url() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  private void handle(HttpExchange ex) throws IOException {
    String path = ex.getRequestURI().getPath();
    byte[] body = ex.getRequestBody().readAllBytes();
    JsonNode req = body.length == 0 ? MAPPER.createObjectNode() : MAPPER.readTree(body);
    ObjectNode resp;
    try {
      if (path.startsWith("/v1/streaming/client/configure")) {
        resp = configure();
      } else if (path.startsWith("/v1/streaming/channels/open")) {
        resp = openChannel(req);
      } else if (path.startsWith("/v1/streaming/channels/status")) {
        resp = channelStatus(req);
      } else if (path.startsWith("/v1/streaming/channels/write/blobs")) {
        resp = registerBlobs(req);
      } else if (path.startsWith("/v1/streaming/channels/drop")) {
        resp = ok();
      } else {
        resp = ok();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      resp = ok();
    }
    byte[] out = MAPPER.writeValueAsBytes(resp);
    ex.getResponseHeaders().add("Content-Type", "application/json");
    ex.sendResponseHeaders(200, out.length);
    try (OutputStream os = ex.getResponseBody()) {
      os.write(out);
    }
  }

  private static ObjectNode ok() {
    ObjectNode n = MAPPER.createObjectNode();
    n.put("status_code", 0);
    n.put("message", "Success");
    return n;
  }

  private ObjectNode configure() {
    ObjectNode n = ok();
    n.put("prefix", "fake");
    n.put("deployment_id", 1);
    ObjectNode stage = n.putObject("stage_location");
    stage.put("locationType", "LOCAL_FS");
    stage.put("location", stageDir.toString());
    stage.put("path", "streaming_ingest/");
    stage.putObject("creds");
    stage.put("isClientSideEncrypted", true);
    return n;
  }

  private static String channelKey(JsonNode n, String channelField) {
    return (n.path("database").asText()
            + "."
            + n.path("schema").asText()
            + "."
            + n.path("table").asText()
            + "."
            + n.path(channelField).asText())
        .toUpperCase();
  }

  private ObjectNode openChannel(JsonNode req) {
    ObjectNode n = ok();
    for (String f : new String[] {"database", "schema", "table", "channel"}) {
      n.put(f, req.path(f).asText().toUpperCase());
    }
    n.put("client_sequencer", 1);
    n.put("row_sequencer", 0);
    String committed = committedOffsets.get(channelKey(req, "channel"));
    if (committed == null) {
      n.putNull("offset_token");
    } else {
      n.put("offset_token", committed);
    }
    ArrayNode cols = n.putArray("table_columns");
    addVariantColumn(cols, "RECORD_METADATA", 1);
    addVariantColumn(cols, "RECORD_CONTENT", 2);
    n.put("encryption_key", encryptionKey);
    n.put("encryption_key_id", 1);
    return n;
  }

  private static void addVariantColumn(ArrayNode cols, String name, int ordinal) {
    ObjectNode c = cols.addObject();
    c.put("name", name);
    c.put("type", "VARIANT");
    c.put("logical_type", "VARIANT");
    c.put("physical_type", "LOB");
    c.putNull("precision");
    c.putNull("scale");
    c.put("byte_length", 16777216);
    c.put("length", 16777216);
    c.put("nullable", true);
    c.put("ordinal", ordinal);
  }

  private ObjectNode channelStatus(JsonNode req) {
    statusCalls.incrementAndGet();
    ObjectNode n = ok();
    ArrayNode out = n.putArray("channels");
    for (JsonNode ch : req.path("channels")) {
      ObjectNode s = out.addObject();
      s.put("status_code", 0);
      s.put("persisted_row_sequencer", 0);
      s.put("persisted_client_sequencer", ch.path("client_sequencer").asLong(1));
      String committed = committedOffsets.get(channelKey(ch, "channel_name"));
      if (committed == null) {
        s.putNull("persisted_offset_token");
      } else {
        s.put("persisted_offset_token", committed);
      }
    }
    return n;
  }

  private ObjectNode registerBlobs(JsonNode req) throws InterruptedException {
    long start = System.currentTimeMillis();
    int chunks = 0;
    for (JsonNode blob : req.path("blobs")) {
      chunks += blob.path("chunks").size();
    }
    Thread.sleep(registerLatencyMsByChunks.applyAsLong(chunks));

    ObjectNode n = ok();
    ArrayNode blobsOut = n.putArray("blobs");
    long now = System.currentTimeMillis();
    for (JsonNode blob : req.path("blobs")) {
      ArrayNode chunksOut = blobsOut.addObject().putArray("chunks");
      for (JsonNode chunk : blob.path("chunks")) {
        ObjectNode co = chunksOut.addObject();
        co.put("database", chunk.path("database").asText());
        co.put("schema", chunk.path("schema").asText());
        co.put("table", chunk.path("table").asText());
        ArrayNode chOut = co.putArray("channels");
        for (JsonNode ch : chunk.path("channels")) {
          ObjectNode cs = chOut.addObject();
          cs.put("status_code", 0);
          cs.put("channel", ch.path("channel_name").asText());
          cs.put("client_sequencer", ch.path("client_sequencer").asLong());
          String token =
              ch.hasNonNull("end_offset_token")
                  ? ch.path("end_offset_token").asText()
                  : ch.path("offset_token").asText(null);
          if (token != null) {
            String key =
                (chunk.path("database").asText()
                        + "."
                        + chunk.path("schema").asText()
                        + "."
                        + chunk.path("table").asText()
                        + "."
                        + ch.path("channel_name").asText())
                    .toUpperCase();
            committedOffsets.put(key, token);
            commitListener.onCommit(key, Long.parseLong(token), now);
          }
        }
      }
    }
    registerCalls.incrementAndGet();
    registeredBlobs.addAndGet(req.path("blobs").size());
    registeredChunks.addAndGet(chunks);
    registerBusyMs.addAndGet(System.currentTimeMillis() - start);
    return n;
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
