using System.Collections.Concurrent;
using System.Threading.Channels;
using ToolHelper.Agent.Application.Contracts;
using ToolHelper.Agent.Domain.Ping;

namespace ToolHelper.Agent.Application.Ping;

public sealed class PingJobManager(ExpandTargetsUseCase expander, IPingExecutor executor, IPingHistoryClient history,
    IEnumerable<IPingExporter> exporters)
{
    private readonly IPingExporter csvExporter = exporters.Single(item => item.Extension == "csv");
    private readonly IPingExporter xlsxExporter = exporters.Single(item => item.Extension == "xlsx");
    private readonly ConcurrentDictionary<string, PingJob> jobs = new(StringComparer.Ordinal);
    private readonly ConcurrentDictionary<string, Channel<SseEvent>> streams = new(StringComparer.Ordinal);

    public ExpandTargetsResponse Expand(string input) => expander.Execute(input);

    public StartPingResponse Start(StartPingRequest request, string traceId)
    {
        var expanded = expander.Execute(request.Input);
        var options = new PingOptions(request.Concurrency ?? 50, request.Count ?? 4, request.TimeoutMs ?? 1000);
        Validate(options);
        var job = new PingJob { Id = Guid.NewGuid().ToString("N"), Input = request.Input, CreatedAt = DateTimeOffset.UtcNow, Targets = expanded.Targets, Options = options };
        jobs[job.Id] = job;
        streams[job.Id] = Channel.CreateUnbounded<SseEvent>();
        _ = RunAsync(job, traceId);
        return new StartPingResponse(job.Id, expanded.Targets.Count, expanded.Truncated, expanded.EstimatedTotal);
    }

    public PingJob Require(string id) => jobs.TryGetValue(id, out var job) ? job : throw new KeyNotFoundException("群 Ping 任务不存在");

    public void Cancel(string id)
    {
        var job = Require(id);
        if (job.Status == PingJobStatus.Running) job.Cancellation.Cancel();
    }

    public ChannelReader<SseEvent> Events(string id, string? lastEventId)
    {
        Require(id);
        return streams[id].Reader;
    }

    public (byte[] Content, string ContentType, string FileName) Export(string id, string format)
    {
        var job = Require(id);
        var exporter = format.Equals("xlsx", StringComparison.OrdinalIgnoreCase) ? xlsxExporter : csvExporter;
        return (exporter.Export(job), exporter.ContentType, $"toolhelper-ping-{id}.{exporter.Extension}");
    }

    private async Task RunAsync(PingJob job, string traceId)
    {
        try
        {
            using var gate = new SemaphoreSlim(job.Options.Concurrency);
            var completion = 0;
            var tasks = job.Targets.Select(async target =>
            {
                if (job.Cancellation.IsCancellationRequested) return;
                await gate.WaitAsync(job.Cancellation.Token);
                try
                {
                    if (job.Cancellation.IsCancellationRequested) return;
                    var attempts = 0; var success = 0; var delays = new List<int>(); string? error = null;
                    for (var round = 0; round < job.Options.Count && !job.Cancellation.IsCancellationRequested; round++)
                    {
                        attempts++;
                        var result = await executor.SendAsync(target.Address, job.Options.TimeoutMs, job.Cancellation.Token);
                        if (result.Success) { success++; if (result.DelayMs is { } delay) delays.Add(delay); }
                        else error = result.Error;
                    }
                    var status = success == attempts ? PingStatusCode.Online : success > 0 ? PingStatusCode.PartialLoss : PingStatusCode.Offline;
                    var item = new PingTargetResult(target.Address, target.InputIndex, Interlocked.Increment(ref completion), status, attempts, success, delays.Count == 0 ? null : delays.Sum() / delays.Count, attempts == 0 ? 100 : (attempts - success) * 100 / attempts, error);
                    lock (job.Results) job.Results.Add(item);
                    Publish(job, "ping.result", traceId, item);
                }
                finally { gate.Release(); }
            }).ToArray();
            await Task.WhenAll(tasks);
            job.Status = job.Cancellation.IsCancellationRequested ? PingJobStatus.Cancelled : PingJobStatus.Completed;
        }
        catch (OperationCanceledException) when (job.Cancellation.IsCancellationRequested)
        {
            job.Status = PingJobStatus.Cancelled;
        }
        catch (Exception error)
        {
            job.Status = PingJobStatus.Failed;
            Publish(job, "ping.error", traceId, new { message = error.Message });
        }
        finally
        {
            lock (job.Results)
            {
                var results = job.Results.ToArray();
                var delays = results.Where(item => item.AverageDelayMs.HasValue).Select(item => item.AverageDelayMs!.Value).ToArray();
                job.Summary = new PingSummary(job.Targets.Count, results.Count(item => item.Status == PingStatusCode.Online), results.Count(item => item.Status == PingStatusCode.Offline), results.Count(item => item.Status == PingStatusCode.PartialLoss), delays.Length == 0 ? null : delays.Sum() / delays.Length);
            }
            job.CompletedAt = DateTimeOffset.UtcNow;
            Publish(job, "ping.summary", traceId, job.Summary);
            try { await history.SubmitAsync(job, CancellationToken.None); } catch { /* 持久化客户端负责离线落盘 */ }
            streams[job.Id].Writer.TryComplete();
        }
    }

    private void Publish(PingJob job, string type, string traceId, object payload)
    {
        streams[job.Id].Writer.TryWrite(new SseEvent(Guid.NewGuid().ToString("N"), type, job.Id, DateTimeOffset.UtcNow, traceId, new Dictionary<string, object?> { ["data"] = payload, ["message"] = type == "ping.summary" ? "群 Ping 任务已完成" : type }));
    }

    private static void Validate(PingOptions options)
    {
        if (options.Concurrency is < 1 or > 200) throw new ArgumentException("并发数需在 1-200 之间");
        if (options.Count is < 1 or > 10) throw new ArgumentException("检测次数需在 1-10 之间");
        if (options.TimeoutMs is < 100 or > 10000) throw new ArgumentException("超时需在 100-10000ms 之间");
    }
}
