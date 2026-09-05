using ToolHelper.Agent.Infrastructure.Persistence;

namespace ToolHelper.Agent.Api.Persistence;

public sealed class PendingSubmissionWorker(JavaHistoryClient history) : BackgroundService
{
    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        while (!stoppingToken.IsCancellationRequested)
        {
            try { await history.ReplayPendingAsync(stoppingToken); }
            catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested) { break; }
            catch { /* 网络恢复前保持后台循环 */ }
            await Task.Delay(TimeSpan.FromSeconds(5), stoppingToken);
        }
    }
}
