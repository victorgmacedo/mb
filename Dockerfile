FROM ghcr.io/graalvm/native-image-community:25@sha256:0d936f32bb8acb5bc60c41b33e05f064d7a6aaf36b726538296c54949bd4a3c0 AS classes
ARG NATIVE_IMAGE_BUILD_HEAP=3000m
ENV NATIVE_IMAGE_BUILD_HEAP=$NATIVE_IMAGE_BUILD_HEAP
WORKDIR /workspace
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle/ gradle/
COPY shared/build.gradle.kts shared/
COPY shared/src/main/ shared/src/main/
COPY command-log/build.gradle.kts command-log/
COPY command-log/src/main/ command-log/src/main/
COPY ledger/build.gradle.kts ledger/
COPY ledger/src/main/ ledger/src/main/
COPY engine/build.gradle.kts engine/
COPY engine/src/main/ engine/src/main/
COPY gateway/build.gradle.kts gateway/
COPY gateway/src/main/ gateway/src/main/
COPY scripts/build-native.sh scripts/build-native.sh
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon --max-workers=2 \
    -Dorg.gradle.jvmargs=-Xmx512m :gateway:classes :engine:classes :ledger:classes

FROM classes AS gateway-build
RUN --mount=type=cache,target=/root/.gradle ./scripts/build-native.sh gateway nativeCompile br.com.mb.gateway.GatewayApplication
FROM classes AS engine-build
RUN --mount=type=cache,target=/root/.gradle ./scripts/build-native.sh engine nativeCompile br.com.mb.engine.EngineApplication
FROM classes AS settlements-build
RUN --mount=type=cache,target=/root/.gradle ./scripts/build-native.sh ledger nativeCompile br.com.mb.ledger.LedgerSettlementApplication
FROM classes AS snapshots-build
RUN --mount=type=cache,target=/root/.gradle ./scripts/build-native.sh engine nativeSnapshotsCompile br.com.mb.engine.BookSnapshotsApplication

FROM gcr.io/distroless/cc-debian13:nonroot@sha256:e792ab3d241a468a4fd7519ddbbebe66b49b5f365771716ea688ad40b6c6f1c2 AS runtime
WORKDIR /app
# Native Image links zlib dynamically; keep only this additional runtime library.
COPY --from=classes /usr/lib64/libz.so.1 /app/lib/libz.so.1
ENV LD_LIBRARY_PATH=/app/lib
USER 65532:65532

FROM runtime AS gateway
COPY --from=gateway-build /workspace/gateway/build/native/nativeCompile/mb-gateway /app/service
EXPOSE 8080
ENTRYPOINT ["/app/service"]
FROM runtime AS engine
COPY --from=engine-build /workspace/engine/build/native/nativeCompile/mb-engine /app/service
EXPOSE 8081
ENTRYPOINT ["/app/service"]
FROM runtime AS settlements
COPY --from=settlements-build /workspace/ledger/build/native/nativeCompile/mb-settlements /app/service
ENTRYPOINT ["/app/service"]
FROM runtime AS snapshots
COPY --from=snapshots-build /workspace/engine/build/native/nativeSnapshotsCompile/mb-snapshots /app/service
ENTRYPOINT ["/app/service"]
