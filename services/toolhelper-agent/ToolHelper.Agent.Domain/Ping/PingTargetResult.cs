namespace ToolHelper.Agent.Domain.Ping;

public sealed record PingTargetResult(
    string Address,
    int InputIndex,
    int CompletionIndex,
    PingStatusCode Status,
    int Attempts,
    int SuccessCount,
    int? AverageDelayMs,
    int PacketLossPercent,
    string? Error);
