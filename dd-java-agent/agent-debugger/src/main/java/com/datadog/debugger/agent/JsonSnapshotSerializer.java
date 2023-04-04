package com.datadog.debugger.agent;

import com.datadog.debugger.util.MoshiHelper;
import com.datadog.debugger.util.MoshiSnapshotHelper;
import com.squareup.moshi.Json;
import com.squareup.moshi.JsonAdapter;
import datadog.trace.bootstrap.debugger.DebuggerContext;
import datadog.trace.bootstrap.debugger.Snapshot;
import java.util.Map;

/** Serializes snapshots in Json using Moshi */
public class JsonSnapshotSerializer implements DebuggerContext.SnapshotSerializer {
  private static final String DD_TRACE_ID = "dd.trace_id";
  private static final String DD_SPAN_ID = "dd.span_id";
  private static final JsonAdapter<IntakeRequest> ADAPTER =
      MoshiHelper.createMoshiSnapshot().adapter(IntakeRequest.class);
  private static final JsonAdapter<Snapshot.CapturedValue> VALUE_ADAPTER =
      new MoshiSnapshotHelper.CapturedValueAdapter();

  @Override
  public String serializeSnapshot(String serviceName, Snapshot snapshot) {
    IntakeRequest request = new IntakeRequest(serviceName, new DebuggerIntakeRequestData(snapshot));
    handleCorrelationFields(snapshot, request);
    handleDuration(snapshot, request);
    handlerLogger(snapshot, request);
    return ADAPTER.toJson(request);
  }

  @Override
  public String serializeValue(Snapshot.CapturedValue value) {
    return VALUE_ADAPTER.toJson(value);
  }

  private void handlerLogger(Snapshot snapshot, IntakeRequest request) {
    request.loggerName = snapshot.getProbe().getLocation().getType();
    request.loggerMethod = snapshot.getProbe().getLocation().getMethod();
    request.loggerVersion = snapshot.getVersion();
    request.loggerThreadId = snapshot.getThread().getId();
    request.loggerThreadName = snapshot.getThread().getName();
  }

  private void handleDuration(Snapshot snapshot, IntakeRequest request) {
    request.duration = snapshot.getDuration();
  }

  private void handleCorrelationFields(Snapshot snapshot, IntakeRequest request) {
    Snapshot.CapturedContext entry = snapshot.getCaptures().getEntry();
    if (entry != null) {
      addTraceSpanId(entry, request);
      removeTraceSpanId(entry);
    }
    if (snapshot.getCaptures().getLines() != null) {
      for (Snapshot.CapturedContext context : snapshot.getCaptures().getLines().values()) {
        addTraceSpanId(context, request);
        removeTraceSpanId(context);
      }
    }
    removeTraceSpanId(snapshot.getCaptures().getReturn());
  }

  private void removeTraceSpanId(Snapshot.CapturedContext context) {
    if (context == null) {
      return;
    }
    Map<String, Snapshot.CapturedValue> fields = context.getFields();
    if (fields == null) {
      return;
    }
    fields.remove(DD_TRACE_ID);
    fields.remove(DD_SPAN_ID);
  }

  private void addTraceSpanId(Snapshot.CapturedContext context, IntakeRequest request) {
    request.traceId = context.getTraceId();
    request.spanId = context.getSpanId();
  }

  public static class IntakeRequest {
    private final String service;
    private final DebuggerIntakeRequestData debugger;
    private final String ddsource = "dd_debugger";
    private final String message;

    private final String ddtags;

    @Json(name = "dd.trace_id")
    private String traceId;

    @Json(name = "dd.span_id")
    private String spanId;

    private long duration;

    private long timestamp;

    @Json(name = "logger.name")
    private String loggerName;

    @Json(name = "logger.method")
    private String loggerMethod;

    @Json(name = "logger.version")
    private int loggerVersion;

    @Json(name = "logger.thread_id")
    private long loggerThreadId;

    @Json(name = "logger.thread_name")
    private String loggerThreadName;

    public IntakeRequest(String service, DebuggerIntakeRequestData debugger) {
      this.service = service;
      this.debugger = debugger;
      this.message = debugger.snapshot.getMessage();
      this.ddtags = debugger.snapshot.getProbe().getStrTags();
      this.timestamp = debugger.snapshot.getTimestamp();
    }

    public String getService() {
      return service;
    }

    public DebuggerIntakeRequestData getDebugger() {
      return debugger;
    }

    public String getDdsource() {
      return ddsource;
    }

    public String getMessage() {
      return message;
    }

    public String getTraceId() {
      return traceId;
    }

    public String getSpanId() {
      return spanId;
    }

    public long getTimestamp() {
      return timestamp;
    }

    public String getLoggerName() {
      return loggerName;
    }

    public String getLoggerMethod() {
      return loggerMethod;
    }

    public int getLoggerVersion() {
      return loggerVersion;
    }

    public long getLoggerThreadId() {
      return loggerThreadId;
    }

    public String getLoggerThreadName() {
      return loggerThreadName;
    }
  }

  public static class DebuggerIntakeRequestData {
    private final Snapshot snapshot;

    public DebuggerIntakeRequestData(Snapshot snapshot) {
      this.snapshot = snapshot;
    }

    public Snapshot getSnapshot() {
      return snapshot;
    }
  }
}
