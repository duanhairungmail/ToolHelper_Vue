package com.toolhelper.application.crypto;

import com.toolhelper.domain.crypto.AesMode;
import com.toolhelper.domain.crypto.CompatibilityProfile;
import com.toolhelper.domain.crypto.PaddingMode;

import java.nio.charset.Charset;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;

/** TOOLHELPER_V3 的 AES 参数归一化、编码和填充边界集中在此处。 */
public final class AesService {
    public static final int MAX_INPUT_BYTES = 1024 * 1024;
    private static final SecureRandom RANDOM = new SecureRandom();
    private final AesCipherAdapter cipher;

    public AesService(AesCipherAdapter cipher) {
        this.cipher = cipher;
    }

    public AesContracts.AesResult encrypt(AesContracts.AesRequest request) {
        return execute(request, true);
    }

    public AesContracts.AesResult decrypt(AesContracts.AesRequest request) {
        return execute(request, false);
    }

    private AesContracts.AesResult execute(AesContracts.AesRequest request, boolean encrypt) {
        validateRequest(request);
        Charset charset = charset(request.charset());
        byte[] key = null;
        byte[] iv = null;
        byte[] input = null;
        byte[] transformed = null;
        byte[] plain = null;
        byte[] rawKey = null;
        byte[] rawIv = null;
        try {
            rawKey = decodeParameter(request.key(), request.keyFormat(), charset, "Key");
            key = normalizeKey(rawKey, request.keySizeBits());
            AesMode mode = request.mode();
            rawIv = mode == AesMode.ECB ? new byte[0] : decodeParameter(request.iv(), request.ivFormat(), charset, "IV");
            iv = normalizeIv(rawIv, mode == AesMode.GCM ? 12 : 16, mode == AesMode.ECB);
            input = decodeInput(request.input(), request.inputFormat(), charset, encrypt);
            checkLimit(input, "解码后的输入");

            PaddingMode effectivePadding = mode == AesMode.CTR || mode == AesMode.GCM ? PaddingMode.NONE : request.padding();
            if (mode == AesMode.GCM) {
                if (encrypt) {
                    transformed = cipher.transform(mode, true, key, iv, input);
                    byte[] ciphertext = new byte[transformed.length - 16];
                    byte[] tag = new byte[16];
                    System.arraycopy(transformed, 0, ciphertext, 0, ciphertext.length);
                    System.arraycopy(transformed, ciphertext.length, tag, 0, tag.length);
                    byte[] combined = new byte[iv.length + tag.length + ciphertext.length];
                    System.arraycopy(iv, 0, combined, 0, iv.length);
                    System.arraycopy(tag, 0, combined, iv.length, tag.length);
                    System.arraycopy(ciphertext, 0, combined, iv.length + tag.length, ciphertext.length);
                    String output = encode(combined, request.outputFormat());
                    clear(combined, ciphertext, tag);
                    return new AesContracts.AesResult(output, mode, effectivePadding, normalizeFormat(request.outputFormat()));
                }
                if (input.length < 28) throw new AesOperationException("AES_INPUT_INVALID", "GCM 密文必须包含 12 字节 nonce、16 字节 tag 和密文");
                iv = copyOfRange(input, 0, 12);
                byte[] tag = copyOfRange(input, 12, 28);
                byte[] ciphertext = copyOfRange(input, 28, input.length);
                byte[] cipherAndTag = new byte[ciphertext.length + tag.length];
                System.arraycopy(ciphertext, 0, cipherAndTag, 0, ciphertext.length);
                System.arraycopy(tag, 0, cipherAndTag, ciphertext.length, tag.length);
                plain = cipher.transform(mode, false, key, iv, cipherAndTag);
                String output = charset.decode(java.nio.ByteBuffer.wrap(plain)).toString();
                clear(tag, ciphertext, cipherAndTag);
                return new AesContracts.AesResult(output, mode, effectivePadding, "TEXT");
            }

            if (encrypt) {
                byte[] padded = pad(input, effectivePadding, mode);
                transformed = cipher.transform(mode, true, key, iv, padded);
                clear(padded);
                String output = encode(transformed, request.outputFormat());
                return new AesContracts.AesResult(output, mode, effectivePadding, normalizeFormat(request.outputFormat()));
            }
            transformed = cipher.transform(mode, false, key, iv, input);
            plain = unpad(transformed, effectivePadding, mode);
            String output = charset.decode(java.nio.ByteBuffer.wrap(plain)).toString();
            return new AesContracts.AesResult(output, mode, effectivePadding, "TEXT");
        } finally {
            clear(rawKey, rawIv, key, iv, input, transformed, plain);
        }
    }

