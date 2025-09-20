// MongoDB initialization script for 100M documents
// This script runs in the MongoDB shell context and has access to built-in functions

print("Starting MongoDB initialization...");
dbAdmin = db.getSiblingDB('admin');
// Authenticate user
dbAdmin.auth({
  user: "user",
  pwd: "password",
  mechanisms: ["SCRAM-SHA-1"]
});
print("created user and auth...");
use("flink_db");
print("created db...");

dbAdmin.createUser({
  user: "flink_user",
  pwd: "flink_password",
  roles: [
    {
      role: "readWrite",
      db: "flink_db"
    }
  ]
});
print("created user...");
dbAdmin.auth({
  user: "flink_user",
  pwd: "flink_password",
  mechanisms: ["SCRAM-SHA-1"]
});
print("created auth...");
db.createCollection("orders", { capped: false });
print("created collection...");
print("Starting bulk insert of 100M documents...");
print("This process will take several minutes. Please wait...");
// Configuration for bulk operations
const BATCH_SIZE = 10000;  // Insert 10K documents per batch
const TOTAL_DOCUMENTS = 100000000;  // 100M documents
const NUM_BATCHES = TOTAL_DOCUMENTS / BATCH_SIZE;

print("Inserting " + TOTAL_DOCUMENTS.toLocaleString() + " documents in " + NUM_BATCHES + " batches of " + BATCH_SIZE);

// Create indexes before bulk insert for better performance
print("Creating indexes...");
db.orders.createIndex({ "_id": 1 });  // Primary index (usually automatic but explicit)

print("Indexes created. Starting bulk insert...");

// Bulk insert with progress tracking
let totalInserted = 0;
const startTime = new Date();

for (let batch = 0; batch < NUM_BATCHES; batch++) {
  const documents = [];
  
  // Generate batch of documents (only _id field)
  for (let i = 0; i < BATCH_SIZE; i++) {
    const docIndex = batch * BATCH_SIZE + i;
    // Just insert documents with auto-generated _id
    documents.push({});
  }
  
  // Insert batch using ordered:false for better performance
  try {
    db.orders.insertMany(documents, { ordered: false });
    totalInserted += BATCH_SIZE;
    
    // Progress update every 100 batches (1M documents)
    if ((batch + 1) % 100 === 0) {
      const currentTime = new Date();
      const elapsed = (currentTime - startTime) / 1000;
      const rate = totalInserted / elapsed;
      const remaining = (TOTAL_DOCUMENTS - totalInserted) / rate;
      
      print("Progress: " + totalInserted.toLocaleString() + " / " + TOTAL_DOCUMENTS.toLocaleString() + " documents");
      print("Rate: " + Math.round(rate).toLocaleString() + " docs/sec");
      print("Estimated remaining time: " + Math.round(remaining) + " seconds");
      print("Batch " + (batch + 1) + " / " + NUM_BATCHES + " completed");
      print("---");
    }
  } catch (error) {
    print("Error in batch " + batch + ": " + error);
    // Continue with next batch
  }
}

const endTime = new Date();
const totalTime = (endTime - startTime) / 1000;
const avgRate = TOTAL_DOCUMENTS / totalTime;

print("=== BULK INSERT COMPLETED ===");
print("Total documents inserted: " + TOTAL_DOCUMENTS.toLocaleString());
print("Total time: " + Math.round(totalTime) + " seconds (" + Math.round(totalTime/60) + " minutes)");
print("Average insertion rate: " + Math.round(avgRate).toLocaleString() + " documents/second");

// Verify the count
const actualCount = db.orders.countDocuments();
print("Verification count: " + actualCount.toLocaleString() + " documents");

// Display collection statistics
print("=== COLLECTION STATISTICS ===");
print("Orders collection: " + db.orders.countDocuments().toLocaleString() + " documents");

// Sample a few document IDs to show the format
print("=== SAMPLE DOCUMENT IDs ===");
db.orders.find().limit(5).forEach(function(doc) {
  print("Sample ID: " + doc._id + " (type: " + typeof doc._id + ")");
});

print("Database initialization completed successfully!");
print("Ready for Flink processing with optimized parallel access.");