using ToolHelper.Agent.Application.Contracts;

var response = new ApiResponse<string>(true, "OK", "ok", "data", "trace-1");
if (!response.Success || response.Code != "OK" || response.TraceId != "trace-1")
    throw new InvalidOperationException("ApiResponse contract smoke test failed");
Console.WriteLine("C# contract smoke test passed");
