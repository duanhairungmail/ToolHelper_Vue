namespace ToolHelper.Agent.Application.Contracts;

public sealed record ApiResponse<T>(
    bool Success,
    string Code,
    string Message,
    T? Data,
    string TraceId);

public sealed record HealthData(
    string Service,
    string Version,
    string Status,
    string TraceId);

public sealed record SseEvent(
    string EventId,
    string Type,
    string JobId,
    DateTimeOffset Timestamp,
    string TraceId,
    IReadOnlyDictionary<string, object?> Payload);
