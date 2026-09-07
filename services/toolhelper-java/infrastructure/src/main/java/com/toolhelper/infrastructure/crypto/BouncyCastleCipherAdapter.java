package com.toolhelper.infrastructure.crypto;

import com.toolhelper.application.crypto.AesCipherAdapter;
import com.toolhelper.application.crypto.AesOperationException;
import com.toolhelper.domain.crypto.AesMode;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

import javax.crypto.Cipher;
import javax.crypto.AEADBadTagException;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.security.Security;
import java.util.Arrays;

/** JCE 适配器；CTS 固定使用 BC，其余模式使用同一 AES/NoPadding 参数。 */
public final class BouncyCastleCipherAdapter implements AesCipherAdapter {
    private static final String PROVIDER = "BC";

    static {
        if (Security.getProvider(PROVIDER) == null) Security.addProvider(new BouncyCastleProvider());
    }

    @Override
    public byte[] transform(AesMode mode, boolean encrypt, byte[] key, byte[] iv, byte[] input) {
        if (mode == AesMode.CTR) return ctr(key, iv, input);
        try {
            Cipher cipher = Cipher.getInstance(transformation(mode), PROVIDER);
            int operation = encrypt ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE;
            SecretKeySpec secretKey = new SecretKeySpec(key, "AES");
            if (mode == AesMode.ECB) cipher.init(operation, secretKey);
            else cipher.init(operation, secretKey, parameter(mode, iv));
            return cipher.doFinal(input);
        } catch (AEADBadTagException error) {
            throw new AesOperationException("AES_AUTH_FAILED", "GCM 认证失败", error);
        } catch (GeneralSecurityException error) {
            throw new AesOperationException("AES_OPERATION_FAILED", "AES 运算失败", error);
        }
    }

    private static String transformation(AesMode mode) {
        return switch (mode) {
            case CBC -> "AES/CBC/NoPadding";
            case ECB -> "AES/ECB/NoPadding";
            case OFB -> "AES/OFB/NoPadding";
            case CFB -> "AES/CFB/NoPadding";
            case CTS -> "AES/CTS/NoPadding";
            case GCM -> "AES/GCM/NoPadding";
            case CTR -> "AES/ECB/NoPadding";
        };
    }

    private static java.security.spec.AlgorithmParameterSpec parameter(AesMode mode, byte[] iv) {
        return switch (mode) {
            case GCM -> new GCMParameterSpec(128, iv);
            default -> new IvParameterSpec(iv);
        };
    }

    private static byte[] ctr(byte[] key, byte[] iv, byte[] input) {
        byte[] result = new byte[input.length];
        byte[] counter = iv.clone();
        try {
            Cipher block = Cipher.getInstance("AES/ECB/NoPadding", PROVIDER);
            block.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"));
            for (int offset = 0; offset < input.length; offset += 16) {
                byte[] stream = block.doFinal(counter);
                int length = Math.min(16, input.length - offset);
                for (int index = 0; index < length; index++) result[offset + index] = (byte) (input[offset + index] ^ stream[index]);
                increment(counter);
                Arrays.fill(stream, (byte) 0);
            }
            return result;
        } catch (GeneralSecurityException error) {
            throw new AesOperationException("AES_OPERATION_FAILED", "AES CTR 运算失败", error);
        } finally {
            Arrays.fill(counter, (byte) 0);
        }
    }

    private static void increment(byte[] counter) {
        for (int index = counter.length - 1; index >= 0; index--) {
            counter[index]++;
            if (counter[index] != 0) break;
        }
    }
}
