package com.toolhelper.api.crypto;

import com.toolhelper.application.crypto.AesContracts;
import com.toolhelper.application.crypto.AesOperationException;
import com.toolhelper.application.crypto.AesService;
import com.toolhelper.domain.crypto.AesMode;
import com.toolhelper.domain.crypto.CompatibilityProfile;
import com.toolhelper.domain.crypto.PaddingMode;
import com.toolhelper.infrastructure.crypto.BouncyCastleCipherAdapter;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AesServiceTest {
    private final AesService service = new AesService(new BouncyCastleCipherAdapter());

    @Test
    void encryptsAndDecryptsCbcHexVector() {
        AesContracts.AesRequest request = request(AesMode.CBC, PaddingMode.PKCS7,
                "000102030405060708090a0b0c0d0e0f", "101112131415161718191a1b1c1d1e1f",
                "00112233445566778899aabbccddeeff", "HEX", "HEX", "HEX", "HEX");
        var encrypted = service.encrypt(request);
        assertEquals("1eca870ffea114b7fd6cf363c30b96b10d47f7c33722bae48a4489a6bb9d993d", encrypted.output());
        var decrypted = service.decrypt(new AesContracts.AesRequest(CompatibilityProfile.TOOLHELPER_V3, AesMode.CBC,
                PaddingMode.PKCS7, 128, "ISO-8859-1", "HEX", "HEX", "HEX", "TEXT", request.key(), request.iv(), encrypted.output()));
        assertArrayEquals(java.util.HexFormat.of().parseHex(request.input()), decrypted.output().getBytes(StandardCharsets.ISO_8859_1));
    }

    @Test
    void rejectsInvalidHexWithStableCode() {
        AesContracts.AesRequest request = request(AesMode.CBC, PaddingMode.PKCS7, "zz", "00", "00", "HEX", "HEX", "HEX", "BASE64");
        assertEquals("AES_FORMAT_INVALID", assertThrows(AesOperationException.class, () -> service.encrypt(request)).code());
    }

    @Test
    void roundTripsCtrAndGcmWithUnicodeText() {
        for (AesMode mode : new AesMode[]{AesMode.CTR, AesMode.GCM}) {
            AesContracts.AesRequest encrypt = new AesContracts.AesRequest(CompatibilityProfile.TOOLHELPER_V3, mode,
                    PaddingMode.PKCS7, 128, "UTF-8", "TEXT", "TEXT", "TEXT", "BASE64", "secret", "nonce", "中文");
            var encrypted = service.encrypt(encrypt);
            var decrypted = service.decrypt(new AesContracts.AesRequest(CompatibilityProfile.TOOLHELPER_V3, mode,
                    PaddingMode.NONE, 128, "UTF-8", "TEXT", "TEXT", "BASE64", "TEXT", "secret", "nonce", encrypted.output()));
            assertEquals("中文", decrypted.output());
        }
    }

    private static AesContracts.AesRequest request(AesMode mode, PaddingMode padding, String key, String iv, String input,
                                                    String keyFormat, String ivFormat, String inputFormat, String outputFormat) {
        return new AesContracts.AesRequest(CompatibilityProfile.TOOLHELPER_V3, mode, padding, 128, "UTF-8",
                keyFormat, ivFormat, inputFormat, outputFormat, key, iv, input);
    }
}
