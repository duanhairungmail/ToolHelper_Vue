namespace ToolHelper.Agent.Infrastructure;

public sealed class RuntimeOptions
{
    public required string SessionToken { get; init; }
    public required string InternalToken { get; init; }
    public required ISet<string> AllowedOrigins { get; init; }
    public string JavaApiBase { get; init; } = "http://127.0.0.1:8080";
}
