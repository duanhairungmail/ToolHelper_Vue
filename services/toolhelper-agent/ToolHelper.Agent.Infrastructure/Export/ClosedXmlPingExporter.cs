using System.IO.Compression;
using System.Security;
using System.Text;
using ToolHelper.Agent.Application.Ping;
using ToolHelper.Agent.Domain.Ping;

namespace ToolHelper.Agent.Infrastructure.Export;

// 使用标准 Open XML 最小包生成 XLSX，保持部署无额外运行时依赖。
public sealed class ClosedXmlPingExporter : IPingExporter
{
    public string ContentType => "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    public string Extension => "xlsx";

    public byte[] Export(PingJob job)
    {
        using var output = new MemoryStream();
        using (var zip = new ZipArchive(output, ZipArchiveMode.Create, true))
        {
            Write(zip, "[Content_Types].xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/><Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/></Types>");
            Write(zip, "_rels/.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>");
            Write(zip, "xl/_rels/workbook.xml.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/></Relationships>");
            Write(zip, "xl/workbook.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?><workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets><sheet name=\"Ping\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>");
            var rows = new List<string[]> { new[] { "目标", "状态", "平均延迟(ms)", "丢包率(%)", "输入序号", "完成序号", "任务状态" } };
            lock (job.Results)
                rows.AddRange(job.Results.OrderBy(item => item.CompletionIndex).Select(result => new[] { result.Address, result.Status switch { PingStatusCode.Online => "在线", PingStatusCode.PartialLoss => "部分丢包", _ => "超时/失败" }, result.AverageDelayMs?.ToString() ?? string.Empty, result.PacketLossPercent.ToString(), result.InputIndex.ToString(), result.CompletionIndex.ToString(), job.Status.ToString().ToUpperInvariant() }));
            var xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?><worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>");
            for (var rowIndex = 0; rowIndex < rows.Count; rowIndex++)
            {
                xml.Append($"<row r=\"{rowIndex + 1}\">");
                for (var col = 0; col < rows[rowIndex].Length; col++)
                {
                    var value = SecurityElement.Escape(rows[rowIndex][col]) ?? string.Empty;
                    var reference = $"{(char)('A' + col)}{rowIndex + 1}";
                    var numeric = rowIndex > 0 && col >= 2 && col <= 5;
                    xml.Append($"<c r=\"{reference}\"{(numeric ? "" : " t=\"inlineStr\"") }>");
                    xml.Append(numeric ? $"<v>{value}</v>" : $"<is><t>{value}</t></is>");
                    xml.Append("</c>");
                }
                xml.Append("</row>");
            }
            xml.Append("</sheetData></worksheet>");
            Write(zip, "xl/worksheets/sheet1.xml", xml.ToString());
        }
        return output.ToArray();
    }

    private static void Write(ZipArchive zip, string name, string content)
    {
        using var writer = new StreamWriter(zip.CreateEntry(name).Open(), new UTF8Encoding(false));
        writer.Write(content);
    }
}
