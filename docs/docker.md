# Docker

## Build

From the project root:

```bash
docker build -t wondering-wizard .
```

This runs a multi-stage build: compiles with `eclipse-temurin:21-jdk`, runs tests, then packages into a slim `eclipse-temurin:21-jre` runtime image.

### Rebuild without cache

```bash
docker build --no-cache -t wondering-wizard .
```

## Configuration

Settings are loaded from the bundled `settings.properties` inside the JAR. Every property can be overridden via environment variables by converting the property key:

- Replace `.` and `-` with `_`
- Convert to UPPERCASE

### Environment variable mapping

| Property | Environment Variable |
|---|---|
| `server.port` | `SERVER_PORT` |
| `kafka.enabled` | `KAFKA_ENABLED` |
| `kafka.bootstrap-server` | `KAFKA_BOOTSTRAP_SERVER` |
| `kafka.client-id` | `KAFKA_CLIENT_ID` |
| `kafka.schema-registry-url` | `KAFKA_SCHEMA_REGISTRY_URL` |
| `kafka.security-protocol` | `KAFKA_SECURITY_PROTOCOL` |
| `kafka.sasl-mechanism` | `KAFKA_SASL_MECHANISM` |
| `kafka.sasl-username` | `KAFKA_SASL_USERNAME` |
| `kafka.sasl-password` | `KAFKA_SASL_PASSWORD` |
| `kafka.terminal-code` | `KAFKA_TERMINAL_CODE` |
| `kafka.consumer.work-queue.topic` | `KAFKA_CONSUMER_WORK_QUEUE_TOPIC` |
| `kafka.consumer.work-queue.group-id` | `KAFKA_CONSUMER_WORK_QUEUE_GROUP_ID` |
| `kafka.consumer.work-instruction.topic` | `KAFKA_CONSUMER_WORK_INSTRUCTION_TOPIC` |
| `kafka.consumer.work-instruction.group-id` | `KAFKA_CONSUMER_WORK_INSTRUCTION_GROUP_ID` |
| `kafka.consumer.asset-event-rtg.topic` | `KAFKA_CONSUMER_ASSET_EVENT_RTG_TOPIC` |
| `kafka.consumer.asset-event-qc.topic` | `KAFKA_CONSUMER_ASSET_EVENT_QC_TOPIC` |
| `kafka.consumer.asset-event-eh.topic` | `KAFKA_CONSUMER_ASSET_EVENT_EH_TOPIC` |
| `kafka.producer.equipment-instruction-rtg.topic` | `KAFKA_PRODUCER_EQUIPMENT_INSTRUCTION_RTG_TOPIC` |
| `kafka.producer.equipment-instruction-tt.topic` | `KAFKA_PRODUCER_EQUIPMENT_INSTRUCTION_TT_TOPIC` |
| `kafka.producer.equipment-instruction-qc.topic` | `KAFKA_PRODUCER_EQUIPMENT_INSTRUCTION_QC_TOPIC` |

### Example

```bash
docker run -p 9090:9090 \
  -e SERVER_PORT=9090 \
  -e KAFKA_BOOTSTRAP_SERVER=my-kafka:9092 \
  -e KAFKA_SASL_USERNAME=myuser \
  -e KAFKA_SASL_PASSWORD=mypassword \
  wondering-wizard
```

### External settings file

Alternatively, mount a complete settings file and point to it via `SETTINGS_FILE`:

```bash
docker run -v ./my-settings.properties:/config/settings.properties \
  -e SETTINGS_FILE=/config/settings.properties \
  wondering-wizard
```

Override precedence (highest to lowest):

1. Environment variables
2. External settings file (`SETTINGS_FILE`)
3. Bundled `settings.properties`
