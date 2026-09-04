package com.mafucai.relayscope;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Android Keystore backed AES-GCM encryption for local API keys. */
public final class SecretBox {
    private static final String STORE = "AndroidKeyStore";
    private static final String ALIAS = "relayscope-site-keys-v1";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    public String encrypt(String plain) {
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key());
            byte[] cipherText = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] iv = cipher.getIV();
            byte[] packed = new byte[iv.length + cipherText.length];
            System.arraycopy(iv, 0, packed, 0, iv.length);
            System.arraycopy(cipherText, 0, packed, iv.length, cipherText.length);
            return "enc1:" + Base64.encodeToString(packed, Base64.NO_WRAP);
        } catch (Exception e) { throw new IllegalStateException("无法加密本地密钥", e); }
    }

    public String decrypt(String packed) {
        if (packed == null || !packed.startsWith("enc1:")) return packed == null ? "" : packed;
        try {
            byte[] all = Base64.decode(packed.substring(5), Base64.NO_WRAP);
            byte[] iv = new byte[12]; byte[] cipherText = new byte[all.length - iv.length];
            System.arraycopy(all, 0, iv, 0, iv.length); System.arraycopy(all, iv.length, cipherText, 0, cipherText.length);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(cipherText), StandardCharsets.UTF_8);
        } catch (Exception e) { throw new IllegalStateException("无法解密本地密钥", e); }
    }

    private SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance(STORE); store.load(null);
        if (!store.containsAlias(ALIAS)) {
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, STORE);
            generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            generator.generateKey();
        }
        return ((KeyStore.SecretKeyEntry) store.getEntry(ALIAS, null)).getSecretKey();
    }
}
