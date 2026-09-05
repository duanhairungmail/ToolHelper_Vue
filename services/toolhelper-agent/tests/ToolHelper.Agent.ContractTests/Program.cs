using System.Net;
using System.Net.NetworkInformation;
using System.Text;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Hosting;
using Microsoft.Extensions.DependencyInjection;
using ToolHelper.Agent.Api.Security;
using ToolHelper.Agent.Application.Contracts;
using ToolHelper.Agent.Infrastructure;
using ToolHelper.Agent.Application.Ping;
using ToolHelper.Agent.Domain.Ping;
using ToolHelper.Agent.Infrastructure.Export;
using System.IO.Compression;
using AgentPingOptions = ToolHelper.Agent.Domain.Ping.PingOptions;

var response = new ApiResponse<string>(true, "OK", "ok", "data", "trace-1");
if (!response.Success || response.Code != "OK" || response.TraceId != "trace-1")
    throw new InvalidOperationException("ApiResponse contract smoke test failed");

var expander = new ExpandTargetsUseCase();
if (expander.Execute("192.168.1.10").Targets.Count != 1 || expander.Execute("192.168.1.1-254").Targets.Count != 254)
    throw new InvalidOperationException("Ping single/range expansion contract failed");
var cidr30 = expander.Execute("192.168.1.0/30");
if (cidr30.Targets.Count != 2 || cidr30.Targets[0].Address != "192.168.1.1" || cidr30.Targets[1].Address != "192.168.1.2")
    throw new InvalidOperationException("Ping CIDR /30 expansion contract failed");
var cidr24 = expander.Execute("192.168.1.0/24");
if (cidr24.Targets.Count != 254 || cidr24.Targets[0].Address != "192.168.1.1" || cidr24.Targets[^1].Address != "192.168.1.254")
    throw new InvalidOperationException("Ping CIDR /24 expansion contract failed");
var cidr16 = expander.Execute("10.0.0.0/16");
if (cidr16.Targets.Count != 4096 || !cidr16.Truncated || cidr16.EstimatedTotal != 65534)
    throw new InvalidOperationException("Ping CIDR cap contract failed");
try { expander.Execute("192.168.1.0/15"); throw new InvalidOperationException("Ping invalid prefix was accepted"); }
catch (ArgumentException) { }
try { expander.Execute("2001:db8::/64"); throw new InvalidOperationException("Ping IPv6 was accepted"); }
catch (ArgumentException) { }
try { expander.Execute("192.168.1.254-1"); throw new InvalidOperationException("Ping invalid range was accepted"); }
catch (ArgumentException) { }
var imported = expander.Execute("# comment\n192.168.1.1\n192.168.1.1\n192.168.1.2");
if (imported.Targets.Count != 2 || imported.Targets[1].InputIndex != 1)
    throw new InvalidOperationException("Ping text import contract failed");

var csv = new CsvPingExporter();
var xlsx = new ClosedXmlPingExporter();
var exportJob = new PingJob { Id = "export-test", Input = "=SUM(A1)", CreatedAt = DateTimeOffset.UtcNow, Targets = [], Options = AgentPingOptions.Default, Status = PingJobStatus.Cancelled };
exportJob.Results.Add(new PingTargetResult("=SUM(A1)", 0, 1, PingStatusCode.Offline, 1, 0, null, 100, null));
var csvBytes = csv.Export(exportJob);
var csvText = Encoding.UTF8.GetString(csvBytes);
if (!csvBytes.AsSpan(0, 3).SequenceEqual(Encoding.UTF8.GetPreamble()) || !csvText.Contains("'=SUM(A1)", StringComparison.Ordinal) || !csvText.Contains("CANCELLED", StringComparison.Ordinal))
    throw new InvalidOperationException("Ping CSV export contract failed");
using (var xlsxStream = new MemoryStream(xlsx.Export(exportJob)))
using (var archive = new ZipArchive(xlsxStream, ZipArchiveMode.Read))
{
    var sheet = archive.GetEntry("xl/worksheets/sheet1.xml");
    if (sheet is null || !new StreamReader(sheet.Open()).ReadToEnd().Contains("inlineStr", StringComparison.Ordinal))
        throw new InvalidOperationException("Ping XLSX export contract failed");
}

var manager = new PingJobManager(expander, new ScriptedPingExecutor(), new NoopHistoryClient(), new IPingExporter[] { csv, xlsx });
var startedJob = manager.Start(new StartPingRequest("192.0.2.1-2", 2, 2, 100), "trace-ping");
var deadline = DateTimeOffset.UtcNow.AddSeconds(3);
while (manager.Require(startedJob.JobId).Status == PingJobStatus.Running && DateTimeOffset.UtcNow < deadline)
    await Task.Delay(20);
