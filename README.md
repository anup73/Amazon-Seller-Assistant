# Amazon Seller Assistant

Amazon Seller Assistant is a comprehensive AI-driven ecosystem designed to help e-commerce sellers manage their store operations through a natural language interface. It integrates Large Language Models (LLMs), Model Context Protocol (MCP), Retrieval-Augmented Generation (RAG), and robust OAuth2 security to provide a seamless assistant experience.

## 🛠️ Key Features

### AI & Intelligence
- **Natural Language Chat**: Interact with your store data using a browser-based UI.
- **Tool Use (MCP)**: The agent can automatically decide to call tools like `getAllOrders` or `updateProductInventory` to perform real actions.
- **Semantic Search (RAG)**: Instead of keyword matching, the agent understands the intent behind product queries.
- **✨ Multi-Agent Report Workflows**: Implements a coordinator-specialist pattern for automated business reporting:
    - **Coordinator Agents**: Manage the overall reporting process (e.g., `OrderSummaryReportService`, `ProductInventoryReportService`).
    - **Specialist Agents**: Focused research agents (e.g., Delivered Order Researcher, Cancellation Pattern Researcher) that gather specific data.
    - **Automated Delivery**: Scheduled tasks (`DailyOrderSummaryReportTask`, `DailyProductInventoryReportTask`) trigger these workflows to generate and email daily business insights.

### Security & Observability
- **OAuth2 M2M Security**: Every internal request is authenticated via JWTs issued by the `ecommerce-auth-server`.
- **Full Tracing**: Integrated with OpenTelemetry, Prometheus, and Loki for monitoring agent traces and system health.

## ⚙️ Configuration

Each module has its own `application.properties`. Common configurations include:
- `spring.ai.ollama.base-url`: URL of your Ollama instance.
- `spring.security.oauth2.client...`: Client IDs and secrets for inter-service communication.
- `app.sqlite-vec.extension-path`: Absolute path to the `vec0` library for RAG.

## 🏗️ System Architecture

The system is composed of several microservices that work together to provide AI capabilities:

- **`ecommerce-ai-agent`**: The main entry point. A Spring Boot application providing a web chat interface. It uses Spring AI and Ollama to orchestrate responses by combining RAG search and MCP tools.
- **`springboot-mcp-server`**: Acts as a bridge between the AI agent and the core e-commerce API. It exposes business operations (orders, customers, products) as **MCP Tools** that the LLM can discover and invoke.
- **`ecommerece-rag-service`**: Provides semantic search over the product catalog. It indexes MySQL data into a SQLite vector database using `sqlite-vec` and Ollama embeddings, allowing the agent to find relevant products or policies.
- **`ecommerce-auth-server`**: A centralized OAuth2 Authorization Server. It manages machine-to-machine (M2M) authentication using `client_credentials` grants, ensuring secure communication between all services.
- **`agent` (Ecommerce API Server)**: The core backend REST API that handles the actual business logic and database interactions for orders, customers, and payments.

### Request Flow
`User` $\rightarrow$ `AI Agent` $\rightarrow$ (`RAG Service` for knowledge / `MCP Server` for actions) $\rightarrow$ `Ecommerce API` $\rightarrow$ `MySQL`

## 🚀 Getting Started

### Prerequisites
- **JDK 17+**
- **MySQL** (for product catalog and order data)
- **Ollama** (running locally with `gemma4` and `nomic-embed-text` models)
- **sqlite-vec** (extension library installed on your OS for the RAG service)

### Installation & Execution Order

To get the full system running, start the services in the following order:

1. **Authorization Server** (Port 9000)
   ```bash
   cd ecommerce-auth-server
   ./mvnw spring-boot:run
   ```
2. **Core API Server** (Port 8080)
   ```bash
   cd agent
   ./mvnw spring-boot:run
   ```
3. **RAG Service** (Port 8081)
   ```bash
   cd ecommerece-rag-service
   ./mvnw spring-boot:run
   # After starting, trigger initial indexing:
   curl -X POST http://localhost:8081/api/rag/reindex
   ```
4. **MCP Server** (Port 8090)
   ```bash
   cd springboot-mcp-server
   ./mvnw spring-boot:run
   ```
5. **AI Agent** (Port 8085)
   ```bash
   cd ecommerce-ai-agent
   ./mvnw spring-boot:run
   ```

## 📁 Project Structure

```text
.
├── agent/                   # Core E-commerce REST API
├── ecommerce-ai-agent/       # AI Orchestrator & Chat UI
├── ecommerce-auth-server/    # OAuth2 Authorization Server
├── ecommerece-rag-service/   # Vector Search & Indexing Service
└── springboot-mcp-server/    # MCP Tool Bridge
```
