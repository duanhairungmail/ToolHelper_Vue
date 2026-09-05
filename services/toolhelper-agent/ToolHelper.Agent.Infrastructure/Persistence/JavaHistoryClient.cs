using System.Net.Http.Json;
using System.Text.Json;
using System.Text.Json.Serialization;
using ToolHelper.Agent.Application.Ping;
using ToolHelper.Agent.Domain.Ping;

namespace ToolHelper.Agent.Infrastructure.Persistence;

public sealed class JavaHistoryClient(RuntimeOptions options) : IPingHistoryClient
{
    private static readonly JsonSerializerOptions JsonOptions = new(JsonSerializerDefaults.Web);
    private readonly string pendingRoot = Path.Combine(AppContext.BaseDirectory, "pending-submissions");
    private readonly HttpClient client = CreateClient(options);
    private readonly SemaphoreSlim gate = new(1, 1);

    public async Task SubmitAsync(PingJob job, CancellationToken cancellationToken)
    {
        var payload = new HistoryPayload(job.Id, job.Status.ToString().ToUpperInvariant(), job.Summary, job.Results.Select(MapResult).ToArray());
        Directory.CreateDirectory(pendingRoot);
        var pending = Path.Combine(pendingRoot, job.Id + ".json");
        await gate.WaitAsync(cancellationToken);
        try
        {
            for (var attempt = 0; attempt < 5; attempt++)
            {
                if (await TrySendAsync(payload, cancellationToken)) { File.Delete(pending); await ReplayPendingCoreAsync(cancellationToken); return; }
                if (attempt < 4) await Task.Delay(TimeSpan.FromSeconds(new[] { 1, 2, 5, 10, 30 }[attempt]), cancellationToken);
            }
            await WritePendingAsync(pending, payload, cancellationToken);
        }
        finally { gate.Release(); }
    }

    public async Task ReplayPendingAsync(CancellationToken cancellationToken)
    {
        await gate.WaitAsync(cancellationToken);
        try { await ReplayPendingCoreAsync(cancellationToken); }
        finally { gate.Release(); }
    }

    private async Task ReplayPendingCoreAsync(CancellationToken cancellationToken)
    {
        if (!Directory.Exists(pendingRoot)) return;
        foreach (var file in Directory.EnumerateFiles(pendingRoot, "*.json"))
        {
            try
            {
                var json = await File.ReadAllTextAsync(file, cancellationToken);
                var payload = JsonSerializer.Deserialize<HistoryPayload>(json, JsonOptions);
                if (payload is not null && await TrySendAsync(payload, cancellationToken)) File.Delete(file);
            }
            catch (Exception error)
            {
                Console.Error.WriteLine($"待提交历史处理失败，文件 {file}: {error.Message}");
            }
        }
    }

    private async Task<bool> TrySendAsync(HistoryPayload payload, CancellationToken cancellationToken)
    {
        try
        {
            using var request = new HttpRequestMessage(HttpMethod.Put, $"/api/internal/v1/ping-jobs/{payload.JobId}") { Content = JsonContent.Create(payload) };
            request.Headers.Authorization = new("Bearer", options.InternalToken);
            request.Headers.TryAddWithoutValidation("Origin", options.AllowedOrigins.FirstOrDefault() ?? "http://127.0.0.1:5173");
            using var response = await client.SendAsync(request, cancellationToken);
            if (!response.IsSuccessStatusCode)
                Console.Error.WriteLine($"Java 历史提交失败，状态码 {(int)response.StatusCode}，任务 {payload.JobId}");
            return response.IsSuccessStatusCode;
        }
        catch (HttpRequestException error)
        {
            Console.Error.WriteLine($"Java 历史提交不可用，任务 {payload.JobId}: {error.Message}");
            return false;
        }
    }

    private static async Task WritePendingAsync(string path, HistoryPayload payload, CancellationToken cancellationToken)
    {
        var temporary = path + ".tmp";
        await File.WriteAllTextAsync(temporary, JsonSerializer.Serialize(payload, JsonOptions), cancellationToken);
        File.Move(temporary, path, true);
    }

    private static HttpClient CreateClient(RuntimeOptions options) => new() { BaseAddress = new Uri(options.JavaApiBase) };

    private static HistoryResult MapResult(PingTargetResult result) => new(
        result.Address,
        result.InputIndex,
        result.CompletionIndex,
        result.Status switch
        {
            PingStatusCode.Online => "ONLINE",
            PingStatusCode.PartialLoss => "PARTIAL_LOSS",
            _ => "OFFLINE"
        },
        result.Attempts,
        result.SuccessCount,
        result.AverageDelayMs,
        result.PacketLossPercent,
        result.Error);

    public sealed record HistoryPayload(string JobId, string Status, PingSummary Summary, IReadOnlyList<HistoryResult> Results);
    public sealed record HistoryResult(string Address, int InputIndex, int CompletionIndex, string Status, int Attempts, int SuccessCount, int? AverageDelayMs, int PacketLossPercent, string? Error);
}
