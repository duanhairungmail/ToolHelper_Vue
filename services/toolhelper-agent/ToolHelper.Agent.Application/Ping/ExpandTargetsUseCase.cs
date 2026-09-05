using System.Net;
using System.Net.Sockets;
using ToolHelper.Agent.Domain.Ping;

namespace ToolHelper.Agent.Application.Ping;

public sealed class ExpandTargetsUseCase
{
    public const int MaxTargets = 4096;

    public ExpandTargetsResponse Execute(string input)
    {
        if (string.IsNullOrWhiteSpace(input)) throw new ArgumentException("检测目标不能为空", nameof(input));
        var addresses = new List<string>();
        var seen = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        var estimated = 0;
        foreach (var raw in input.Split(['\r', '\n'], StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries))
        {
            var line = raw.Trim();
            if (line.Length == 0 || line.StartsWith('#')) continue;
            var expanded = ExpandLine(line);
            estimated += line.Contains('/') ? EstimateCidrHosts(line) : expanded.Count;
            foreach (var address in expanded)
            {
                if (seen.Add(address)) addresses.Add(address);
                if (addresses.Count == MaxTargets) break;
            }
        }
        if (addresses.Count == 0) throw new ArgumentException("未找到有效的 IPv4 目标", nameof(input));
        return new ExpandTargetsResponse(addresses.Select((address, index) => new PingTarget(address, index)).ToArray(), estimated > MaxTargets, estimated);
    }

    private static IReadOnlyList<string> ExpandLine(string line)
    {
        if (line.Contains('/')) return ExpandCidr(line);
        var dash = line.LastIndexOf('-');
        if (dash > 0 && IPAddress.TryParse(line[..dash], out var start) && start.AddressFamily == AddressFamily.InterNetwork)
        {
            if (!int.TryParse(line[(dash + 1)..], out var end) || start.GetAddressBytes()[3] > end || end is < 1 or > 254)
                throw new ArgumentException("范围需在 1-254 之间");
            var prefix = start.GetAddressBytes();
            return Enumerable.Range(prefix[3], end - prefix[3] + 1).Select(value => $"{prefix[0]}.{prefix[1]}.{prefix[2]}.{value}").ToArray();
        }
        if (!IPAddress.TryParse(line, out var address) || address.AddressFamily != AddressFamily.InterNetwork)
            throw new ArgumentException($"仅支持 IPv4 目标：{line}");
        return [address.ToString()];
    }

    private static IReadOnlyList<string> ExpandCidr(string line)
    {
        var parts = line.Split('/', 2);
        if (parts.Length != 2 || !IPAddress.TryParse(parts[0], out var address) || address.AddressFamily != AddressFamily.InterNetwork)
            throw new ArgumentException("仅支持 IPv4 CIDR");
        if (!int.TryParse(parts[1], out var prefix) || prefix is < 16 or > 30)
            throw new ArgumentException("前缀长度需在 16-30 之间（避免过大网段）");
        var ip = BitConverter.ToUInt32(address.GetAddressBytes().Reverse().ToArray());
        var mask = uint.MaxValue << (32 - prefix);
        var network = ip & mask;
        var count = (long)1 << (32 - prefix);
        var hosts = Math.Max(0, count - 2);
        var result = new List<string>((int)Math.Min(hosts, MaxTargets));
        for (long offset = 1; offset <= hosts && result.Count < MaxTargets; offset++)
        {
            var value = network + (uint)offset;
            var bytes = BitConverter.GetBytes(value).Reverse().ToArray();
            result.Add(new IPAddress(bytes).ToString());
        }
        return result;
    }

    private static int EstimateCidrHosts(string line)
    {
        var parts = line.Split('/', 2);
        if (parts.Length != 2 || !IPAddress.TryParse(parts[0], out var address) || address.AddressFamily != AddressFamily.InterNetwork)
            throw new ArgumentException("仅支持 IPv4 CIDR");
        if (!int.TryParse(parts[1], out var prefix) || prefix is < 16 or > 30)
            throw new ArgumentException("前缀长度需在 16-30 之间（避免过大网段）");
        return (int)(((long)1 << (32 - prefix)) - 2);
    }
}
