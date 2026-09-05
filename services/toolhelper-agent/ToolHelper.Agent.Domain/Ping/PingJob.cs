namespace ToolHelper.Agent.Domain.Ping;

public enum PingJobStatus
{
    Running,
    Completed,
    Cancelled,
    Failed
}

public enum PingStatusCode
{
    Online,
    PartialLoss,
    Offline
}

public sealed record PingOptions(int Concurrency, int Count, int TimeoutMs)
{
    public static PingOptions Default => new(50, 4, 1000);
}

public sealed class PingJob
{
    public required string Id { get; init; }
    public required string Input { get; init; }
    public required DateTimeOffset CreatedAt { get; init; }
    public required IReadOnlyList<PingTarget> Targets { get; init; }
    public required PingOptions Options { get; init; }
    public PingJobStatus Status { get; set; } = PingJobStatus.Running;
    public PingSummary Summary { get; set; } = PingSummary.Empty;
    public List<PingTargetResult> Results { get; } = [];
    public DateTimeOffset? CompletedAt { get; set; }
    public CancellationTokenSource Cancellation { get; } = new();
}
