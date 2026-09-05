using ToolHelper.Agent.Api.Security;
using ToolHelper.Agent.Application.Contracts;
using ToolHelper.Agent.Domain;
using ToolHelper.Agent.Infrastructure;
using ToolHelper.Agent.Application.Ping;
using ToolHelper.Agent.Infrastructure.Export;
using ToolHelper.Agent.Infrastructure.Network;
using ToolHelper.Agent.Infrastructure.Persistence;
using ToolHelper.Agent.Api.Persistence;
using System.Text.Json;

var builder = WebApplication.CreateBuilder(args);
var sessionToken = builder.Configuration["TOOLHELPER_SESSION_TOKEN"] ?? Environment.GetEnvironmentVariable("TOOLHELPER_SESSION_TOKEN");
var internalToken = builder.Configuration["TOOLHELPER_INTERNAL_TOKEN"] ?? Environment.GetEnvironmentVariable("TOOLHELPER_INTERNAL_TOKEN");
if (string.IsNullOrWhiteSpace(sessionToken) || string.IsNullOrWhiteSpace(internalToken))
    throw new InvalidOperationException("TOOLHELPER_SESSION_TOKEN 和 TOOLHELPER_INTERNAL_TOKEN 必须由 Launcher 注入");

var origins = (builder.Configuration["TOOLHELPER_ALLOWED_ORIGINS"] ?? "http://127.0.0.1:5173;http://localhost:5173")
    .Split(';', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries)
    .ToHashSet(StringComparer.Ordinal);
var javaApiBase = builder.Configuration["TOOLHELPER_JAVA_API_BASE"] ?? Environment.GetEnvironmentVariable("TOOLHELPER_JAVA_API_BASE") ?? "http://127.0.0.1:8080";
builder.Services.AddSingleton(new RuntimeOptions { SessionToken = sessionToken, InternalToken = internalToken, AllowedOrigins = origins, JavaApiBase = javaApiBase });
builder.Services.AddSingleton<ExpandTargetsUseCase>();
builder.Services.AddSingleton<SystemPingExecutor>();
builder.Services.AddSingleton<IPingExecutor>(sp => sp.GetRequiredService<SystemPingExecutor>());
builder.Services.AddSingleton<CsvPingExporter>();
builder.Services.AddSingleton<ClosedXmlPingExporter>();
builder.Services.AddSingleton<IPingExporter>(sp => sp.GetRequiredService<CsvPingExporter>());
builder.Services.AddSingleton<IPingExporter>(sp => sp.GetRequiredService<ClosedXmlPingExporter>());
builder.Services.AddSingleton<JavaHistoryClient>();
builder.Services.AddSingleton<IPingHistoryClient>(sp => sp.GetRequiredService<JavaHistoryClient>());
builder.Services.AddHostedService<PendingSubmissionWorker>();
builder.Services.AddSingleton<PingJobManager>();

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

app.MapPost("/api/local/ping/targets/expand", (ExpandTargetsRequest request, PingJobManager manager, HttpContext context) =>
{
    var traceId = (string)context.Items[TraceIdMiddleware.Key]!;
    return Results.Ok(new ApiResponse<ExpandTargetsResponse>(true, "OK", "目标展开成功", manager.Expand(request.Input), traceId));
});

app.MapPost("/api/local/ping/jobs", (StartPingRequest request, PingJobManager manager, HttpContext context) =>
{
    var traceId = (string)context.Items[TraceIdMiddleware.Key]!;
    return Results.Ok(new ApiResponse<StartPingResponse>(true, "OK", "群 Ping 任务已启动", manager.Start(request, traceId), traceId));
});

app.MapGet("/api/local/ping/jobs/{jobId}", (string jobId, PingJobManager manager, HttpContext context) =>
{
    var traceId = (string)context.Items[TraceIdMiddleware.Key]!;
    var job = manager.Require(jobId);
    return Results.Ok(new ApiResponse<object>(true, "OK", "任务状态", new { job.Id, status = job.Status.ToString().ToUpperInvariant(), summary = job.Summary, results = job.Results.ToArray(), completedAt = job.CompletedAt }, traceId));
});

app.MapGet("/api/local/ping/jobs/{jobId}/events", async (string jobId, HttpContext context, PingJobManager manager) =>
{
    var traceId = (string)context.Items[TraceIdMiddleware.Key]!;
    context.Response.ContentType = "text/event-stream; charset=utf-8";
    context.Response.Headers.CacheControl = "no-cache";
    await foreach (var item in manager.Events(jobId, context.Request.Headers["Last-Event-ID"].FirstOrDefault()).ReadAllAsync(context.RequestAborted))
    {
        await context.Response.WriteAsync($"id: {item.EventId}\nevent: {item.Type}\ndata: {JsonSerializer.Serialize(item)}\n\n", context.RequestAborted);
        await context.Response.Body.FlushAsync(context.RequestAborted);
    }
});

app.MapPost("/api/local/ping/jobs/{jobId}/cancel", (string jobId, PingJobManager manager, HttpContext context) =>
{
    var traceId = (string)context.Items[TraceIdMiddleware.Key]!;
    manager.Cancel(jobId);
    return Results.Ok(new ApiResponse<object?>(true, "OK", "停止请求已提交", null, traceId));
});

app.MapGet("/api/local/ping/jobs/{jobId}/export", (string jobId, string? format, PingJobManager manager) =>
{
    var selected = string.IsNullOrWhiteSpace(format) ? "csv" : format;
    if (!selected.Equals("csv", StringComparison.OrdinalIgnoreCase) && !selected.Equals("xlsx", StringComparison.OrdinalIgnoreCase))
        return Results.BadRequest(new { code = "FORMAT_INVALID", message = "仅支持 csv 或 xlsx 导出" });
    var export = manager.Export(jobId, selected);
    return Results.File(export.Content, export.ContentType, export.FileName);
});

app.Run();
