#!/bin/bash

# Setup script for Large Dataset MongoDB + Flink Processing
set -e

echo "🚀 Setting up Large Dataset MongoDB + Flink Processing Environment"
echo "================================================"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Function to print colored output
print_status() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

print_warning() {
    echo -e "${YELLOW}[WARNING]${NC} $1"
}

print_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# Check prerequisites
print_status "Checking prerequisites..."

# Check Docker
if ! command -v docker &> /dev/null; then
    print_error "Docker is required but not installed. Please install Docker first."
    exit 1
fi

# Check Docker Compose
if ! command -v docker-compose &> /dev/null && ! docker compose version &> /dev/null; then
    print_error "Docker Compose is required but not installed. Please install Docker Compose first."
    exit 1
fi

# Check Maven
if ! command -v mvn &> /dev/null; then
    print_error "Maven is required but not installed. Please install Maven first."
    exit 1
fi

# Check Java
if ! command -v java &> /dev/null; then
    print_error "Java is required but not installed. Please install Java 11+ first."
    exit 1
fi

print_status "All prerequisites found ✓"

# Check available disk space (in KB)
if command -v df &> /dev/null; then
    available_space=$(df . | tail -1 | awk '{print $4}')
    required_space=5242880  # 5GB in KB (reduced since we only store _id)

    if [ "$available_space" -lt "$required_space" ]; then
        print_warning "Available disk space might be insufficient for 100M documents."
        print_warning "Available: $(($available_space / 1024 / 1024))GB, Recommended: 5GB+"
        echo -n "Continue anyway? (y/N): "
        read -r response
        if [[ ! "$response" =~ ^[Yy]$ ]]; then
            print_status "Setup cancelled."
            exit 0
        fi
    fi
fi

# Check available RAM (in MB) if free command is available
if command -v free &> /dev/null; then
    available_ram=$(free -m | awk 'NR==2{printf "%.0f", $7}')
    required_ram=8192  # 8GB

    if [ "$available_ram" -lt "$required_ram" ]; then
        print_warning "Available RAM might be insufficient for optimal performance."
        print_warning "Available: ${available_ram}MB, Recommended: 8GB+"
        echo -n "Continue anyway? (y/N): "
        read -r response
        if [[ ! "$response" =~ ^[Yy]$ ]]; then
            print_status "Setup cancelled."
            exit 0
        fi
    fi
fi

print_status "Directory structure created ✓"

# Build the Java project
print_status "Building Java project..."
cd FlinkMongoMigration
if mvn clean package -q; then
    print_status "Java project built successfully ✓"
else
    print_error "Failed to build Java project"
    exit 1
fi
cd ..
# Start Docker services
print_status "Starting Docker services (this may take a few minutes)..."
print_warning "The MongoDB initialization will insert 100M documents (only _id field)."
print_warning "This process can take 10-30 minutes depending on your system."

echo -n "Do you want to proceed with the full 100M dataset? (y/N): "
read -r response
if [[ ! "$response" =~ ^[Yy]$ ]]; then
    print_status "You can modify the TOTAL_DOCUMENTS variable in init-mongo.js to use a smaller dataset for testing."
    exit 0
fi

# Start services
print_status "Starting services..."
if command -v docker-compose &> /dev/null; then
    docker-compose up -d
else
    docker compose up -d
fi

print_status "Waiting for services to be ready..."

# Wait for MongoDB
print_status "Waiting for MongoDB to be ready..."
until docker exec mongodb mongosh --quiet --eval "print('MongoDB is ready')" > /dev/null 2>&1; do
    echo -n "."
    sleep 2
done
echo ""
print_status "MongoDB is ready ✓"

# Wait for Flink JobManager
print_status "Waiting for Flink JobManager to be ready..."
until curl -s http://localhost:8081/overview > /dev/null 2>&1; do
    echo -n "."
    sleep 2
done
echo ""
print_status "Flink JobManager is ready ✓"

# Display service information
print_status "Services started successfully!"
echo ""
echo "📊 Service URLs:"
echo "   • Flink Web UI:     http://localhost:8081"
echo "   • Mongo Express:    http://localhost:8082 (admin/admin)"
echo ""
echo "📁 Docker Services Status:"
if command -v docker-compose &> /dev/null; then
    docker-compose ps
else
    docker compose ps
fi

# Monitor MongoDB initialization
print_status "Monitoring MongoDB initialization progress..."
print_warning "This will take some time (10-30 minutes for 100M documents with only _id field)."
print_status "You can monitor progress in MongoDB logs: docker-compose logs -f mongodb"
echo ""

# Function to submit Flink job
submit_job() {
    print_status "Submitting Flink job..."
    if docker exec flink-jobmanager flink run /opt/flink/usrlib/flink-mongodb-processor-1.0-SNAPSHOT.jar; then
        print_status "Flink job submitted successfully ✓"
        echo ""
        echo "🎯 Monitor job progress:"
        echo "   • Flink Web UI: http://localhost:8081/#/job/running"
        echo "   • Task Logs: docker-compose logs -f taskmanager1 taskmanager2"
        echo ""
    else
        print_error "Failed to submit Flink job"
        return 1
    fi
}

# Ask if user wants to submit job automatically
echo -n "Do you want to automatically submit the Flink job when MongoDB initialization is complete? (y/N): "
read -r auto_submit

if [[ "$auto_submit" =~ ^[Yy]$ ]]; then
    # Wait for MongoDB initialization to complete
    print_status "Waiting for MongoDB initialization to complete..."
    while true; do
        if docker logs mongodb 2>/dev/null | grep -q "Database initialization completed successfully!"; then
            print_status "MongoDB initialization completed ✓"
            sleep 5  # Give a moment for everything to settle
            submit_job
            break
        else
            echo -n "."
            sleep 30
        fi
    done
else
    print_status "Setup completed! To submit the Flink job manually, run:"
    echo "docker exec flink-jobmanager flink run /opt/flink/usrlib/flink-mongodb-processor-1.0-SNAPSHOT.jar"
fi

print_status "Setup completed successfully! 🎉"
echo ""
echo "📋 Next Steps:"
echo "1. Wait for MongoDB initialization to complete (check logs)"
echo "2. Submit Flink job (manually or automatically)"
echo "3. Monitor processing in Flink Web UI"
echo "4. View results in TaskManager logs"
echo ""
echo "🔧 Useful Commands:"
echo "   • View MongoDB logs: docker logs mongodb"
echo "   • View Flink logs: docker logs flink-jobmanager"
echo "   • Stop services: docker-compose down"
echo "   • Clean restart: docker-compose down -v && docker-compose up -d"
echo ""