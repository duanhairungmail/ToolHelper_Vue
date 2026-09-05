using ToolHelper.Agent.Domain.Ping;

namespace ToolHelper.Agent.Application.Ping;

public sealed record ExpandTargetsRequest(string Input);
public sealed record ExpandTargetsResponse(IReadOnlyList<PingTarget> Targets, bool Truncated, int EstimatedTotal);
public sealed record StartPingRequest(string Input, int? Concurrency, int? Count, int? TimeoutMs);
public sealed record StartPingResponse(string JobId, int TargetTotal, bool Truncated, int EstimatedTotal);

public interface IPingExecutor
{
    Task<(bool Success, int? DelayMs, string? Error)> SendAsync(string address, int timeoutMs, CancellationToken cancellationToken);
}

public interface IPingHistoryClient
{
    Task SubmitAsync(PingJob job, CancellationToken cancellationToken);
}

public interface IPingExporter
{
    byte[] Export(PingJob job);
    string ContentType { get; }
    string Extension { get; }
}