var completedJob = manager.Require(startedJob.JobId);
if (completedJob.Status != PingJobStatus.Completed || completedJob.Summary.Online != 1 || completedJob.Summary.PartialLoss != 1 || completedJob.Results.Count != 2)
    throw new InvalidOperationException("Ping execution summary contract failed");

var cancellable = new PingJobManager(expander, new SlowPingExecutor(), new NoopHistoryClient(), new IPingExporter[] { csv, xlsx });
var cancelJob = cancellable.Start(new StartPingRequest("192.0.2.1-254", 1, 10, 100), "trace-cancel");
cancellable.Cancel(cancelJob.JobId);
deadline = DateTimeOffset.UtcNow.AddSeconds(3);
while (cancellable.Require(cancelJob.JobId).Status == PingJobStatus.Running && DateTimeOffset.UtcNow < deadline)
    await Task.Delay(20);
if (cancellable.Require(cancelJob.JobId).Status != PingJobStatus.Cancelled)
    throw new InvalidOperationException("Ping cancellation contract failed");

var context = new DefaultHttpContext();
context.Connection.RemoteIpAddress = IPAddress.Parse("192.0.2.10");
context.Response.Body = new MemoryStream();
var options = new RuntimeOptions
{
    SessionToken = "session-token",
    InternalToken = "internal-token",
    AllowedOrigins = new HashSet<string>(StringComparer.Ordinal) { "http://127.0.0.1:5173" }
};
await new RequestSecurityMiddleware(_ => Task.CompletedTask, options).InvokeAsync(context);
context.Response.Body.Position = 0;
var body = await new StreamReader(context.Response.Body, Encoding.UTF8).ReadToEndAsync();
if (context.Response.StatusCode != StatusCodes.Status403Forbidden || !body.Contains("LOOPBACK_REQUIRED", StringComparison.Ordinal))
    throw new InvalidOperationException("C# non-loopback request rejection smoke test failed");

var nonLoopbackAddress = NetworkInterface.GetAllNetworkInterfaces()
    .Where(network => network.OperationalStatus == OperationalStatus.Up)
    .SelectMany(network => network.GetIPProperties().UnicastAddresses)
    .Select(address => address.Address)
    .FirstOrDefault(address => address.AddressFamily == System.Net.Sockets.AddressFamily.InterNetwork && !IPAddress.IsLoopback(address));
if (nonLoopbackAddress is null)
    throw new InvalidOperationException("C# non-loopback HTTP smoke test requires an active non-loopback IPv4 address");

var webBuilder = WebApplication.CreateBuilder();
webBuilder.WebHost.UseUrls("http://0.0.0.0:0");
webBuilder.Services.AddSingleton(options);
var webApp = webBuilder.Build();
webApp.UseMiddleware<RequestSecurityMiddleware>();
webApp.MapGet("/health", () => Results.Ok());
await webApp.StartAsync();
try
{
    var boundPort = new Uri(webApp.Urls.Single()).Port;
    using var client = new HttpClient(new SocketsHttpHandler { UseProxy = false });
    client.DefaultRequestHeaders.Add("Origin", "http://127.0.0.1:5173");
    using var result = await client.GetAsync($"http://{nonLoopbackAddress}:{boundPort}/health");
    var realBody = await result.Content.ReadAsStringAsync();
    if (result.StatusCode != HttpStatusCode.Forbidden || !realBody.Contains("LOOPBACK_REQUIRED", StringComparison.Ordinal))
        throw new InvalidOperationException("C# non-loopback HTTP request rejection smoke test failed");
}
finally
{
    await webApp.StopAsync();
    await webApp.DisposeAsync();
}

Console.WriteLine("C# contract smoke test passed");

sealed class ScriptedPingExecutor : IPingExecutor
{
    private readonly Dictionary<string, int> calls = new();
    public Task<(bool Success, int? DelayMs, string? Error)> SendAsync(string address, int timeoutMs, CancellationToken cancellationToken)
    {
        calls.TryGetValue(address, out var count); calls[address] = count + 1;
        var success = address.EndsWith(".1", StringComparison.Ordinal) || count == 1;
        return Task.FromResult<(bool Success, int? DelayMs, string? Error)>((success, success ? 12 : null, success ? null : "TimedOut"));
    }
}

sealed class SlowPingExecutor : IPingExecutor
{
    public async Task<(bool Success, int? DelayMs, string? Error)> SendAsync(string address, int timeoutMs, CancellationToken cancellationToken)
    {
        await Task.Delay(200, cancellationToken);
        return (true, 1, null);
    }
}

sealed class NoopHistoryClient : IPingHistoryClient
{
    public Task SubmitAsync(PingJob job, CancellationToken cancellationToken) => Task.CompletedTask;
}
