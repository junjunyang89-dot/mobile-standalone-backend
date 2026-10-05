package com.mobile.standalone.service.kafka;

import com.mobile.standalone.auth.CurrentUser;
import com.mobile.standalone.entity.KafkaConnection;
import com.mobile.standalone.util.RawValueText;
import com.mobile.standalone.util.TestEnvironment;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.ConsumerGroupDescription;
import org.apache.kafka.clients.admin.ConsumerGroupListing;
import org.apache.kafka.clients.admin.DescribeClusterResult;
import org.apache.kafka.clients.admin.ListConsumerGroupOffsetsSpec;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.ListTopicsOptions;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.consumer.OffsetAndTimestamp;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.TopicPartitionInfo;
import org.apache.kafka.common.config.ConfigException;
import org.apache.kafka.common.config.SaslConfigs;
import org.apache.kafka.common.errors.AuthenticationException;
import org.apache.kafka.common.errors.AuthorizationException;
import org.apache.kafka.common.errors.TimeoutException;
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * 测试工具「Kafka 查询」：看 topic、看最近的消息、看谁在消费、在测试环境发一条消息。
 * <p>
 * 最常见的用法是给「把请求体原样转发到 Kafka」这类接口找答案：body 该传什么，
 * 去 topic 里翻最近几条真实消息就知道；调完接口消息到没到，也只能在这里看——
 * 这类接口发送失败往往只打一行日志，接口照样回成功。
 * <p>
 * 几条硬规矩：
 * <ul>
 *   <li>读消息不加入任何消费组、不提交位点——查一下不能把业务服务的消费进度挪走</li>
 *   <li>生产环境的连接不能发消息，服务端直接拒</li>
 *   <li>发消息前确认 topic 存在：broker 开了自动建 topic 的话，打错一个字母就凭空多出一个 topic</li>
 *   <li>每次请求临时建连、用完即关，不常驻</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KafkaToolService {

    static final int API_TIMEOUT_MS = 10_000;
    static final int TOPIC_LIST_LIMIT = 500;
    static final int MESSAGE_LIMIT = 500;
    /** 带关键字搜时最多往回翻这么多条 */
    static final int KEYWORD_SCAN = 5000;
    static final long READ_BUDGET_MS = 6000;
    static final int VALUE_PREVIEW = 64 * 1024;
    static final int GROUP_BATCH = 100;

    private final KafkaConnectionService connections;
    private final LoggingSystem loggingSystem;

    /**
     * 每查一次都新建客户端，Kafka 客户端在 INFO 级别会把整份配置打一遍，日志很快被刷满。
     * 写在这里而不是 application.yml：那个文件不进 git，各环境的副本不一定有这条。
     */
    @PostConstruct
    void quietClientLogs() {
        loggingSystem.setLogLevel("org.apache.kafka", LogLevel.WARN);
    }

    // ==================== 连接 ====================

    public Map<String, Object> probe(KafkaConnection c) {
        long t0 = System.currentTimeMillis();
        Map<String, Object> m = new LinkedHashMap<>();
        try (Admin admin = Admin.create(clientProps(c))) {
            DescribeClusterResult r = admin.describeCluster();
            Collection<Node> nodes = r.nodes().get(API_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            Set<String> topics = admin.listTopics().names().get(API_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            m.put("ok", true);
            m.put("clusterId", r.clusterId().get(API_TIMEOUT_MS, TimeUnit.MILLISECONDS));
            m.put("brokers", nodes.size());
            m.put("topicCount", topics.size());
            m.put("message", "连上了");
        } catch (Exception e) {
            m.put("ok", false);
            m.put("message", explain(e));
        }
        m.put("elapsedMs", System.currentTimeMillis() - t0);
        return m;
    }

    public Map<String, Object> probe(String id) {
        return probe(require(id));
    }

    // ==================== 查 ====================

    public Map<String, Object> topics(String id, String keyword, boolean includeInternal) throws Exception {
        KafkaConnection c = require(id);
        try (Admin admin = Admin.create(clientProps(c))) {
            Set<String> names = admin.listTopics(new ListTopicsOptions().listInternal(includeInternal))
                    .names().get(API_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            String kw = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
            List<String> hit = names.stream()
                    .filter(n -> kw.isEmpty() || n.toLowerCase(Locale.ROOT).contains(kw))
                    .sorted()
                    .toList();
            List<String> shown = hit.size() > TOPIC_LIST_LIMIT ? hit.subList(0, TOPIC_LIST_LIMIT) : hit;
            Map<String, TopicDescription> desc = shown.isEmpty() ? Map.of()
                    : admin.describeTopics(shown).allTopicNames().get(API_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            List<Map<String, Object>> rows = new ArrayList<>();
            for (String n : shown) {
                TopicDescription d = desc.get(n);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("name", n);
                row.put("partitions", d == null ? null : d.partitions().size());
                row.put("replicas", d == null || d.partitions().isEmpty() ? null : d.partitions().get(0).replicas().size());
                row.put("internal", d != null && d.isInternal());
                rows.add(row);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("topics", rows);
            out.put("total", hit.size());
            out.put("truncated", hit.size() > shown.size());
            return out;
        }
    }

    /** 分区、各分区的起止位点，以及哪些消费组在消费它、落后多少 */
    public Map<String, Object> topicDetail(String id, String topic) throws Exception {
        KafkaConnection c = require(id);
        String t = requireTopic(topic);
        try (Admin admin = Admin.create(clientProps(c))) {
            TopicDescription d = admin.describeTopics(List.of(t)).allTopicNames()
                    .get(API_TIMEOUT_MS, TimeUnit.MILLISECONDS).get(t);
            List<TopicPartition> tps = d.partitions().stream().map(p -> new TopicPartition(t, p.partition())).toList();
            Map<TopicPartition, Long> begin = offsets(admin, tps, OffsetSpec.earliest());
            Map<TopicPartition, Long> end = offsets(admin, tps, OffsetSpec.latest());

            List<Map<String, Object>> parts = new ArrayList<>();
            long total = 0;
            for (TopicPartitionInfo p : d.partitions()) {
                TopicPartition tp = new TopicPartition(t, p.partition());
                long b = begin.getOrDefault(tp, 0L);
                long e = end.getOrDefault(tp, 0L);
                total += e - b;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("partition", p.partition());
                row.put("leader", p.leader() == null ? null : p.leader().id());
                row.put("replicas", p.replicas().stream().map(Node::id).toList());
                row.put("isr", p.isr().stream().map(Node::id).toList());
                row.put("begin", b);
                row.put("end", e);
                row.put("count", e - b);
                parts.add(row);
            }

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("topic", t);
            out.put("partitions", parts);
            out.put("messageCount", total);
            out.put("groups", groupsOf(admin, tps, end));
            return out;
        }
    }

    private static Map<TopicPartition, Long> offsets(Admin admin, List<TopicPartition> tps, OffsetSpec spec)
            throws Exception {
        Map<TopicPartition, OffsetSpec> req = new HashMap<>();
        tps.forEach(tp -> req.put(tp, spec));
        Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> r =
                admin.listOffsets(req).all().get(API_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        Map<TopicPartition, Long> out = new HashMap<>();
        r.forEach((tp, info) -> out.put(tp, info.offset()));
        return out;
    }

    /**
     * 只问「这个 topic 的分区」的已提交位点，一次带一批消费组——
     * 集群里常有几百个组，挨个问每个组的全部位点太慢。
     */
    private static List<Map<String, Object>> groupsOf(Admin admin, List<TopicPartition> tps,
                                                      Map<TopicPartition, Long> end) throws Exception {
        Collection<ConsumerGroupListing> listings = admin.listConsumerGroups().all()
                .get(API_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        List<String> ids = listings.stream().map(ConsumerGroupListing::groupId).sorted().toList();
        Map<String, Map<TopicPartition, OffsetAndMetadata>> committed = new LinkedHashMap<>();
        for (int i = 0; i < ids.size(); i += GROUP_BATCH) {
            Map<String, ListConsumerGroupOffsetsSpec> specs = new LinkedHashMap<>();
            for (String g : ids.subList(i, Math.min(i + GROUP_BATCH, ids.size()))) {
                specs.put(g, new ListConsumerGroupOffsetsSpec().topicPartitions(tps));
            }
            committed.putAll(admin.listConsumerGroupOffsets(specs).all().get(API_TIMEOUT_MS, TimeUnit.MILLISECONDS));
        }
        List<String> consuming = committed.entrySet().stream()
                .filter(e -> e.getValue() != null && e.getValue().values().stream().anyMatch(o -> o != null))
                .map(Map.Entry::getKey)
                .toList();
        if (consuming.isEmpty()) {
            return List.of();
        }
        Map<String, ConsumerGroupDescription> desc = admin.describeConsumerGroups(consuming).all()
                .get(API_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String g : consuming) {
            long lag = 0;
            List<Map<String, Object>> perPart = new ArrayList<>();
            for (TopicPartition tp : tps) {
                OffsetAndMetadata o = committed.get(g).get(tp);
                Long e = end.get(tp);
                Long l = o == null || e == null ? null : Math.max(0, e - o.offset());
                if (l != null) {
                    lag += l;
                }
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("partition", tp.partition());
                p.put("committed", o == null ? null : o.offset());
                p.put("end", e);
                p.put("lag", l);
                perPart.add(p);
            }
            ConsumerGroupDescription gd = desc.get(g);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("group", g);
            row.put("state", gd == null ? null : gd.state().toString());
            row.put("members", gd == null ? 0 : gd.members().size());
            row.put("lag", lag);
            row.put("partitions", perPart);
            rows.add(row);
        }
        rows.sort(Comparator.comparingLong((Map<String, Object> r) -> (Long) r.get("lag")).reversed());
        return rows;
    }

    /**
     * 读消息。
     *
     * @param partition 只看某个分区；null 看全部
     * @param mode      LATEST 最近 N 条 / OFFSET 从某个位点往后 / TIME 从某个时间往后
     * @param keyword   key 或 value 里包含它的才要；带关键字时最多往回翻 {@value #KEYWORD_SCAN} 条
     */
    public Map<String, Object> messages(String id, String topic, Integer partition, String mode, Long offset,
                                        Long timeMs, Integer limit, String keyword) {
        KafkaConnection c = require(id);
        String t = requireTopic(topic);
        int want = limit == null ? 50 : Math.max(1, Math.min(limit, MESSAGE_LIMIT));
        String m = mode == null ? "LATEST" : mode.trim().toUpperCase(Locale.ROOT);
        String kw = keyword == null || keyword.isBlank() ? null : keyword.trim();
        if ("OFFSET".equals(m) && offset == null) {
            throw new IllegalArgumentException("按位点查要填起始 offset");
        }
        if ("TIME".equals(m) && timeMs == null) {
            throw new IllegalArgumentException("按时间查要选起始时间");
        }

        try (KafkaConsumer<byte[], byte[]> consumer = new KafkaConsumer<>(consumerProps(c))) {
            List<PartitionInfo> infos = consumer.partitionsFor(t, Duration.ofMillis(API_TIMEOUT_MS));
            if (infos == null || infos.isEmpty()) {
                throw new IllegalArgumentException("topic 不存在：" + t);
            }
            List<TopicPartition> tps = infos.stream()
                    .filter(p -> partition == null || p.partition() == partition)
                    .map(p -> new TopicPartition(t, p.partition()))
                    .toList();
            if (tps.isEmpty()) {
                throw new IllegalArgumentException("没有分区 " + partition);
            }
            Map<TopicPartition, Long> begin = consumer.beginningOffsets(tps, Duration.ofMillis(API_TIMEOUT_MS));
            Map<TopicPartition, Long> end = consumer.endOffsets(tps, Duration.ofMillis(API_TIMEOUT_MS));
            Map<TopicPartition, Long> fromTime = "TIME".equals(m) ? offsetsForTime(consumer, tps, timeMs) : Map.of();
            int span = kw == null ? want : KEYWORD_SCAN;
            Map<TopicPartition, long[]> ranges = ranges(m, tps, begin, end, offset, fromTime, span);

            List<ConsumerRecord<byte[], byte[]>> got = read(consumer, ranges, kw);
            boolean newestFirst = "LATEST".equals(m);
            Comparator<ConsumerRecord<byte[], byte[]>> order = Comparator
                    .comparingLong((ConsumerRecord<byte[], byte[]> r) -> r.timestamp())
                    .thenComparingInt(ConsumerRecord::partition)
                    .thenComparingLong(ConsumerRecord::offset);
            got.sort(newestFirst ? order.reversed() : order);

            List<Map<String, Object>> rows = got.stream().limit(want).map(KafkaToolService::row).toList();
            long scanned = ranges.values().stream().mapToLong(r -> r[1] - r[0]).sum();
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("messages", rows);
            out.put("matched", got.size());
            out.put("scanned", scanned);
            out.put("keyword", kw);
            return out;
        }
    }

    private static Map<TopicPartition, Long> offsetsForTime(KafkaConsumer<byte[], byte[]> consumer,
                                                            List<TopicPartition> tps, long timeMs) {
        Map<TopicPartition, Long> q = new HashMap<>();
        tps.forEach(tp -> q.put(tp, timeMs));
        Map<TopicPartition, OffsetAndTimestamp> r = consumer.offsetsForTimes(q, Duration.ofMillis(API_TIMEOUT_MS));
        Map<TopicPartition, Long> out = new HashMap<>();
        r.forEach((tp, o) -> {
            if (o != null) {
                out.put(tp, o.offset());
            }
        });
        return out;
    }

    /**
     * 每个分区读 [from, to) 这一段。
     * <ul>
     *   <li>LATEST：从末尾往回 span 条——每个分区都回退 span 条，合并后按时间取最新的</li>
     *   <li>OFFSET：每个选中分区都从这个位点往后 span 条（一般配合指定分区用）</li>
     *   <li>TIME：从该时间点之后的第一条往后 span 条；那之后没消息的分区跳过</li>
     * </ul>
     */
    static Map<TopicPartition, long[]> ranges(String mode, List<TopicPartition> tps, Map<TopicPartition, Long> begin,
                                             Map<TopicPartition, Long> end, Long offset,
                                             Map<TopicPartition, Long> fromTime, int span) {
        Map<TopicPartition, long[]> out = new LinkedHashMap<>();
        for (TopicPartition tp : tps) {
            long b = begin.getOrDefault(tp, 0L);
            long e = end.getOrDefault(tp, 0L);
            long from;
            long to;
            switch (mode) {
                case "OFFSET" -> {
                    from = Math.max(b, Math.min(offset, e));
                    to = Math.min(e, from + span);
                }
                case "TIME" -> {
                    Long f = fromTime.get(tp);
                    from = f == null ? e : Math.max(b, f);
                    to = Math.min(e, from + span);
                }
                default -> {
                    to = e;
                    from = Math.max(b, e - span);
                }
            }
            if (from < to) {
                out.put(tp, new long[]{from, to});
            }
        }
        return out;
    }

    private static List<ConsumerRecord<byte[], byte[]>> read(KafkaConsumer<byte[], byte[]> consumer,
                                                            Map<TopicPartition, long[]> ranges, String keyword) {
        List<ConsumerRecord<byte[], byte[]>> got = new ArrayList<>();
        if (ranges.isEmpty()) {
            return got;
        }
        consumer.assign(ranges.keySet());
        ranges.forEach((tp, r) -> consumer.seek(tp, r[0]));
        Set<TopicPartition> pending = new HashSet<>(ranges.keySet());
        long deadline = System.currentTimeMillis() + READ_BUDGET_MS;
        while (!pending.isEmpty() && System.currentTimeMillis() < deadline) {
            for (ConsumerRecord<byte[], byte[]> rec : consumer.poll(Duration.ofMillis(500))) {
                TopicPartition tp = new TopicPartition(rec.topic(), rec.partition());
                long[] r = ranges.get(tp);
                if (r == null || rec.offset() >= r[1]) {
                    continue;
                }
                if (keyword == null || contains(rec.key(), keyword) || contains(rec.value(), keyword)) {
                    got.add(rec);
                }
            }
            for (TopicPartition tp : new ArrayList<>(pending)) {
                if (consumer.position(tp) >= ranges.get(tp)[1]) {
                    pending.remove(tp);
                    consumer.pause(List.of(tp));
                }
            }
        }
        return got;
    }

    private static boolean contains(byte[] b, String keyword) {
        return b != null && new String(b, StandardCharsets.UTF_8).contains(keyword);
    }

    private static Map<String, Object> row(ConsumerRecord<byte[], byte[]> r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("partition", r.partition());
        m.put("offset", r.offset());
        m.put("timestamp", r.timestamp());
        m.put("key", r.key() == null ? null : keyText(r.key()));
        byte[] v = r.value();
        m.put("value", v == null ? null : v.length > VALUE_PREVIEW
                ? RawValueText.of(Arrays.copyOf(v, VALUE_PREVIEW), v.length, true)
                : RawValueText.of(v));
        List<Map<String, String>> headers = new ArrayList<>();
        for (Header h : r.headers()) {
            Map<String, String> hm = new LinkedHashMap<>();
            hm.put("key", h.key());
            hm.put("value", h.value() == null ? null : keyText(h.value()));
            headers.add(hm);
        }
        m.put("headers", headers);
        return m;
    }

    private static String keyText(byte[] b) {
        RawValueText.View v = RawValueText.of(b);
        return RawValueText.TEXT.equals(v.format()) || RawValueText.JSON.equals(v.format())
                ? new String(b, StandardCharsets.UTF_8)
                : v.text();
    }

    // ==================== 发（只限测试环境） ====================

    public record HeaderKv(String key, String value) {
    }

    public Map<String, Object> produce(String id, String topic, String key, String value, Integer partition,
                                       List<HeaderKv> headers) throws Exception {
        KafkaConnection c = requireWritable(id);
        String t = requireTopic(topic);
        try (Admin admin = Admin.create(clientProps(c))) {
            Set<String> names = admin.listTopics(new ListTopicsOptions().listInternal(true)).names()
                    .get(API_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (!names.contains(t)) {
                throw new IllegalArgumentException("topic 不存在：" + t + "。这里不替你建 topic，确认一下名字");
            }
        }
        List<Header> hs = new ArrayList<>();
        if (headers != null) {
            for (HeaderKv h : headers) {
                if (h != null && h.key() != null && !h.key().isBlank()) {
                    hs.add(new RecordHeader(h.key().trim(),
                            h.value() == null ? null : h.value().getBytes(StandardCharsets.UTF_8)));
                }
            }
        }
        byte[] k = key == null || key.isEmpty() ? null : key.getBytes(StandardCharsets.UTF_8);
        byte[] v = value == null ? null : value.getBytes(StandardCharsets.UTF_8);
        RecordMetadata md;
        try (KafkaProducer<byte[], byte[]> producer = new KafkaProducer<>(producerProps(c))) {
            md = producer.send(new ProducerRecord<>(t, partition, k, v, hs)).get(API_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        }
        log.info("[kafka-tool] {} 在 {}({}) 发消息 topic={} partition={} offset={} bytes={}",
                CurrentUser.nameOr("未知"), c.getName(), c.getEnvironment(), t, md.partition(), md.offset(),
                v == null ? 0 : v.length);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("partition", md.partition());
        out.put("offset", md.offset());
        out.put("timestamp", md.timestamp());
        return out;
    }

    // ==================== 内部 ====================

    private KafkaConnection require(String id) {
        KafkaConnection c = id == null ? null : connections.get(id);
        if (c == null) {
            throw new IllegalArgumentException("连接不存在，可能被删了，刷新一下");
        }
        return c;
    }

    private KafkaConnection requireWritable(String id) {
        KafkaConnection c = require(id);
        if (!TestEnvironment.isTest(c.getEnvironment())) {
            throw new IllegalStateException("生产环境的 Kafka 只能看不能发消息");
        }
        return c;
    }

    private static String requireTopic(String topic) {
        if (topic == null || topic.isBlank()) {
            throw new IllegalArgumentException("topic 不能为空");
        }
        return topic.trim();
    }

    static Properties clientProps(KafkaConnection c) {
        Properties p = new Properties();
        p.put(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, c.getBootstrapServers());
        p.put(CommonClientConfigs.CLIENT_ID_CONFIG, "mse-kafka-tool");
        p.put(CommonClientConfigs.REQUEST_TIMEOUT_MS_CONFIG, 8000);
        p.put(CommonClientConfigs.DEFAULT_API_TIMEOUT_MS_CONFIG, API_TIMEOUT_MS);
        p.put(CommonClientConfigs.SOCKET_CONNECTION_SETUP_TIMEOUT_MS_CONFIG, 5000L);
        p.put(CommonClientConfigs.SOCKET_CONNECTION_SETUP_TIMEOUT_MAX_MS_CONFIG, 8000L);
        p.put(CommonClientConfigs.RECONNECT_BACKOFF_MAX_MS_CONFIG, 2000L);
        String proto = c.getSecurityProtocol() == null ? "PLAINTEXT" : c.getSecurityProtocol();
        p.put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, proto);
        if (proto.startsWith("SASL")) {
            String mech = c.getSaslMechanism() == null ? "PLAIN" : c.getSaslMechanism();
            p.put(SaslConfigs.SASL_MECHANISM, mech);
            p.put(SaslConfigs.SASL_JAAS_CONFIG, jaas(mech, c.getUsername(), c.getPassword()));
        }
        return p;
    }

    static String jaas(String mechanism, String username, String password) {
        String module = mechanism.startsWith("SCRAM")
                ? "org.apache.kafka.common.security.scram.ScramLoginModule"
                : "org.apache.kafka.common.security.plain.PlainLoginModule";
        return module + " required username=\"" + jaasEscape(username) + "\" password=\"" + jaasEscape(password) + "\";";
    }

    private static String jaasEscape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** 不带 group.id：只 assign + seek，不加入消费组，也就不会动任何人的消费进度 */
    static Properties consumerProps(KafkaConnection c) {
        Properties p = clientProps(c);
        p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        p.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        p.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 1000);
        p.put(ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, false);
        return p;
    }

    static Properties producerProps(KafkaConnection c) {
        Properties p = clientProps(c);
        p.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        p.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        p.put(ProducerConfig.ACKS_CONFIG, "all");
        p.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 8000L);
        p.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, API_TIMEOUT_MS);
        p.put(ProducerConfig.LINGER_MS_CONFIG, 0);
        return p;
    }

    public String explain(Exception e) {
        return explainStatic(e);
    }

    /** 报给人看的原因：Kafka 客户端的原始报错基本都是「Timed out waiting for a node assignment」，看了等于没看 */
    static String explainStatic(Throwable e) {
        Throwable t = e;
        // Future 的 ExecutionException、构造客户端失败的裸 KafkaException 都只是外壳，真正的原因在里面
        while (t.getCause() != null && t.getCause() != t
                && (t instanceof ExecutionException || t.getClass() == KafkaException.class)) {
            t = t.getCause();
        }
        if (t instanceof TimeoutException || t instanceof java.util.concurrent.TimeoutException) {
            return "连 Kafka 超时：地址不对、网络不通（内网 / 白名单），或者 broker 对外公布的地址（advertised.listeners）这台服务器解析不了";
        }
        if (t instanceof AuthenticationException) {
            return "认证失败：用户名、密码或认证方式不对（" + t.getMessage() + "）";
        }
        if (t instanceof AuthorizationException) {
            return "账号没有权限：" + t.getMessage();
        }
        if (t instanceof UnknownTopicOrPartitionException) {
            return "topic 不存在";
        }
        if (t instanceof ConfigException) {
            return "连接配置不对：" + t.getMessage();
        }
        if (t instanceof IllegalArgumentException || t instanceof IllegalStateException) {
            return t.getMessage();
        }
        String msg = t.getMessage();
        return msg == null ? t.getClass().getSimpleName() : msg;
    }
}
