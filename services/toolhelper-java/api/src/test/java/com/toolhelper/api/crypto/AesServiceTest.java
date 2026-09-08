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
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        AesContracts.AesRequest request = new AesContracts.AesRequest(CompatibilityProfile.TOOLHELPER_V3,
                AesMode.CBC, PaddingMode.PKCS7, 128, "UTF-8", "HEX", "HEX", "HEX", "BASE64",
                "zz", "00", "00");
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

    @Test
    void matchesStageZeroVectorsForSupportedModes() {
        assertVector(AesMode.CBC, PaddingMode.PKCS7,
                "000102030405060708090a0b0c0d0e0f", "101112131415161718191a1b1c1d1e1f",
                "00112233445566778899aabbccddeeff",
                "1eca870ffea114b7fd6cf363c30b96b10d47f7c33722bae48a4489a6bb9d993d");
        assertVector(AesMode.ECB, PaddingMode.PKCS7,
                "000102030405060708090a0b0c0d0e0f", "",
                "00112233445566778899aabbccddeeff",
                "69c4e0d86a7b0430d8cdb78070b4c55a954f64f2e4e86e9eee82d20216684899");
        assertVector(AesMode.OFB, PaddingMode.NONE,
                "000102030405060708090a0b0c0d0e0f", "101112131415161718191a1b1c1d1e1f",
                "00112233445566778899aabbccddeeff", "07efcd47a5806519189744aa42497c6c");
        assertVector(AesMode.CFB, PaddingMode.NONE,
                "000102030405060708090a0b0c0d0e0f", "101112131415161718191a1b1c1d1e1f",
                "00112233445566778899aabbccddeeff", "07975727c77c260f1936be5ff9c9d3c2");
        assertVector(AesMode.CTR, PaddingMode.NONE,
                "000102030405060708090a0b0c0d0e0f", "101112131415161718191a1b1c1d1e1f",
                "00112233445566778899aabbccddeeff", "07efcd47a5806519189744aa42497c6c");
        assertVector(AesMode.GCM, PaddingMode.NONE,
                "000102030405060708090a0b0c0d0e0f", "101112131415161718191a1b1c1d1e1f",
                "00112233445566778899aabbccddeeff",
                "101112131415161718191a1b04f771ec4ba043761b723a825f6a3833c43f219c4b1ad0989f44f74e0bfa05c1");
    }

    @Test
    void supportsAllKeySizesAndBlockBoundaries() {
        assertVector(AesMode.CBC, PaddingMode.PKCS7,
                "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f",
                "101112131415161718191a1b1c1d1e1f", "00112233445566778899aabbccddeeff",
                "0e2392dd6f690b44a5a1b4fdff3b7f8303657c008fa9cf5c86c1a750a150a5fe");
        assertVector(AesMode.CBC, PaddingMode.PKCS7,
                "000102030405060708090a0b0c0d0e0f1011121314151617",
                "101112131415161718191a1b1c1d1e1f", "00112233445566778899aabbccddeeff",
                "85ad183fb866f8182dbafa82ef7e9fc00169a3c1b9b6164e4655c709c443d4c7");
        for (int length : new int[]{0, 15, 16, 17}) {
            StringBuilder input = new StringBuilder();
            for (int i = 0; i < length; i++) input.append(String.format("%02x", i & 0xff));
            AesContracts.AesRequest request = request(AesMode.CBC, PaddingMode.PKCS7,
                    "000102030405060708090a0b0c0d0e0f", "101112131415161718191a1b1c1d1e1f",
                    input.toString(), "HEX", "HEX", "HEX", "HEX");
            var encrypted = service.encrypt(request);
            var decrypted = service.decrypt(new AesContracts.AesRequest(CompatibilityProfile.TOOLHELPER_V3,
                    AesMode.CBC, PaddingMode.PKCS7, 128, "ISO-8859-1", "HEX", "HEX", "HEX", "TEXT",
                    request.key(), request.iv(), encrypted.output()));
            assertEquals(input.toString(), java.util.HexFormat.of().formatHex(decrypted.output().getBytes(java.nio.charset.StandardCharsets.ISO_8859_1)));
        }
    }

    @Test
    void carriesCtrAcrossLastByteAndRejectsGcmTampering() {
        byte[] data = new byte[16 * 3];
        for (int i = 0; i < data.length; i++) data[i] = (byte) i;
        StringBuilder input = new StringBuilder(java.util.HexFormat.of().formatHex(data));
        AesContracts.AesRequest ctr = request(AesMode.CTR, PaddingMode.NONE,
                "000102030405060708090a0b0c0d0e0f", "101112131415161718191a1b1c1d1eff",
                input.toString(), "HEX", "HEX", "HEX", "HEX");
        var encrypted = service.encrypt(ctr);
        var decrypted = service.decrypt(new AesContracts.AesRequest(CompatibilityProfile.TOOLHELPER_V3,
                AesMode.CTR, PaddingMode.NONE, 128, "ISO-8859-1", "HEX", "HEX", "HEX", "TEXT",
                ctr.key(), ctr.iv(), encrypted.output()));
        assertEquals(input.toString(), java.util.HexFormat.of().formatHex(decrypted.output().getBytes(java.nio.charset.StandardCharsets.ISO_8859_1)));

        AesContracts.AesRequest gcm = request(AesMode.GCM, PaddingMode.NONE,
                "000102030405060708090a0b0c0d0e0f", "101112131415161718191a1b1c1d1e1f",
                "00112233445566778899aabbccddeeff", "HEX", "HEX", "HEX", "HEX");
        byte[] combined = java.util.HexFormat.of().parseHex(service.encrypt(gcm).output());
        for (int index : new int[]{0, 12, 28}) {
            byte[] tampered = combined.clone();
            tampered[index] ^= 1;
            AesContracts.AesRequest bad = new AesContracts.AesRequest(CompatibilityProfile.TOOLHELPER_V3,
                    AesMode.GCM, PaddingMode.NONE, 128, "UTF-8", "HEX", "HEX", "HEX", "HEX",
                    gcm.key(), gcm.iv(), java.util.HexFormat.of().formatHex(tampered));
            AesOperationException error = assertThrows(AesOperationException.class, () -> service.decrypt(bad));
            assertTrue(error.code().equals("AES_AUTH_FAILED"), error.getMessage());
        }
    }

    @Test
    void rejectsInvalidBase64KeyIvAndModePaddingCombinations() {
        AesContracts.AesRequest base = request(AesMode.CBC, PaddingMode.PKCS7,
                "000102030405060708090a0b0c0d0e0f", "101112131415161718191a1b1c1d1e1f",
                "00", "HEX", "HEX", "HEX", "HEX");
        AesContracts.AesRequest invalidBase64 = new AesContracts.AesRequest(CompatibilityProfile.TOOLHELPER_V3,
                AesMode.CBC, PaddingMode.PKCS7, 128, "UTF-8", "BASE64", "HEX", "HEX", "HEX",
                "not base64", base.iv(), base.input());
        assertEquals("AES_FORMAT_INVALID", assertThrows(AesOperationException.class,
                () -> service.encrypt(invalidBase64)).code());

        AesContracts.AesRequest invalidIv = new AesContracts.AesRequest(CompatibilityProfile.TOOLHELPER_V3,
                AesMode.CBC, PaddingMode.PKCS7, 128, "UTF-8", "HEX", "HEX", "HEX", "HEX",
                base.key(), "zz", base.input());
        assertEquals("AES_FORMAT_INVALID", assertThrows(AesOperationException.class,
                () -> service.encrypt(invalidIv)).code());

        AesContracts.AesRequest invalidPadding = new AesContracts.AesRequest(CompatibilityProfile.TOOLHELPER_V3,
                AesMode.CTR, PaddingMode.PKCS7, 128, "UTF-8", "HEX", "HEX", "HEX", "HEX",
                base.key(), base.iv(), base.input());
        assertEquals(PaddingMode.NONE, service.encrypt(invalidPadding).padding());
    }

    @Test
    void normalizesShortAndLongKeysAndRoundTripsAllSupportedCharsets() {
        String exact = "000102030405060708090a0b0c0d0e0f";
        String shortKey = "000102030405060708090a0b0c0d0e";
        String longKey = exact + "10";
        String iv = "101112131415161718191a1b1c1d1e1f";
        String input = "00112233445566778899aabbccddeeff";
        String expected = service.encrypt(new AesContracts.AesRequest(CompatibilityProfile.TOOLHELPER_V3,
                AesMode.CBC, PaddingMode.PKCS7, 128, "UTF-8", "HEX", "HEX", "HEX", "HEX",
                exact, iv, input)).output();
        assertEquals("5c49fed2af02152fe05d17da076db5c21999dec693de7e082839f260b8691616",
                service.encrypt(new AesContracts.AesRequest(CompatibilityProfile.TOOLHELPER_V3,
                AesMode.CBC, PaddingMode.PKCS7, 128, "UTF-8", "HEX", "HEX", "HEX", "HEX",
                shortKey, iv, input)).output());
        assertEquals(expected, service.encrypt(new AesContracts.AesRequest(CompatibilityProfile.TOOLHELPER_V3,
                AesMode.CBC, PaddingMode.PKCS7, 128, "UTF-8", "HEX", "HEX", "HEX", "HEX",
                longKey, iv, input)).output());

        for (String charset : new String[]{"UTF-8", "UTF-16LE", "UTF-32LE", "ASCII", "GBK", "ISO-8859-1"}) {
            String text = switch (charset) {
                case "ASCII" -> "ToolHelper";
                case "ISO-8859-1" -> "ToolHelper-é";
                default -> "ToolHelper-测试";
            };
            AesContracts.AesRequest encrypt = new AesContracts.AesRequest(CompatibilityProfile.TOOLHELPER_V3,
                    AesMode.CBC, PaddingMode.PKCS7, 128, charset, "TEXT", "TEXT", "TEXT", "BASE64",
                    "stage4-key", "stage4-iv", text);
            var encrypted = service.encrypt(encrypt);
            var decrypted = service.decrypt(new AesContracts.AesRequest(CompatibilityProfile.TOOLHELPER_V3,
                    AesMode.CBC, PaddingMode.PKCS7, 128, charset, "TEXT", "TEXT", "BASE64", "TEXT",
                    "stage4-key", "stage4-iv", encrypted.output()));
            assertEquals(text, decrypted.output(), charset);
        }
    }

    private void assertVector(AesMode mode, PaddingMode padding, String key, String iv, String input, String expected) {
        AesContracts.AesRequest request = request(mode, padding, key, iv, input, "HEX", "HEX", "HEX", "HEX");
        var encrypted = service.encrypt(request);
        assertEquals(expected, encrypted.output());
        var decrypted = service.decrypt(new AesContracts.AesRequest(CompatibilityProfile.TOOLHELPER_V3, mode,
                padding, key.length() * 4, "ISO-8859-1", "HEX", "HEX", "HEX", "TEXT", key, iv, encrypted.output()));
        assertEquals(input, java.util.HexFormat.of().formatHex(decrypted.output().getBytes(java.nio.charset.StandardCharsets.ISO_8859_1)));
    }

    private static AesContracts.AesRequest request(AesMode mode, PaddingMode padding, String key, String iv, String input,
                                                    String keyFormat, String ivFormat, String inputFormat, String outputFormat) {
        int keySizeBits = keyFormat.equals("HEX") ? key.replaceFirst("^(0x|0X)", "").length() * 4 : 128;
        return new AesContracts.AesRequest(CompatibilityProfile.TOOLHELPER_V3, mode, padding, keySizeBits, "UTF-8",
                keyFormat, ivFormat, inputFormat, outputFormat, key, iv, input);
    }
}
