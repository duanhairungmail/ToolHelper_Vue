using System.Net.Http.Json;
using System.Text.Json;

namespace ToolHelper.Agent.Infrastructure.Network;

/// <summary>物理串口打开前必须取得Java租约，关闭设备句柄之后才释放；不得静默抢占。</summary>
public sealed class SerialPortLeaseClient(RuntimeOptions options) : IDisposable
{
    private readonly HttpClient client = new(new HttpClientHandler { UseProxy = false }) { BaseAddress = new Uri(options.JavaApiBase), Timeout = TimeSpan.FromSeconds(5) };
    private readonly Dictionary<string, Lease> held = new(StringComparer.Ordinal);
    private readonly SemaphoreSlim gate = new(1, 1);
    private static readonly JsonSerializerOptions JsonOptions = new(JsonSerializerDefaults.Web);
    public sealed record Lease(string Id, string Port, string Owner, long Pid, string ReleaseHint);

    public async Task<Lease> AcquireAsync(string port, CancellationToken cancellationToken)
    {
        await gate.WaitAsync(cancellationToken);
        try
        {
            using var request = Request(HttpMethod.Post, "/api/internal/v1/serial/leases");
            request.Content = JsonContent.Create(new { port, pid = Environment.ProcessId });
            using var response = await client.SendAsync(request, cancellationToken);
            var payload = await response.Content.ReadFromJsonAsync<JsonElement>(cancellationToken);
            if (!response.IsSuccessStatusCode || !payload.GetProperty("success").GetBoolean())
                throw new SerialPortLeaseException(payload.GetProperty("code").GetString() ?? "SERIAL_COORDINATION_FAILED", payload.GetProperty("message").GetString() ?? "Java串口协调服务拒绝打开串口");
            var lease = payload.GetProperty("data").Deserialize<Lease>(JsonOptions) ?? throw new InvalidOperationException("串口租约响应无效");
            held.Add(lease.Id, lease);
            Console.WriteLine($"串口租约取得：{lease.Port}，占用方 C# Agent");
            return lease;
        }
        finally { gate.Release(); }
    }

    public async Task ReleaseAsync(string id, CancellationToken cancellationToken)
    {
        await gate.WaitAsync(cancellationToken);
        try
        {
            if (!held.TryGetValue(id, out var lease)) throw new KeyNotFoundException("未找到本服务的串口租约");
            using var request = Request(HttpMethod.Delete, "/api/internal/v1/serial/leases/" + Uri.EscapeDataString(id));
            using var response = await client.SendAsync(request, cancellationToken);
            if (!response.IsSuccessStatusCode) throw new InvalidOperationException("Java串口租约释放失败，请保持设备关闭并重试");
            held.Remove(id);
            Console.WriteLine($"串口租约释放：{lease.Port}");
        }
        finally { gate.Release(); }
    }
    private HttpRequestMessage Request(HttpMethod method, string path)
    {
        var request = new HttpRequestMessage(method, path);
        request.Headers.Authorization = new("Bearer", options.InternalToken);
        request.Headers.TryAddWithoutValidation("Origin", options.AllowedOrigins.First());
        return request;
    }
    public void Dispose() { client.Dispose(); gate.Dispose(); }
}

public sealed class SerialPortLeaseException(string code, string message) : Exception(message)
{
    public string Code { get; } = code;
}
