using System.Text;
using ToolHelper.Agent.Application.Ping;
using ToolHelper.Agent.Domain.Ping;

namespace ToolHelper.Agent.Infrastructure.Export;

public sealed class CsvPingExporter : IPingExporter
{
    public string ContentType => "text/csv; charset=utf-8";
    public string Extension => "csv";

    public byte[] Export(PingJob job)
    {
        var builder = new StringBuilder("目标,状态,平均延迟(ms),丢包率(%),输入序号,完成序号,任务状态\r\n");
        lock (job.Results)
        foreach (var result in job.Results.OrderBy(item => item.CompletionIndex))
            builder.Append(string.Join(',', Escape(result.Address), Escape(result.Status switch { PingStatusCode.Online => "在线", PingStatusCode.PartialLoss => "部分丢包", _ => "超时/失败" }), result.AverageDelayMs?.ToString() ?? string.Empty, result.PacketLossPercent, result.InputIndex, result.CompletionIndex, job.Status.ToString().ToUpperInvariant())).Append("\r\n");
        return Encoding.UTF8.GetPreamble().Concat(Encoding.UTF8.GetBytes(builder.ToString())).ToArray();
    }

    private static string Escape(string value)
    {
        // Excel 公式注入防护：文本单元格以前缀单引号开头。
        if (value.Length > 0 && value[0] is '=' or '+' or '-' or '@') value = "'" + value;
        return value.IndexOfAny(new[] { ',', '"', '\r', '\n' }) >= 0 ? $"\"{value.Replace("\"", "\"\"")}\"" : value;
    }
}
