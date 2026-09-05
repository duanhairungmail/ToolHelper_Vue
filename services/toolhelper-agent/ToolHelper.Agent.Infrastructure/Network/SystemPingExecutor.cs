using System.Net.NetworkInformation;
using ToolHelper.Agent.Application.Ping;

namespace ToolHelper.Agent.Infrastructure.Network;

public sealed class SystemPingExecutor : IPingExecutor
{
    public async Task<(bool Success, int? DelayMs, string? Error)> SendAsync(string address, int timeoutMs, CancellationToken cancellationToken)
    {
        using var ping = new Ping();
        try
        {
            var reply = await ping.SendPingAsync(address, timeoutMs).WaitAsync(cancellationToken);
            return (reply.Status == IPStatus.Success, reply.Status == IPStatus.Success ? (int)reply.RoundtripTime : null, reply.Status.ToString());
        }
        catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested) { throw; }
        catch (Exception error) { return (false, null, error.GetType().Name); }
    }
}