    private static void validateRequest(AesContracts.AesRequest request) {
        if (request == null) throw new AesOperationException("AES_REQUEST_INVALID", "请求不能为空");
        if (request.compatibilityProfile() != CompatibilityProfile.TOOLHELPER_V3)
            throw new AesOperationException("AES_PROFILE_INVALID", "仅支持 TOOLHELPER_V3 兼容配置");
        if (request.mode() == null || request.padding() == null)
            throw new AesOperationException("AES_REQUEST_INVALID", "模式和填充不能为空");
        if (request.keySizeBits() != 128 && request.keySizeBits() != 192 && request.keySizeBits() != 256)
            throw new AesOperationException("AES_KEY_INVALID", "Key 长度仅支持 128/192/256 位");
        if (blank(request.key()) || request.input() == null)
            throw new AesOperationException("AES_REQUEST_INVALID", "Key 和输入不能为空");
        if (request.mode() != AesMode.ECB && blank(request.iv()))
            throw new AesOperationException("AES_IV_INVALID", "当前模式需要 IV");
        if (request.mode() == AesMode.CTS && request.padding() != PaddingMode.NONE)
            throw new AesOperationException("AES_PADDING_INVALID", "CTS 仅支持 None 填充");
        checkRawLimit(request.input());
        normalizeFormat(request.keyFormat());
        normalizeFormat(request.ivFormat());
        normalizeFormat(request.inputFormat());
        normalizeFormat(request.outputFormat());
    }

    private static byte[] decodeInput(String value, String format, Charset charset, boolean encrypt) {
        return "TEXT".equals(normalizeFormat(format)) && !encrypt
                ? decode(value, format, charset, "密文")
                : decode(value, format, charset, encrypt ? "明文" : "密文");
    }

    private static byte[] decodeParameter(String value, String format, Charset charset, String label) {
        if (blank(value)) throw new AesOperationException("AES_REQUEST_INVALID", label + "不能为空");
        String normalized = normalizeFormat(format);
        String source = "TEXT".equals(normalized) ? value.trim() : value;
        return decode(source, normalized, charset, label);
    }

    private static byte[] decode(String value, String format, Charset charset, String label) {
        try {
            return switch (normalizeFormat(format)) {
                case "TEXT" -> textBytes(value, charset);
                case "HEX" -> hex(value, label);
                case "BASE64" -> base64(value, label);
                default -> throw new AesOperationException("AES_FORMAT_INVALID", "不支持的" + label + "格式");
            };
        } catch (AesOperationException error) {
            throw error;
        } catch (IllegalArgumentException error) {
            throw new AesOperationException("AES_FORMAT_INVALID", label + " Base64 格式错误，无法解析输入", error);
        }
    }

    private static Charset charset(String value) {
        if (blank(value)) throw new AesOperationException("AES_CHARSET_INVALID", "字符编码不能为空");
        return switch (value.trim().toUpperCase(Locale.ROOT)) {
            case "UTF-8" -> StandardCharsets.UTF_8;
            case "UTF-16LE", "UTF-16" -> StandardCharsets.UTF_16LE;
            case "UTF-32LE", "UTF-32" -> Charset.forName("UTF-32LE");
            case "ASCII" -> StandardCharsets.US_ASCII;
            case "GBK" -> Charset.forName("GBK");
            case "ISO-8859-1" -> StandardCharsets.ISO_8859_1;
            default -> throw new AesOperationException("AES_CHARSET_INVALID", "不支持的字符编码：" + value);
        };
    }

    private static byte[] normalizeKey(byte[] raw, int bits) {
        int size = bits / 8;
        return fit(raw, size);
    }

    private static byte[] normalizeIv(byte[] raw, int size, boolean ignored) {
        return ignored ? new byte[16] : fit(raw, size);
    }

    private static byte[] fit(byte[] raw, int size) {
        byte[] result = new byte[size];
        System.arraycopy(raw, 0, result, 0, Math.min(raw.length, size));
        return result;
    }

    private static byte[] pad(byte[] input, PaddingMode padding, AesMode mode) {
        if (mode == AesMode.CTS || mode == AesMode.CTR) return input.clone();
        int remainder = input.length % 16;
        if (padding == PaddingMode.NONE) {
            if (mode == AesMode.OFB || mode == AesMode.CFB) return input.clone();
            if (remainder != 0) throw new AesOperationException("AES_PADDING_INVALID", "None 填充要求输入长度为 16 的倍数");
            return input.clone();
        }
        int padLength = remainder == 0 && padding == PaddingMode.ZEROS ? 0 : 16 - remainder;
        if (padding == PaddingMode.PKCS7) padLength = 16 - remainder;
        byte[] result = new byte[input.length + padLength];
        System.arraycopy(input, 0, result, 0, input.length);
        if (padLength == 0) return result;
        result[result.length - 1] = (byte) padLength;
        if (padding == PaddingMode.ISO10126) {
            byte[] random = new byte[padLength - 1];
            RANDOM.nextBytes(random);
            System.arraycopy(random, 0, result, input.length, random.length);
            clear(random);
        } else if (padding == PaddingMode.ANSIX923 || padding == PaddingMode.ZEROS) {
            java.util.Arrays.fill(result, input.length, result.length - 1, (byte) 0);
        } else if (padding == PaddingMode.PKCS7) {
            java.util.Arrays.fill(result, input.length, result.length, (byte) padLength);
        }
        return result;
    }

