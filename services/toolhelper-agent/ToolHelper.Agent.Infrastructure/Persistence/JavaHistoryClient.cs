using System.Net.Http.Json;
using System.Text.Json;
using System.Text.Json.Serialization;
using ToolHelper.Agent.Application.Ping;
using ToolHelper.Agent.Domain.Ping;

namespace ToolHelper.Agent.Infrastructure.Persistence;

public sealed class JavaHistoryClient(RuntimeOptions options) : IPingHistoryClient
{
    private readonly string pendingRoot = Path.Combine(AppContext.BaseDirectory, "pending-submissions");
    private readonly HttpClient client = CreateClient(options);
    private readonly SemaphoreSlim gate = new(1, 1);

    public async Task SubmitAsync(PingJob job, CancellationToken cancellationToken)
    {
        var payload = new HistoryPayload(job.Id, job.Status.ToString().ToUpperInvariant(), job.Summary, job.Results.ToArray());
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
                await using var stream = File.OpenRead(file);
                var payload = await JsonSerializer.DeserializeAsync<HistoryPayload>(stream, new JsonSerializerOptions(JsonSerializerDefaults.Web), cancellationToken);
                if (payload is not null && await TrySendAsync(payload, cancellationToken)) File.Delete(file);
            }
            catch (Exception) { /* 单个文件失败不阻塞其它待提交任务 */ }
        }
    }

    private async Task<bool> TrySendAsync(HistoryPayload payload, CancellationToken cancellationToken)
    {
        try
        {
            using var request = new HttpRequestMessage(HttpMethod.Put, $"/api/internal/v1/ping-jobs/{payload.JobId}") { Content = JsonContent.Create(payload) };
            request.Headers.Authorization = new("Bearer", options.InternalToken);
            using var response = await client.SendAsync(request, cancellationToken);
            return response.IsSuccessStatusCode;
        }
        catch (HttpRequestException) { return false; }
    }

    private static async Task WritePendingAsync(string path, HistoryPayload payload, CancellationToken cancellationToken)
    {
        var temporary = path + ".tmp";
        await File.WriteAllTextAsync(temporary, JsonSerializer.Serialize(payload), cancellationToken);
        File.Move(temporary, path, true);
    }

    private static HttpClient CreateClient(RuntimeOptions options) => new() { BaseAddress = new Uri(options.JavaApiBase) };

    public sealed record HistoryPayload(string JobId, string Status, PingSummary Summary, IReadOnlyList<PingTargetResult> Results);
}
