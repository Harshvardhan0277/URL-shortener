# URL Shortener - Quick Start Guide

## Installation & Setup

### Prerequisites
- **Java 21+** (required for Spring Boot 3)
- **Maven 3.8+** (for building)
- **MySQL 8.0+** (for database)
- **Docker & Docker Compose** (optional, for containerized setup)

## Quick Start Options

### Option 1: Docker Compose (Recommended - Easiest)

This runs both the application and MySQL database in containers.

```bash
# From the project root directory
docker-compose up --build

# Application will be available at: http://localhost:8080
# MySQL will be available at: localhost:3306
```

The Docker Compose setup automatically:
- Builds the Spring Boot application
- Starts MySQL 8.0
- Creates the database
- Seeds the schema
- Exposes port 8080 for the web UI

**To stop the containers:**
```bash
docker-compose down
```

---

### Option 2: Local Build with Docker Database

If you want to build locally but run the database in Docker:

```bash
# Start only the MySQL container
docker-compose up -d db

# Build the application
mvn clean package

# Run the JAR file
java -jar target/url-shortener.jar

# Application will be available at: http://localhost:8080
```

---

### Option 3: Full Local Setup

For complete local development (Java app + MySQL locally):

#### 1. Install MySQL
```bash
# macOS with Homebrew
brew install mysql

# Windows: Download from https://dev.mysql.com/downloads/mysql/
# Linux: apt-get install mysql-server (Debian/Ubuntu)
```

#### 2. Start MySQL
```bash
# macOS/Linux
brew services start mysql

# Windows (if installed as service)
# Already running, or start via Services app

# Verify MySQL is running
mysql -u root -p
# Enter password: heyitsme!123 (or change in application.yml)
```

#### 3. Create Database
```bash
mysql -u root -p
# Enter password: heyitsme!123

# In MySQL console:
CREATE DATABASE url_shortener;
EXIT;
```

#### 4. Build & Run Application
```bash
# Build the project
mvn clean package

# Run the application



# Application will be available at: http://localhost:8080
```

---

## Accessing the Web UI

Once the application is running, open your browser and navigate to:

```
http://localhost:8080
```

You should see the **LinkCutter** URL Shortener interface with:
- ✅ URL shortening form
- ✅ Quick statistics display
- ✅ Analytics dashboard
- ✅ Top URLs leaderboard

## Testing the Application

### Create Your First Short URL

1. Go to http://localhost:8080
2. Enter a long URL (e.g., `https://github.com/microsoft/vscode`)
3. Click **"Shorten"**
4. Copy the generated short URL
5. Try clicking it to verify the redirect works

### View Analytics

1. Click the **"Analytics"** button or scroll down
2. Enter the short code (displayed after shortening)
3. View detailed metrics:
   - Total clicks
   - Creation date
   - Last accessed time

### Check Top URLs

1. Scroll to the **"Top URLs"** section
2. See all your short URLs ranked by click count
3. List updates every 30 seconds

## Configuration

### Database Connection

Edit `src/main/resources/application.yml` to change:

```yaml
app:
  base-url: http://localhost:8080  # Change to your domain
  
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/url_shortener
    username: root
    password: heyitsme!123
```

### Rate Limiting

Adjust rate limits (20 requests per minute per IP by default):

```yaml
app:
  rate-limit:
    capacity: 20                    # Max requests per burst
    refill-tokens: 20               # Tokens per refill
    refill-duration-seconds: 60     # Refill period
```

### Environment Variables

Override configuration via environment variables:

```bash
# Database
export DB_URL=jdbc:mysql://localhost:3306/url_shortener
export DB_USERNAME=root
export DB_PASSWORD=heyitsme!123

# Application
export APP_BASE_URL=http://localhost:8080

# Run the application
java -jar target/url-shortener.jar
```

## Development

### Building from Source

```bash
# Clone the repository
git clone <repository-url>
cd URL-shortener

# Build the project
mvn clean package

# Run tests
mvn test

# Build and skip tests
mvn clean package -DskipTests
```

### Project Structure