    private static byte[] unpad(byte[] input, PaddingMode padding, AesMode mode) {
        if (mode == AesMode.CTS || mode == AesMode.CTR || padding == PaddingMode.NONE) return input.clone();
        if (input.length == 0) return input.clone();
        if (padding == PaddingMode.ZEROS) {
            int end = input.length;
            while (end > 0 && input[end - 1] == 0) end--;
            return copyOfRange(input, 0, end);
        }
        int count = input[input.length - 1] & 0xff;
        if (count < 1 || count > 16 || count > input.length)
            throw new AesOperationException("AES_PADDING_INVALID", "填充校验失败");
        if (padding == PaddingMode.PKCS7) {
            for (int i = input.length - count; i < input.length; i++)
                if ((input[i] & 0xff) != count) throw new AesOperationException("AES_PADDING_INVALID", "PKCS7 填充校验失败");
        } else if (padding == PaddingMode.ANSIX923) {
            for (int i = input.length - count; i < input.length - 1; i++)
                if (input[i] != 0) throw new AesOperationException("AES_PADDING_INVALID", "ANSIX923 填充校验失败");
        }
        return copyOfRange(input, 0, input.length - count);
    }

    private static byte[] hex(String value, String label) {
        String source = value.startsWith("0x") || value.startsWith("0X") ? value.substring(2) : value;
        if ((source.length() & 1) != 0) throw new AesOperationException("AES_FORMAT_INVALID", label + " HEX 在位置 " + source.length() + " 缺少半字节");
        byte[] result = new byte[source.length() / 2];
        for (int i = 0; i < source.length(); i += 2) {
            int high = Character.digit(source.charAt(i), 16);
            int low = Character.digit(source.charAt(i + 1), 16);
            if (high < 0) throw new AesOperationException("AES_FORMAT_INVALID", label + " HEX 在位置 " + i + " 无效");
            if (low < 0) throw new AesOperationException("AES_FORMAT_INVALID", label + " HEX 在位置 " + (i + 1) + " 无效");
            result[i / 2] = (byte) ((high << 4) | low);
        }
        return result;
    }

    private static byte[] base64(String value, String label) {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            boolean valid = character >= 'A' && character <= 'Z' || character >= 'a' && character <= 'z'
                    || character >= '0' && character <= '9' || character == '+' || character == '/' || character == '=';
            if (!valid) throw new AesOperationException("AES_FORMAT_INVALID", label + " Base64 在位置 " + index + " 无效");
        }
        if ((value.length() & 3) != 0)
            throw new AesOperationException("AES_FORMAT_INVALID", label + " Base64 长度必须为 4 的倍数");
        try {
            return Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException error) {
            throw new AesOperationException("AES_FORMAT_INVALID", label + " Base64 填充无效", error);
        }
    }

    private static String encode(byte[] data, String format) {
        return switch (normalizeFormat(format)) {
            case "HEX" -> java.util.HexFormat.of().formatHex(data);
            case "BASE64" -> Base64.getEncoder().encodeToString(data);
            default -> throw new AesOperationException("AES_FORMAT_INVALID", "输出格式仅支持 HEX 或 BASE64");
        };
    }

    private static String normalizeFormat(String value) {
        if (blank(value)) throw new AesOperationException("AES_FORMAT_INVALID", "数据格式不能为空");
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!normalized.equals("TEXT") && !normalized.equals("HEX") && !normalized.equals("BASE64"))
            throw new AesOperationException("AES_FORMAT_INVALID", "不支持的数据格式：" + value);
        return normalized;
    }

    private static void checkRawLimit(String value) {
        if (value.getBytes(StandardCharsets.UTF_8).length > MAX_INPUT_BYTES)
            throw new AesOperationException("AES_INPUT_TOO_LARGE", "输入超过 1 MiB 限制");
    }

    private static void checkLimit(byte[] input, String label) {
        if (input.length > MAX_INPUT_BYTES) throw new AesOperationException("AES_INPUT_TOO_LARGE", label + "超过 1 MiB 限制");
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static byte[] copyOfRange(byte[] input, int start, int end) {
        byte[] result = new byte[end - start];
        System.arraycopy(input, start, result, 0, result.length);
        return result;
    }

    private static byte[] textBytes(String value, Charset charset) {
        ByteBuffer buffer = charset.encode(value);
        byte[] result = new byte[buffer.remaining()];
        buffer.get(result);
        return result;
    }

    private static void clear(byte[]... arrays) {
        for (byte[] array : arrays) if (array != null) java.util.Arrays.fill(array, (byte) 0);
    }
}
