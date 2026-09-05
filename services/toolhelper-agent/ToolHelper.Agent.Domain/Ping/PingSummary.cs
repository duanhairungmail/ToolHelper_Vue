namespace ToolHelper.Agent.Domain.Ping;

public sealed record PingSummary(int TargetTotal, int Online, int Offline, int PartialLoss, int? AverageDelayMs)
{
    public static PingSummary Empty => new(0, 0, 0, 0, null);
}
