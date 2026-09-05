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

var response = new ApiResponse<string>(true, "OK", "ok", "data", "trace-1");
if (!response.Success || response.Code != "OK" || response.TraceId != "trace-1")
    throw new InvalidOperationException("ApiResponse contract smoke test failed");

var expander = new ExpandTargetsUseCase();
if (expander.Execute("192.168.1.10").Targets.Count != 1 || expander.Execute("192.168.1.1-254").Targets.Count != 254)
    throw new InvalidOperationException("Ping single/range expansion contract failed");
var cidr30 = expander.Execute("192.168.1.0/30");
if (cidr30.Targets.Count != 2 || cidr30.Targets[0].Address != "192.168.1.1" || cidr30.Targets[1].Address != "192.168.1.2")
    throw new InvalidOperationException("Ping CIDR /30 expansion contract failed");
var cidr16 = expander.Execute("10.0.0.0/16");
if (cidr16.Targets.Count != 4096 || !cidr16.Truncated || cidr16.EstimatedTotal != 65534)
    throw new InvalidOperationException("Ping CIDR cap contract failed");
try { expander.Execute("192.168.1.0/15"); throw new InvalidOperationException("Ping invalid prefix was accepted"); }
catch (ArgumentException) { }

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
