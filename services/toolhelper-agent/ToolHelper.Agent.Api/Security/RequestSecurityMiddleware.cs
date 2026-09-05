using System.Net;
using System.Security.Cryptography;
using System.Text.Json;
using ToolHelper.Agent.Application.Contracts;
using ToolHelper.Agent.Infrastructure;

namespace ToolHelper.Agent.Api.Security;

public sealed class RequestSecurityMiddleware(RequestDelegate next, RuntimeOptions options)
{
    private static readonly JsonSerializerOptions JsonOptions = new(JsonSerializerDefaults.Web);

    public async Task InvokeAsync(HttpContext context)
    {
        var traceId = GetTraceId(context.Request.Headers["X-Trace-Id"].FirstOrDefault());
        context.Items[TraceIdMiddleware.Key] = traceId;
        context.Response.Headers["X-Content-Type-Options"] = "nosniff";
        context.Response.Headers.ContentSecurityPolicy = "default-src 'none'; frame-ancestors 'none'";
        context.Response.Headers.ReferrerPolicy = "no-referrer";
        context.Response.Headers.CacheControl = "no-store";
        context.Response.Headers["X-Trace-Id"] = traceId;

        if (context.Connection.RemoteIpAddress is not { } remote || !IPAddress.IsLoopback(remote))
        {
            await RejectAsync(context, 403, "LOOPBACK_REQUIRED", "仅允许回环请求", traceId);
            return;
        }

        var origin = context.Request.Headers.Origin.FirstOrDefault();
        if (string.IsNullOrWhiteSpace(origin) || !options.AllowedOrigins.Contains(origin))
        {
            await RejectAsync(context, 403, "ORIGIN_INVALID", "请求来源不在白名单", traceId);
            return;
        }
        context.Response.Headers["Access-Control-Allow-Origin"] = origin;
        context.Response.Headers["Access-Control-Allow-Headers"] = "Authorization, Content-Type, X-Trace-Id, Last-Event-ID";
        context.Response.Headers["Access-Control-Allow-Methods"] = "GET, POST, PUT, DELETE, OPTIONS";
        context.Response.Headers["Vary"] = "Origin";
        if (HttpMethods.IsOptions(context.Request.Method))
        {
            context.Response.StatusCode = StatusCodes.Status204NoContent;
            return;
        }

        var authorization = context.Request.Headers.Authorization.FirstOrDefault();
        if (string.IsNullOrWhiteSpace(authorization))
        {
            await RejectAsync(context, 401, "AUTH_REQUIRED", "缺少服务令牌", traceId);
            return;
        }

        var presented = authorization.StartsWith("Bearer ", StringComparison.Ordinal) ? authorization[7..] : string.Empty;
        if (string.IsNullOrEmpty(presented) || !MatchesToken(presented, options.SessionToken) && !MatchesToken(presented, options.InternalToken))
        {
            await RejectAsync(context, 401, "AUTH_INVALID", "服务令牌无效", traceId);
            return;
        }

        await next(context);
    }

    private static bool MatchesToken(string presented, string expected) => CryptographicOperations.FixedTimeEquals(
        System.Text.Encoding.UTF8.GetBytes(presented), System.Text.Encoding.UTF8.GetBytes(expected));

    private static string GetTraceId(string? candidate) =>
        !string.IsNullOrWhiteSpace(candidate) && candidate.Length <= 128 && candidate.All(IsTraceChar)
            ? candidate
            : Guid.NewGuid().ToString("N");

    private static bool IsTraceChar(char value) => char.IsLetterOrDigit(value) || value is '-' or '_' or '.' or ':';

    private static async Task RejectAsync(HttpContext context, int status, string code, string message, string traceId)
    {
        context.Response.StatusCode = status;
        context.Response.ContentType = "application/json; charset=utf-8";
        await context.Response.WriteAsync(JsonSerializer.Serialize(
            new ApiResponse<object>(false, code, message, null, traceId), JsonOptions));
    }
}

public sealed class TraceIdMiddleware(RequestDelegate next)
{
    public const string Key = "toolhelper.traceId";

    public async Task InvokeAsync(HttpContext context)
    {
        context.Response.OnStarting(() =>
        {
            if (context.Items.TryGetValue(Key, out var value) && value is string traceId)
                context.Response.Headers["X-Trace-Id"] = traceId;
            return Task.CompletedTask;
        });
        await next(context);
    }
}
