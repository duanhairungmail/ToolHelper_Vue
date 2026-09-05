using System.Net.Http.Json;
using System.Text.Json.Serialization;
using ToolHelper.Agent.Application.Ping;
using ToolHelper.Agent.Domain.Ping;

namespace ToolHelper.Agent.Infrastructure.Persistence;

public sealed class JavaHistoryClient(RuntimeOptions options) : IPingHistoryClient
{
    private readonly string pendingRoot = Path.Combine(AppContext.BaseDirectory, "pending-submissions");
    private readonly HttpClient client = CreateClient(options);

    public async Task SubmitAsync(PingJob job, CancellationToken cancellationToken)
    {
        var payload = new HistoryPayload(job.Id, job.Status.ToString().ToUpperInvariant(), job.Summary, job.Results.ToArray());
        Directory.CreateDirectory(pendingRoot);
        var pending = Path.Combine(pendingRoot, job.Id + ".json");
        for (var attempt = 0; attempt < 5; attempt++)
        {
            try
            {
                using var request = new HttpRequestMessage(HttpMethod.Put, $"/api/internal/v1/ping-jobs/{job.Id}") { Content = JsonContent.Create(payload) };
                request.Headers.Authorization = new("Bearer", options.InternalToken);
                using var response = await client.SendAsync(request, cancellationToken);
                if (response.IsSuccessStatusCode) { File.Delete(pending); return; }
            }
            catch (HttpRequestException) { }
            if (attempt < 4) await Task.Delay(TimeSpan.FromSeconds(new[] { 1, 2, 5, 10, 30 }[attempt]), cancellationToken);
        }
        var temporary = pending + ".tmp";
        await File.WriteAllTextAsync(temporary, System.Text.Json.JsonSerializer.Serialize(payload), cancellationToken);
        File.Move(temporary, pending, true);
    }

    private static HttpClient CreateClient(RuntimeOptions options) => new() { BaseAddress = new Uri(options.JavaApiBase) };

    private sealed record HistoryPayload(string JobId, string Status, PingSummary Summary, IReadOnlyList<PingTargetResult> Results);
}
