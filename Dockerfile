# ─────────────────────────────────────────────────────────────────────────────
# Multi-stage build para imagem de produção otimizada.
#
# Stage 1 (builder): Compila e empacota a aplicação
# Stage 2 (runtime): Imagem mínima com apenas o necessário para execução
#
# Resultado: imagem final ~250MB vs ~600MB com imagem única
# ─────────────────────────────────────────────────────────────────────────────

# ── Stage 1: Build ───────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /app

# Copia o Maven Wrapper antes do código fonte.
# Isso permite que o Docker cache a camada de dependências
# separadamente do código — rebuild é muito mais rápido.
COPY mvnw .
COPY .mvn .mvn
COPY pom.xml .

# Download de dependências (camada cacheável)
# Se o pom.xml não mudar, esta camada é reutilizada no próximo build
RUN ./mvnw dependency:go-offline -q

# Copia o código fonte e compila
COPY src src
RUN ./mvnw clean package -DskipTests -q

# Extrai as camadas do Spring Boot para otimização de camadas Docker
# Isso separa libs (raramente muda) do código da app (muda frequentemente)
RUN java -Djarmode=layertools -jar target/*.jar extract --destination extracted

# ── Stage 2: Runtime ─────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine AS runtime

# Usuário não-root por segurança
RUN addgroup -S monitor && adduser -S monitor -G monitor

WORKDIR /app

# Copia as camadas do Spring Boot na ordem certa (menos volátil → mais volátil)
# Isso maximiza o cache de camadas Docker em deploys
COPY --from=builder --chown=monitor:monitor /app/extracted/dependencies/ ./
COPY --from=builder --chown=monitor:monitor /app/extracted/spring-boot-loader/ ./
COPY --from=builder --chown=monitor:monitor /app/extracted/snapshot-dependencies/ ./
COPY --from=builder --chown=monitor:monitor /app/extracted/application/ ./

USER monitor

EXPOSE 8080

# JVM flags otimizados para containers:
# -XX:+UseContainerSupport: detecta limites de CPU/memória do container
# -XX:MaxRAMPercentage: usa até 75% da RAM do container
# -XX:+UseZGC: GC de baixa latência (ideal para aplicações de monitoramento)
# -Djava.security.egd: entropia mais rápida para startup
ENV JAVA_OPTS="-XX:+UseContainerSupport \
    -XX:MaxRAMPercentage=75.0 \
    -XX:+UseZGC \
    -Djava.security.egd=file:/dev/./urandom \
    -Dspring.backgroundpreinitializer.ignore=true"

HEALTHCHECK --interval=30s --timeout=10s --start-period=30s --retries=3 \
    CMD wget -q --spider http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]
