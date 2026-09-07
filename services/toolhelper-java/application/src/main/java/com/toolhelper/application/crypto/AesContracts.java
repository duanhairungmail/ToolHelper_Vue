package com.toolhelper.application.crypto;

import com.toolhelper.domain.crypto.AesMode;
import com.toolhelper.domain.crypto.CompatibilityProfile;
import com.toolhelper.domain.crypto.PaddingMode;

public final class AesContracts {
    private AesContracts() {}

    public record AesRequest(
            CompatibilityProfile compatibilityProfile,
            AesMode mode,
            PaddingMode padding,
            int keySizeBits,
            String charset,
            String keyFormat,
            String ivFormat,
            String inputFormat,
            String outputFormat,
            String key,
            String iv,
            String input) {}

    public record AesResult(String output, AesMode mode, PaddingMode padding, String outputFormat) {}
}
