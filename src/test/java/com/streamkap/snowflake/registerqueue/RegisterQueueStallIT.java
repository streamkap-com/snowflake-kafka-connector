package com.streamkap.snowflake.registerqueue;

import static org.assertj.core.api.Assertions.assertThat;

import com.snowflake.kafka.connector.SnowflakeSinkConnectorConfig;
import com.snowflake.kafka.connector.Utils;
import com.snowflake.kafka.connector.internal.streaming.DirectStreamingClientHandler;
import com.snowflake.kafka.connector.internal.streaming.StreamingClientProperties;
import java.security.KeyPairGenerator;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import net.snowflake.ingest.streaming.OpenChannelRequest;
import net.snowflake.ingest.streaming.SnowflakeStreamingIngestChannel;
import net.snowflake.ingest.streaming.SnowflakeStreamingIngestClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * STR-4969 reproduction: classic SDK clients shared by many steady channels plus bursting (hot)
 * channels. A hot channel's buffer crosses MAX_CHANNEL_SIZE_IN_BYTES (buffer.size.bytes), the
 * client flushes every channel early, and its single register thread falls behind, so steady
 * channels' commit latency grows far past max.client.lag.
 *
 * <p>Everything runs at prod / {@value #TIME_SCALE}: client lag 1s (prod 30s), register cost per
 * chunk (prod ~0.2s), and buffer sizes, so a hot channel at prod byte rate crosses the threshold
 * as often per lag window as in prod. Override with -Drq.baseMs / -Drq.perChunkMs /
 * -Drq.hotBytesPerSec.
 */
class RegisterQueueStallIT {

  static final int TIME_SCALE = 30;
  private static final long RUN_MS = 30_000;
  private static final long MEASURE_FROM_MS = 15_000;

  /** Prod buffer.size.bytes values scaled by {@link #TIME_SCALE}. */
  static final long BUF_5MB = 5_000_000L / TIME_SCALE;

  static final long BUF_64MB = 64_000_000L / TIME_SCALE;

  /** Healthy: every client's steady p99 within 3x max.client.lag (prod ~90s). */
  private static final long HEALTHY_P99_MS = 3_000;

  /** Stalled: steady p99 past 5x max.client.lag (prod ~150s). */
  private static final long STALLED_P99_MS = 5_000;

  private static final String STEADY_PAYLOAD = "{\"v\":\"" + "x".repeat(200) + "\"}";
  private static final String HOT_PAYLOAD = "{\"v\":\"" + "y".repeat(2_000) + "\"}";

  /** Prod Oct 15: 8 chunks 1.8s, 13 chunks 2.8s, 80-100 chunks 11.6-13.6s => ~0.2s + ~0.2s/chunk. */
  private static final long BASE_MS = Long.getLong("rq.baseMs", 200 / TIME_SCALE);

  private static final long PER_CHUNK_MS = Long.getLong("rq.perChunkMs", 200 / TIME_SCALE);

  /** Per hot channel; prod-rate bytes against prod/30 buffers. */
  private static final long HOT_BYTES_PER_SEC = Long.getLong("rq.hotBytesPerSec", 1_000_000);

  private static long registerLatencyMs(int chunks) {
    return BASE_MS + PER_CHUNK_MS * chunks;
  }

  enum Expect {
    HEALTHY,
    STALLED
  }

  @Test
  void baselineNoBurst() throws Exception {
    check(run(Scenario.single("baseline", 0, BUF_5MB)), Expect.HEALTHY);
  }

  @Test
  void burstSharedClientSmallBuffer() throws Exception {
    check(run(Scenario.single("burst-shared-5MB", 1, BUF_5MB)), Expect.STALLED);
  }

  @Test
  void burstSharedClientBigBuffer() throws Exception {
    check(run(Scenario.single("burst-shared-64MB", 1, BUF_64MB)), Expect.HEALTHY);
  }

  @Test
  void burstThreeClients() throws Exception {
    // "add pods": steady channels over 3 clients, the burst lands on client 0 with ~1/3 of them
    check(
        run(new Scenario("burst-3-clients-5MB", 50, 3, 1, BUF_5MB, false, null)),
        Expect.HEALTHY);
  }

  @Test
  void burstSharedClientMoreChunksPerRegister() throws Exception {
    // SDK caps a register request at 100 chunks; lifting the cap does not help a per-chunk cost
    check(
        run(
            new Scenario(
                "burst-shared-5MB-maxChunksPerRegister=1000",
                50,
                1,
                1,
                BUF_5MB,
                false,
                "max_chunks_in_registration_request:1000")),
        Expect.STALLED);
  }

  @Test
  void burstSeparateClient() throws Exception {
    check(
        run(new Scenario("burst-own-client-5MB", 50, 1, 1, BUF_5MB, true, null)),
        Expect.HEALTHY);
  }

  /**
   * ShipMonk layout: ~200 channels with data (59 / 67 / 76 tables per pod; ~240 channels per pod
   * open), one shared client per pod, all hot channels on client 0.
   */
  static Stream<Arguments> shipMonk() {
    return Stream.of(
        Arguments.of(3, 0, BUF_5MB, Expect.HEALTHY),
        Arguments.of(3, 1, BUF_5MB, Expect.STALLED),
        Arguments.of(3, 3, BUF_5MB, Expect.STALLED),
        Arguments.of(3, 3, BUF_64MB, Expect.HEALTHY),
        Arguments.of(5, 1, BUF_5MB, Expect.STALLED),
        Arguments.of(5, 3, BUF_64MB, Expect.HEALTHY));
  }

  @ParameterizedTest(name = "clients={0} hot={1} buffer={2} -> {3}")
  @MethodSource("shipMonk")
  void shipMonkLayout(int clients, int hot, long buffer, Expect expect) throws Exception {
    String name =
        String.format(
            "shipmonk-%dclients-%dhot-%dMB", clients, hot, Math.round(buffer * TIME_SCALE / 1e6));
    check(run(new Scenario(name, 200, clients, hot, buffer, false, null)), expect);
  }

  private static void check(Result r, Expect expect) {
    r.print();
    r.printPerClient();
    if (expect == Expect.HEALTHY) {
      assertThat(r.clientP99Ms).allSatisfy(p99 -> assertThat(p99).isLessThan(HEALTHY_P99_MS));
    } else {
      assertThat(r.steadyP99Ms).isGreaterThan(STALLED_P99_MS);
    }
  }

  // ---------------------------------------------------------------------------------------------

  /**
   * @param steadyChannels steady channels, spread round robin over the clients
   * @param clients clients the steady channels are spread over, like pods
   * @param hotChannels bursting channels, all on client 0 (or a dedicated client)
   * @param hotOwnClient hot channels get a dedicated client instead of client 0
   */
  record Scenario(
      String name,
      int steadyChannels,
      int clients,
      int hotChannels,
      long bufferSizeBytes,
      boolean hotOwnClient,
      String clientOverrides) {
    static Scenario single(String name, int hotChannels, long buffer) {
      return new Scenario(name, 50, 1, hotChannels, buffer, false, null);
    }
  }

  static final class Result {
    final String name;
    final long steadyP50Ms;
    final long steadyP99Ms;
    final long steadyMaxMs;
    final long steadyUncommitted;
    final long registerCalls;
    final long registeredChunks;
    final long registeredBlobs;
    final long registerBusyMs;
    final long burstRows;
    final List<String> perClient = new ArrayList<>();
    final List<Long> clientP99Ms = new ArrayList<>();

    Result(
        String name,
        List<Long> lat,
        long uncommitted,
        FakeSnowpipeStreamingServer s,
        long burstRows) {
      this.name = name;
      List<Long> sorted = new ArrayList<>(lat);
      Collections.sort(sorted);
      this.steadyP50Ms = pct(sorted, 0.50);
      this.steadyP99Ms = pct(sorted, 0.99);
      this.steadyMaxMs = sorted.isEmpty() ? -1 : sorted.get(sorted.size() - 1);
      this.steadyUncommitted = uncommitted;
      this.registerCalls = s.registerCalls.get();
      this.registeredChunks = s.registeredChunks.get();
      this.registeredBlobs = s.registeredBlobs.get();
      this.registerBusyMs = s.registerBusyMs.get();
      this.burstRows = burstRows;
    }

    private static long pct(List<Long> sorted, double p) {
      if (sorted.isEmpty()) {
        return -1;
      }
      return sorted.get(Math.min(sorted.size() - 1, (int) (p * sorted.size())));
    }

    void printPerClient() {
      perClient.forEach(l -> System.out.println("SCENARIO " + name + " | " + l));
    }

    void print() {
      System.out.printf(
          "SCENARIO %s [register=%dms+%dms/chunk hot=%dB/s] | steady commit latency p50=%dms p99=%dms max=%dms uncommittedAtEnd=%d |"
              + " register calls=%d blobs=%d chunks=%d chunks/call=%.1f busy=%.0f%% | burstRows=%d%n",
          name,
          BASE_MS,
          PER_CHUNK_MS,
          HOT_BYTES_PER_SEC,
          steadyP50Ms,
          steadyP99Ms,
          steadyMaxMs,
          steadyUncommitted,
          registerCalls,
          registeredBlobs,
          registeredChunks,
          registerCalls == 0 ? 0.0 : (double) registeredChunks / registerCalls,
          100.0 * registerBusyMs / RUN_MS,
          burstRows);
    }
  }

  private static Map<String, String> connectorConfig(
      String url, String role, long bufferBytes, String clientOverrides) throws Exception {
    KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
    gen.initialize(2048);
    String pk = Base64.getEncoder().encodeToString(gen.generateKeyPair().getPrivate().getEncoded());
    Map<String, String> c = new HashMap<>();
    c.put(Utils.NAME, "register_queue_it");
    c.put(Utils.SF_URL, url);
    c.put(Utils.SF_USER, "TEST_USER");
    c.put(Utils.SF_ROLE, role);
    c.put(Utils.SF_PRIVATE_KEY, pk);
    c.put(Utils.SF_AUTHENTICATOR, Utils.SNOWFLAKE_JWT);
    c.put(Utils.SF_DATABASE, "DB");
    c.put(Utils.SF_SCHEMA, "SC");
    c.put(SnowflakeSinkConnectorConfig.SNOWPIPE_STREAMING_MAX_CLIENT_LAG, "1");
    c.put(SnowflakeSinkConnectorConfig.SNOWPIPE_STREAMING_ENABLE_SINGLE_BUFFER, "true");
    c.put(SnowflakeSinkConnectorConfig.BUFFER_SIZE_BYTES, Long.toString(bufferBytes));
    if (clientOverrides != null) {
      c.put(SnowflakeSinkConnectorConfig.SNOWPIPE_STREAMING_CLIENT_PROVIDER_OVERRIDE_MAP, clientOverrides);
    }
    return c;
  }

  private static SnowflakeStreamingIngestChannel open(
      SnowflakeStreamingIngestClient client, String table) {
    return client.openChannel(
        OpenChannelRequest.builder(table + "_0")
            .setDBName("DB")
            .setSchemaName("SC")
            .setTableName(table)
            .setOnErrorOption(OpenChannelRequest.OnErrorOption.CONTINUE)
            .build());
  }

  private static Map<String, Object> row(String payload) {
    Map<String, Object> r = new HashMap<>();
    r.put("RECORD_METADATA", "{}");
    r.put("RECORD_CONTENT", payload);
    return r;
  }

  private static String steadyKey(int i) {
    return ("DB.SC.T" + i + ".T" + i + "_0").toUpperCase();
  }

  private Result run(Scenario sc) throws Exception {
    long t0 = System.currentTimeMillis();
    // per steady channel: offset -> insert time, drained on commit
    Map<String, ConcurrentSkipListMap<Long, Long>> pending = new ConcurrentHashMap<>();
    Map<String, List<Long>> latByChannel = new ConcurrentHashMap<>();

    FakeSnowpipeStreamingServer.CommitListener listener =
        (key, offset, now) -> {
          ConcurrentSkipListMap<Long, Long> p = pending.get(key);
          if (p == null) {
            return;
          }
          Map<Long, Long> done = p.headMap(offset, true);
          for (Long insertTs : done.values()) {
            if (insertTs - t0 >= MEASURE_FROM_MS) {
              latByChannel.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>()).add(now - insertTs);
            }
          }
          done.clear();
        };

    try (FakeSnowpipeStreamingServer server =
        new FakeSnowpipeStreamingServer(RegisterQueueStallIT::registerLatencyMs, listener)) {
      DirectStreamingClientHandler handler = new DirectStreamingClientHandler();
      // distinct role => distinct StreamingClientProperties => distinct SDK client, like one per pod
      List<SnowflakeStreamingIngestClient> clients = new ArrayList<>();
      for (int c = 0; c < sc.clients(); c++) {
        clients.add(
            handler.createClient(
                new StreamingClientProperties(
                    connectorConfig(
                        server.url(), "ROLE_" + c, sc.bufferSizeBytes(), sc.clientOverrides()))));
      }
      SnowflakeStreamingIngestClient hotClient =
          sc.hotOwnClient()
              ? handler.createClient(
                  new StreamingClientProperties(
                      connectorConfig(
                          server.url(), "ROLE_HOT", sc.bufferSizeBytes(), sc.clientOverrides())))
              : clients.get(0);

      List<SnowflakeStreamingIngestChannel> steady = new ArrayList<>();
      for (int i = 0; i < sc.steadyChannels(); i++) {
        steady.add(open(clients.get(i % clients.size()), "T" + i));
        pending.put(steadyKey(i), new ConcurrentSkipListMap<>());
      }

      AtomicBoolean running = new AtomicBoolean(true);
      AtomicLong burstRows = new AtomicLong();
      // rows per 100ms slice so each hot channel writes HOT_BYTES_PER_SEC
      int rowsPerSlice = (int) Math.max(1, HOT_BYTES_PER_SEC / 10 / HOT_PAYLOAD.length());
      List<Thread> hotThreads = new ArrayList<>();
      for (int h = 0; h < sc.hotChannels(); h++) {
        SnowflakeStreamingIngestChannel hot = open(hotClient, "HOT" + h);
        Thread t =
            new Thread(
                () -> {
                  long off = 0;
                  while (running.get()) {
                    long sliceStart = System.nanoTime();
                    for (int i = 0; i < rowsPerSlice && running.get(); i++) {
                      hot.insertRow(row(HOT_PAYLOAD), Long.toString(off++));
                    }
                    long sleepMs = 100 - (System.nanoTime() - sliceStart) / 1_000_000;
                    if (sleepMs > 0) {
                      try {
                        Thread.sleep(sleepMs);
                      } catch (InterruptedException e) {
                        break;
                      }
                    }
                  }
                  burstRows.addAndGet(off);
                },
                "hot-producer-" + h);
        t.start();
        hotThreads.add(t);
      }

      // steady: each channel one row every 50ms (20 rows/s), like put() from many tasks
      long[] offsets = new long[sc.steadyChannels()];
      while (System.currentTimeMillis() - t0 < RUN_MS) {
        long tick = System.currentTimeMillis();
        for (int i = 0; i < sc.steadyChannels(); i++) {
          long off = offsets[i]++;
          pending.get(steadyKey(i)).put(off, tick);
          steady.get(i).insertRow(row(STEADY_PAYLOAD), Long.toString(off));
        }
        long sleep = 50 - (System.currentTimeMillis() - tick);
        if (sleep > 0) {
          Thread.sleep(sleep);
        }
      }
      running.set(false);
      for (Thread t : hotThreads) {
        t.join(5_000);
      }
      // Rows inserted after MEASURE_FROM_MS that never committed during the run count with their
      // age at the end of the run, a lower bound on their real commit latency.
      long end = System.currentTimeMillis();
      List<Long> all = new ArrayList<>();
      List<List<Long>> byClient = new ArrayList<>();
      for (int c = 0; c < clients.size(); c++) {
        byClient.add(new ArrayList<>());
      }
      long uncommitted = 0;
      for (int i = 0; i < sc.steadyChannels(); i++) {
        String key = steadyKey(i);
        List<Long> mine = byClient.get(i % clients.size());
        mine.addAll(latByChannel.getOrDefault(key, Collections.emptyList()));
        for (Long ts : pending.get(key).values()) {
          if (ts - t0 >= MEASURE_FROM_MS) {
            mine.add(end - ts);
            uncommitted++;
          }
        }
      }
      byClient.forEach(all::addAll);
      Result r = new Result(sc.name(), all, uncommitted, server, burstRows.get());
      for (int c = 0; c < clients.size(); c++) {
        List<Long> mine = byClient.get(c);
        Collections.sort(mine);
        long p99 = Result.pct(mine, 0.99);
        r.clientP99Ms.add(p99);
        r.perClient.add(
            String.format(
                "client%d%s channels=%d p50=%dms p99=%dms",
                c,
                (!sc.hotOwnClient() && sc.hotChannels() > 0 && c == 0)
                    ? "(+" + sc.hotChannels() + " hot)"
                    : "",
                (sc.steadyChannels() + clients.size() - 1 - c) / clients.size(),
                Result.pct(mine, 0.5),
                p99));
      }
      for (SnowflakeStreamingIngestClient c : clients) {
        handler.closeClient(c);
      }
      if (!clients.contains(hotClient)) {
        handler.closeClient(hotClient);
      }
      return r;
    }
  }
}