```
URL-shortener/
├── src/main/
│   ├── java/com/example/urlshortener/
│   │   ├── controller/          # REST API endpoints
│   │   ├── service/             # Business logic
│   │   ├── repository/          # Database access
│   │   ├── entity/              # JPA entities
│   │   ├── dto/                 # Request/response DTOs
│   │   ├── exception/           # Custom exceptions
│   │   ├── config/              # Configuration classes
│   │   └── util/                # Utility classes
│   └── resources/
│       ├── static/              # UI files (HTML, CSS, JS)
│       ├── application.yml      # Configuration
│       └── schema.sql           # Database schema
├── src/test/                    # Unit tests
├── pom.xml                      # Maven build file
├── Dockerfile                   # Application container
├── docker-compose.yml           # Multi-container orchestration
└── README.md                    # Full documentation
```

### Running Tests

```bash
# Run all tests
mvn test

# Run specific test class
mvn test -Dtest=UrlControllerTest

# Run with coverage report
mvn test jacoco:report
# Report available at: target/site/jacoco/index.html
```

## API Endpoints

### Create Short URL
```bash
curl -X POST http://localhost:8080/api/urls \
  -H "Content-Type: application/json" \
  -d '{"originalUrl": "https://github.com"}'
```

### Get URL Analytics
```bash
curl http://localhost:8080/api/analytics/abc123
```

### Get Top URLs
```bash
curl http://localhost:8080/api/analytics/top?limit=10
```

### Redirect to Original URL
```bash
curl -L http://localhost:8080/r/abc123
```

## Troubleshooting

### Port 8080 Already in Use
```bash
# Change the port in application.yml or via environment variable
export SERVER_PORT=8081
java -jar target/url-shortener.jar
```

### MySQL Connection Error
```
Error: Can't connect to MySQL server at 'localhost:3306'
```

**Solution:**
1. Verify MySQL is running: `mysql -u root -p`
2. Check database exists: `SHOW DATABASES;`
3. Update DB credentials in `application.yml`
4. Use Docker: `docker-compose up`

### UI Not Loading
1. Check http://localhost:8080 in your browser
2. Open browser console (F12) for errors
3. Check server logs for exceptions
4. Verify static files are in `src/main/resources/static/`

### Analytics Not Showing
1. Ensure you've clicked the short link at least once
2. Wait 30 seconds for auto-refresh
3. Click the link from different tabs/browsers to register multiple clicks
4. Check browser console for API errors

## Performance & Monitoring

### View Application Logs
```bash
# Logs are printed to console by default
# For production, redirect to a file:
java -jar target/url-shortener.jar > app.log 2>&1 &
```

### Monitor Database
```bash
# Connect to MySQL and monitor
mysql -u root -p
USE url_shortener;
SELECT COUNT(*) FROM url_mapping;  # Total URLs created
SELECT SUM(click_count) FROM url_mapping;  # Total clicks
```

### Check Application Health
Visit the metrics endpoint (if enabled):
```bash
curl http://localhost:8080/actuator/health
```

## Deployment

### Deploy to Cloud (AWS/GCP/Azure)

1. **Build the JAR:**
   ```bash
   mvn clean package
   ```

2. **Create Docker image:**
   ```bash
   docker build -t url-shortener:1.0 .
   ```

3. **Push to container registry:**
   ```bash
   docker tag url-shortener:1.0 your-registry/url-shortener:1.0
   docker push your-registry/url-shortener:1.0
   ```

4. **Deploy (example for AWS):**
   ```bash
   # Using AWS ECS or Elastic Beanstalk
   # Follow your platform's deployment guide
   ```

### Environment Configuration for Production

```bash
# Create a .env file with production values
APP_BASE_URL=https://your-domain.com
DB_URL=jdbc:mysql://prod-db-host:3306/url_shortener
DB_USERNAME=db_user
DB_PASSWORD=secure_password_here
SERVER_PORT=8080
DDL_AUTO=validate  # IMPORTANT: Use 'validate' in production, not 'update'
```

## Support

- **Documentation**: See `UI_DOCUMENTATION.md` for UI details
- **API Reference**: See code comments in controller classes
- **Issues**: Check application logs and browser console

## License

This project is part of the URL Shortener demonstration project.

---

**Last Updated**: June 22, 2024  
**Status**: Ready for Production ✓
