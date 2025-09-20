package com.example.flink;
import com.example.model.Order;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.common.functions.MapFunction;
import org.apache.flink.api.common.functions.ReduceFunction;
import org.apache.flink.api.common.functions.RichMapFunction;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.configuration.MemorySize;
import org.apache.flink.connector.mongodb.source.MongoSource;
import org.apache.flink.connector.mongodb.source.enumerator.splitter.PartitionStrategy;
import org.apache.flink.connector.mongodb.source.reader.deserializer.MongoDeserializationSchema;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.datastream.SingleOutputStreamOperator;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.sink.PrintSinkFunction;
import org.apache.flink.streaming.api.windowing.assigners.TumblingProcessingTimeWindows;
import org.apache.flink.streaming.api.windowing.time.Time;
import org.bson.BsonDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

public class MongoDBFlinkProcessor {

    private static final Logger LOG = LoggerFactory.getLogger(MongoDBFlinkProcessor.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    // Counters for monitoring
    private static final AtomicLong processedCount = new AtomicLong(0);
    private static final AtomicLong startTime = new AtomicLong(System.currentTimeMillis());

    public static void main(String[] args) throws Exception {
        // Set up the streaming execution environment with optimizations for large datasets
        final StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

        // Configure for high-throughput processing
        env.setParallelism(8);  // Match the number of available task slots
        env.getConfig().setLatencyTrackingInterval(1000);  // Track latency every second

        // Enable checkpointing for fault tolerance
        env.enableCheckpointing(30000);  // Checkpoint every 30 seconds
        env.getCheckpointConfig().setMinPauseBetweenCheckpoints(5000);
        env.getCheckpointConfig().setCheckpointTimeout(600000);  // 10 minutes timeout
        env.getCheckpointConfig().setMaxConcurrentCheckpoints(1);
        env.getCheckpointConfig().setCheckpointStorage("file:///opt/flink/checkpoints");

        // MongoDB connection string
        String connectionString = "mongodb://admin:password@mongodb:27017/flink_db?authSource=admin";

        LOG.info("Starting MongoDB Flink Processor for large dataset (100M documents)");
        LOG.info("Parallelism: {}", env.getParallelism());

        // Create MongoDB source with optimizations for large datasets
        MongoSource<Order> source = MongoSource.<Order>builder()
                .setUri(connectionString)
                .setDatabase("flink_db")
                .setCollection("orders")
                // Only project the _id field since that's all we have
                .setProjectedFields("_id")
                // Use sharding-based partitioning for optimal parallel processing
                .setPartitionStrategy(PartitionStrategy.SAMPLE)
                .setDeserializationSchema(new MongoDeserializationSchema<Order>() {
                    @Override
                    public TypeInformation<Order> getProducedType() {
                        return TypeInformation.of(Order.class);
                    }

                    @Override
                    public Order deserialize(BsonDocument document) {
                        try {
                            String id = String.valueOf(document.getObjectId("_id"));
                            return new Order(id);
                        } catch (Exception e) {
                            LOG.warn("Failed to deserialize document, skipping: {}", e.getMessage());
                            return null;
                        }
                    }
                })
                .build();

        // Create data stream from MongoDB with backpressure handling
        DataStream<Order> mongoStream = env.fromSource(
                source,
                WatermarkStrategy.noWatermarks(),
                "MongoDB Large Dataset Source"
        ).setParallelism(8);  // Match partition strategy

        // Convert JSON to simple document ID strings with monitoring
        SingleOutputStreamOperator<String> documentIdStream = mongoStream
                .map(new RichMapFunction<Order, String>() {
                    private transient long localProcessedCount = 0;

                    @Override
                    public void open(Configuration parameters) throws Exception {
                        super.open(parameters);
                    }

                    @Override
                    public String map(Order jsonNode) throws Exception {
                        try {
                            // Skip empty nodes from deserialization errors
                            if (jsonNode == null) {
                                return null;
                            }

                            // Extract the _id field (could be ObjectId or other type)
                            String documentId = jsonNode.getOrderId();

                            // Progress tracking
                            localProcessedCount++;
                            long globalCount = processedCount.incrementAndGet();

                            // Log progress every 100K processed documents
                            if (globalCount % 100000 == 0) {
                                long currentTime = System.currentTimeMillis();
                                long elapsedSeconds = (currentTime - startTime.get()) / 1000;
                                long rate = elapsedSeconds > 0 ? globalCount / elapsedSeconds : 0;

                                LOG.info("Progress: {} documents processed, Rate: {} docs/sec",
                                        globalCount, rate);
                            }

                            return documentId;
                        } catch (Exception e) {
                            LOG.debug("Error extracting document ID, skipping: {}", e.getMessage());
                            return null;  // Will be filtered out
                        }
                    }
                })
                .name("Document ID Extractor (High-Throughput)")
                .filter(documentId -> documentId != null)  // Remove null IDs from mapping errors
                .setParallelism(8);

        // Example Processing 1: Count total documents processed
        SingleOutputStreamOperator<Tuple2<String, Long>> documentCount = documentIdStream
                .map(new MapFunction<String, Tuple2<String, Long>>() {
                    @Override
                    public Tuple2<String, Long> map(String documentId) throws Exception {
                        return new Tuple2<>("total_documents", 1L);
                    }
                })
                .keyBy(tuple -> tuple.f0)
                .window(TumblingProcessingTimeWindows.of(Time.minutes(1))) // 1-minute windows
                .reduce(new ReduceFunction<Tuple2<String, Long>>() {
                    @Override
                    public Tuple2<String, Long> reduce(Tuple2<String, Long> value1,
                                                       Tuple2<String, Long> value2) throws Exception {
                        return new Tuple2<>(value1.f0, value1.f1 + value2.f1);
                    }
                })
                .name("Document Count Aggregation (1-min windows)");

        // Output streams with controlled parallelism to prevent overwhelming logs
        documentCount
                .setParallelism(2)
                .addSink(new PrintSinkFunction<>("Document Count (1-min)", false));


        // Summary statistics stream (very low frequency sampling)
        documentIdStream
                .filter(documentId -> documentId.hashCode() % 100000 == 0) // Sample 1 in 100K
                .map(new MapFunction<String, String>() {
                    @Override
                    public String map(String documentId) throws Exception {
                        return String.format("Sample Document ID: %s (length: %d)",
                                documentId, documentId.length());
                    }
                })
                .setParallelism(1)
                .addSink(new PrintSinkFunction<>("Rare Samples (1 in 100K)", false));

        // Execute the Flink job
        LOG.info("Starting large-scale MongoDB Flink Processor...");
        LOG.info("Expected dataset size: 100M documents");
        LOG.info("Processing configuration: {} parallelism, checkpointing enabled", env.getParallelism());
        LOG.info("Processing only document _id fields for maximum throughput");
        LOG.info("Monitor progress in TaskManager logs and Flink Web UI at http://localhost:8081");

        env.execute("MongoDB Large Dataset Processor - ID Processing (100M documents)");
    }
}