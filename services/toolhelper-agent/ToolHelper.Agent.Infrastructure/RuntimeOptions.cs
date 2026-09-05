namespace ToolHelper.Agent.Infrastructure;

public sealed class RuntimeOptions
{
    public required string SessionToken { get; init; }
    public required string InternalToken { get; init; }
    public required ISet<string> AllowedOrigins { get; init; }
}
