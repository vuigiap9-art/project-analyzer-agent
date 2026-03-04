# Project Insight Agent

## 🚀 Quick Start

### Prerequisites
- Java 21+
- Maven 3.8+
- DeepSeek API Key

### Setup

1. Set environment variable:
```bash
export DEEPSEEK_API_KEY=your-api-key-here
```

2. Build and run:
```bash
mvn clean install
mvn spring-boot:run
```

3. The service will start on `http://localhost:8080`

## 📡 API Endpoints

### 1. Analyze Project
```bash
GET /api/analyze?path=/path/to/your/project
```

**Response:**
```json
{
  "status": "success",
  "filesScanned": 42,
  "auditReport": "# Audit Report\n...",
  "blueprint": "# Project Blueprint\n...",
  "message": "Analysis completed successfully. Blueprint saved to Project-Blueprint.md"
}
```

### 2. Analyze Single File
```bash
POST /api/analyze-snippet
Content-Type: application/json

{
  "path": "/path/to/file.java",
  "language": "Java",
  "code": "public class Example { ... }"
}
```

### 3. Health Check
```bash
GET /api/health
```

## 🏗️ Architecture

```
┌─────────────────────────────────────────────────────────┐
│                  AnalysisController                      │
│                   (REST API Layer)                       │
└────────────────────┬────────────────────────────────────┘
                     │
        ┌────────────┼────────────┐
        │            │            │
        ▼            ▼            ▼
┌──────────────┐ ┌──────────────┐ ┌──────────────────┐
│CodeCrawler   │ │LogicAudit    │ │ProjectSummarizer │
│Service       │ │Service       │ │Service           │
└──────┬───────┘ └──────┬───────┘ └────────┬─────────┘
       │                │                   │
       │                ▼                   │
       │         ┌──────────────┐           │
       │         │AuditAssistant│           │
       │         │(LangChain4j) │           │
       │         └──────┬───────┘           │
       │                │                   │
       │                ▼                   │
       │         ┌──────────────┐           │
       │         │  DeepSeek    │           │
       │         │     API      │           │
       │         └──────────────┘           │
       │                                    │
       └────────────────┬───────────────────┘
                        ▼
                ┌──────────────┐
                │Project-      │
                │Blueprint.md  │
                └──────────────┘
```

## 🔍 Features

- **Multi-threaded Scanning**: Java 21 virtual threads for fast file crawling
- **Multi-language Support**: Java, Python, C++, Go
- **Deep Logic Audit**: Security, concurrency, resource leaks, distributed system issues
- **Architecture Visualization**: Auto-generated Mermaid diagrams
- **Project Blueprint**: Comprehensive project summary with tech stack and vulnerabilities

## 📝 Example Usage

```bash
# Analyze a Java project
curl "http://localhost:8080/api/analyze?path=/home/user/my-java-project"

# Check service health
curl http://localhost:8080/api/health
```

## 🛠️ Technology Stack

- Spring Boot 3.3.5
- Java 21 (Virtual Threads)
- LangChain4j 0.36.2
- DeepSeek API
- Lombok

## 📄 Output

The analysis generates:
1. **Audit Report**: Detailed security and code quality analysis
2. **Project-Blueprint.md**: Saved in the analyzed project directory
   - Technology stack overview
   - Architecture diagrams (Mermaid)
   - Known vulnerabilities
   - Code statistics
