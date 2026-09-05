using ToolHelper.Agent.Api.Security;
using ToolHelper.Agent.Application.Contracts;
using ToolHelper.Agent.Domain;
using ToolHelper.Agent.Infrastructure;

var builder = WebApplication.CreateBuilder(args);
var sessionToken = builder.Configuration["TOOLHELPER_SESSION_TOKEN"] ?? Environment.GetEnvironmentVariable("TOOLHELPER_SESSION_TOKEN");
var internalToken = builder.Configuration["TOOLHELPER_INTERNAL_TOKEN"] ?? Environment.GetEnvironmentVariable("TOOLHELPER_INTERNAL_TOKEN");
if (string.IsNullOrWhiteSpace(sessionToken) || string.IsNullOrWhiteSpace(internalToken))
    throw new InvalidOperationException("TOOLHELPER_SESSION_TOKEN 和 TOOLHELPER_INTERNAL_TOKEN 必须由 Launcher 注入");

var origins = (builder.Configuration["TOOLHELPER_ALLOWED_ORIGINS"] ?? "http://127.0.0.1:5173;http://localhost:5173")
    .Split(';', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries)
    .ToHashSet(StringComparer.Ordinal);
builder.Services.AddSingleton(new RuntimeOptions { SessionToken = sessionToken, InternalToken = internalToken, AllowedOrigins = origins });

var port = builder.Configuration.GetValue("TOOLHELPER_LOCAL_PORT", 0);
builder.WebHost.UseUrls($"http://127.0.0.1:{port}");
var app = builder.Build();
app.UseMiddleware<RequestSecurityMiddleware>();
app.UseMiddleware<TraceIdMiddleware>();

app.MapGet("/health", (HttpContext context) =>
{
    var traceId = (string)context.Items[TraceIdMiddleware.Key]!;
    return Results.Ok(new ApiResponse<HealthData>(true, "OK", "服务正常", new HealthData(
        DomainMarker.ServiceName, "0.1.0", "UP", traceId), traceId));
});

app.MapGet("/api/jobs/{jobId}/events", async (string jobId, HttpContext context) =>
{
    var traceId = (string)context.Items[TraceIdMiddleware.Key]!;
    var lastEventId = context.Request.Headers["Last-Event-ID"].FirstOrDefault();
    context.Response.ContentType = "text/event-stream; charset=utf-8";
    context.Response.Headers.CacheControl = "no-cache";
    if (lastEventId != "1")
    {
        var payload = new SseEvent("1", "job.completed", jobId, DateTimeOffset.UtcNow, traceId,
            new Dictionary<string, object?> { ["level"] = "INFO", ["message"] = "任务已完成" });
        await context.Response.WriteAsync($"id: {payload.EventId}\nevent: {payload.Type}\ndata: {System.Text.Json.JsonSerializer.Serialize(payload)}\n\n");
        await context.Response.Body.FlushAsync();
    }
});

app.Run();
