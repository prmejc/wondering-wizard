# Claude Instructions

For all development work in this repository, follow these guidelines:

## Frontend Development

### Code Structure
- **All code goes into `index.html`** - Keep the entire application in a single HTML file
- **No external libraries allowed** - Use vanilla JavaScript and native browser APIs only

### Architecture
- **Use Web Components** - All custom UI elements should be implemented as Web Components
- **Extend HTMLElement** - Create custom elements by extending the HTMLElement class
- Use the Custom Elements API for component registration
- Follow Web Components best practices for encapsulation and reusability

### Example Structure
```javascript
class MyComponent extends HTMLElement {
  constructor() {
    super();
    // Initialize component
  }

  connectedCallback() {
    // Component mounted to DOM
  }
}

customElements.define('my-component', MyComponent);
```

## Backend Development

### Technology Stack
- **Use latest Java (Java 21+)** - All backend code should be written in the latest stable Java version
- Leverage modern Java features such as:
  - Virtual threads
  - Pattern matching
  - Records
  - Sealed classes
  - Enhanced switch expressions

### Best Practices
- Follow standard Java conventions and naming patterns
- Use appropriate frameworks as needed (Spring Boot, Quarkus, etc.)
- Write clean, maintainable, and well-documented code
- **No string literals for domain values** - Use constants or enums instead of string literals for status values, types, or any domain-specific values (e.g., use `WorkQueueStatus.ACTIVE` instead of `"Active"`)

### Event-Driven Architecture

This is a clean event-driven system following these principles:

#### Event Processing Model
- **Input → Event transformation** - Every input is transformed into an event
- **Event → Side Effects** - Each event processing results in a list of side effects
- Side effects can be:
  - Persisted to a database
  - Sent as messages to Kafka or similar message brokers

#### Processing Rules
- **No delays or thread sleeps** - Blocking operations like `Thread.sleep()` or delays are strictly forbidden inside event processing
- **Time-based logic must use TimeEvent** - If business logic depends on current time:
  - React to `TimeEvent` which is sent through the system every 5 seconds
  - The event contains the UTC timestamp of when it was generated
  - **No direct time lookups allowed** (e.g., `System.currentTimeMillis()`, `Instant.now()`, `LocalDateTime.now()`)

#### Processing Engine
- The processing engine supports **multiple processing plugins**
- Plugins are configured at startup time
- Each plugin processes events independently and produces side effects

#### Observability (OTEL Metrics)
- Every plugin has its own OpenTelemetry metric
- Each processing function has its own OTEL metric
- Metrics track processing duration percentiles:
  - **p50** - Median processing time
  - **p90** - 90th percentile processing time
  - **p99** - 99th percentile processing time
- Use these metrics to monitor and optimize event processing performance

## Testing Requirements

- **Every new feature requires tests** - No feature is complete without corresponding test coverage
- **Every bug fix requires a test** - Add a test that reproduces the bug before fixing it to prevent regression
- **Test coverage must be above 90%** - Maintain high code coverage across the codebase
