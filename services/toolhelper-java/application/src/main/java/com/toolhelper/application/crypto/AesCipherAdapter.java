package com.toolhelper.application.crypto;

import com.toolhelper.domain.crypto.AesMode;

public interface AesCipherAdapter {
    byte[] transform(AesMode mode, boolean encrypt, byte[] key, byte[] iv, byte[] input);
}
