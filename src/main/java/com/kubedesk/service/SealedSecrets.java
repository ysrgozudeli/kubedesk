package com.kubedesk.service;

import io.fabric8.kubernetes.client.utils.Serialization;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.MGF1ParameterSpec;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Client-side generator for <a href="https://github.com/bitnami-labs/sealed-secrets">Bitnami Sealed
 * Secrets</a>. Encrypts secret values against the cluster's sealed-secrets controller public key and
 * emits a {@code SealedSecret} custom resource — the output is safe to commit to Git, because only the
 * controller (which holds the private key) can decrypt it.
 *
 * <p>The ciphertext format is byte-for-byte compatible with {@code kubeseal}'s {@code HybridEncrypt}:
 * a random 256-bit AES-GCM session key encrypts the value (zero nonce — the key is single-use), and the
 * session key is wrapped with RSA-OAEP (SHA-256 for both the hash and MGF1). The sealed blob is
 * {@code [2-byte big-endian RSA-ciphertext length] + RSA-ciphertext + AES-GCM-ciphertext}, then base64.
 *
 * <p>The OAEP label encodes the <em>scope</em>, and the controller must decrypt with the exact same
 * label, so a mismatch fails silently at unseal time. Strict scope binds the sealed secret to one
 * {@code namespace/name}; namespace-wide to the namespace; cluster-wide to nothing.
 */
public final class SealedSecrets {

    private SealedSecrets() {
    }

    /** Where a SealedSecret may be unsealed. Determines the OAEP label and the scope annotation. */
    public enum Scope {
        STRICT("Strict",
                "Only a SealedSecret with this exact name, in this namespace, can be unsealed. Safest default."),
        NAMESPACE_WIDE("Namespace-wide",
                "Any SealedSecret name in this namespace can unseal it (handy if the Secret is renamed)."),
        CLUSTER_WIDE("Cluster-wide",
                "Any name in any namespace can unseal it. Use only when you must move it freely.");

        private final String label;
        private final String help;

        Scope(String label, String help) {
            this.label = label;
            this.help = help;
        }

        public String label() {
            return label;
        }

        public String help() {
            return help;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private static final SecureRandom RNG = new SecureRandom();
    private static final int SESSION_KEY_BYTES = 32; // AES-256
    private static final int GCM_TAG_BITS = 128;
    private static final int GCM_NONCE_BYTES = 12;

    /**
     * Parse a controller public certificate (PEM, {@code -----BEGIN CERTIFICATE-----}) — what
     * {@code kubeseal --fetch-cert} returns — into its RSA public key. A bare PEM public key
     * ({@code -----BEGIN PUBLIC KEY-----}) is also accepted.
     *
     * <p>Sealed-secrets controllers mint a self-signed cert with an <em>empty</em> issuer/subject DN,
     * which Java's strict {@link CertificateFactory} rejects ("Empty issuer DN not allowed"). Like
     * {@code kubeseal}, we don't validate the cert — we only need the key — so when strict parsing fails
     * we fall back to pulling the RSA {@code SubjectPublicKeyInfo} out of the DER by its standard OID.
     * This is generic: it works for any RSA key size and any (even empty) certificate subject/issuer.
     */
    public static RSAPublicKey publicKeyFromPem(String pem) {
        if (pem == null || pem.isBlank()) {
            throw new IllegalArgumentException("No certificate provided.");
        }
        byte[] der;
        try {
            String base64 = pem.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
            der = Base64.getDecoder().decode(base64);
        } catch (Exception e) {
            throw new IllegalArgumentException("Not valid PEM/base64 content: " + e.getMessage());
        }

        // 1. Standard X.509 certificate (the normal case).
        try {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            X509Certificate cert = (X509Certificate) cf.generateCertificate(
                    new ByteArrayInputStream(pem.getBytes(StandardCharsets.UTF_8)));
            if (cert.getPublicKey() instanceof RSAPublicKey rsa) {
                return rsa;
            }
        } catch (Exception ignore) {
            // e.g. empty-DN cert — handled by the lenient path below
        }

        // 2. A raw SubjectPublicKeyInfo ("-----BEGIN PUBLIC KEY-----").
        try {
            var key = java.security.KeyFactory.getInstance("RSA")
                    .generatePublic(new java.security.spec.X509EncodedKeySpec(der));
            if (key instanceof RSAPublicKey rsa) {
                return rsa;
            }
        } catch (Exception ignore) {
            // not a bare public key — try extracting from the cert DER
        }

        // 3. Lenient: extract the RSA SubjectPublicKeyInfo from the certificate DER without validating it.
        try {
            return rsaKeyFromCertificateDer(der);
        } catch (Exception e) {
            throw new IllegalArgumentException("Couldn't read an RSA public key from the certificate: "
                    + e.getMessage());
        }
    }

    // The DER of the RSA algorithm identifier: SEQUENCE { OID 1.2.840.113549.1.1.1, NULL }.
    private static final byte[] RSA_ALG_ID = {
            0x30, 0x0D, 0x06, 0x09, 0x2A, (byte) 0x86, 0x48, (byte) 0x86,
            (byte) 0xF7, 0x0D, 0x01, 0x01, 0x01, 0x05, 0x00
    };

    /**
     * Pull the RSA public key out of a certificate's DER by locating the {@code SubjectPublicKeyInfo} —
     * the RSA algorithm identifier followed by the key BIT STRING — and re-wrapping it as a standalone
     * SPKI for {@link java.security.spec.X509EncodedKeySpec}. No certificate validation is performed.
     */
    private static RSAPublicKey rsaKeyFromCertificateDer(byte[] der) throws Exception {
        int algPos = indexOf(der, RSA_ALG_ID);
        if (algPos < 0) {
            throw new IllegalArgumentException("no RSA public key found");
        }
        int bitPos = algPos + RSA_ALG_ID.length;
        if (bitPos >= der.length || der[bitPos] != 0x03) { // expect a BIT STRING next
            throw new IllegalArgumentException("unexpected key encoding");
        }
        int[] len = readDerLength(der, bitPos + 1); // {contentLength, lengthFieldBytes}
        int bitStringTlv = 1 + len[1] + len[0];     // tag + length bytes + content
        int spkiContentLen = RSA_ALG_ID.length + bitStringTlv;
        if (algPos + spkiContentLen > der.length) {
            throw new IllegalArgumentException("truncated key");
        }
        byte[] spkiContent = java.util.Arrays.copyOfRange(der, algPos, algPos + spkiContentLen);
        byte[] spki = wrapInSequence(spkiContent);
        var key = java.security.KeyFactory.getInstance("RSA")
                .generatePublic(new java.security.spec.X509EncodedKeySpec(spki));
        if (key instanceof RSAPublicKey rsa) {
            return rsa;
        }
        throw new IllegalArgumentException("key is not RSA");
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    /** Read a DER length at {@code pos}; returns {contentLength, numberOfLengthBytes}. */
    private static int[] readDerLength(byte[] d, int pos) {
        int b = d[pos] & 0xFF;
        if (b < 0x80) {
            return new int[]{b, 1};
        }
        int n = b & 0x7F;
        int value = 0;
        for (int i = 0; i < n; i++) {
            value = (value << 8) | (d[pos + 1 + i] & 0xFF);
        }
        return new int[]{value, 1 + n};
    }

    /** Wrap raw content in a DER SEQUENCE (tag 0x30 + minimal length + content). */
    private static byte[] wrapInSequence(byte[] content) {
        byte[] lenEnc;
        int len = content.length;
        if (len < 0x80) {
            lenEnc = new byte[]{(byte) len};
        } else {
            int n = (len <= 0xFF) ? 1 : (len <= 0xFFFF) ? 2 : (len <= 0xFFFFFF) ? 3 : 4;
            lenEnc = new byte[1 + n];
            lenEnc[0] = (byte) (0x80 | n);
            for (int i = 0; i < n; i++) {
                lenEnc[1 + n - 1 - i] = (byte) (len >>> (8 * i));
            }
        }
        byte[] out = new byte[1 + lenEnc.length + content.length];
        out[0] = 0x30;
        System.arraycopy(lenEnc, 0, out, 1, lenEnc.length);
        System.arraycopy(content, 0, out, 1 + lenEnc.length, content.length);
        return out;
    }

    /** The OAEP label for a scope — must match what the controller uses when unsealing. */
    static byte[] labelFor(String namespace, String name, Scope scope) {
        return switch (scope) {
            case CLUSTER_WIDE -> new byte[0];
            case NAMESPACE_WIDE -> namespace.getBytes(StandardCharsets.UTF_8);
            case STRICT -> (namespace + "/" + name).getBytes(StandardCharsets.UTF_8);
        };
    }

    /** Seal one value into the raw sealed-secrets blob (pre-base64). */
    static byte[] seal(RSAPublicKey key, byte[] plaintext, byte[] label) {
        try {
            byte[] sessionKey = new byte[SESSION_KEY_BYTES];
            RNG.nextBytes(sessionKey);

            // Wrap the session key with RSA-OAEP. Java defaults MGF1 to SHA-1, but Go's
            // rsa.EncryptOAEP (what kubeseal uses) drives MGF1 from the same SHA-256 digest, so we set
            // MGF1ParameterSpec.SHA256 explicitly or the controller cannot unwrap the key.
            Cipher rsa = Cipher.getInstance("RSA/ECB/OAEPPadding");
            OAEPParameterSpec oaep = new OAEPParameterSpec(
                    "SHA-256", "MGF1", MGF1ParameterSpec.SHA256, new PSource.PSpecified(label));
            rsa.init(Cipher.ENCRYPT_MODE, key, oaep);
            byte[] rsaCipher = rsa.doFinal(sessionKey);

            // Encrypt the value with AES-256-GCM under a zero nonce (safe: the session key is used once).
            Cipher gcm = Cipher.getInstance("AES/GCM/NoPadding");
            gcm.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(sessionKey, "AES"),
                    new GCMParameterSpec(GCM_TAG_BITS, new byte[GCM_NONCE_BYTES]));
            byte[] aesCipher = gcm.doFinal(plaintext); // ciphertext || 16-byte tag

            if (rsaCipher.length > 0xFFFF) {
                throw new IllegalStateException("RSA ciphertext too large to frame.");
            }
            byte[] out = new byte[2 + rsaCipher.length + aesCipher.length];
            out[0] = (byte) (rsaCipher.length >>> 8);
            out[1] = (byte) rsaCipher.length;
            System.arraycopy(rsaCipher, 0, out, 2, rsaCipher.length);
            System.arraycopy(aesCipher, 0, out, 2 + rsaCipher.length, aesCipher.length);
            return out;
        } catch (Exception e) {
            throw new IllegalStateException("Sealing failed: " + e.getMessage(), e);
        }
    }

    /** Seal one value and base64-encode it (the form stored in {@code spec.encryptedData}). */
    public static String sealToBase64(RSAPublicKey key, byte[] plaintext, byte[] label) {
        return Base64.getEncoder().encodeToString(seal(key, plaintext, label));
    }

    /**
     * Build a complete {@code SealedSecret} manifest (YAML) from plaintext entries.
     *
     * @param data   secret key → raw plaintext bytes (strings should be UTF-8 encoded by the caller)
     * @param type   the Secret {@code type} the controller should produce (e.g. {@code Opaque}); may be null
     * @param labels/annotations optional metadata to copy onto the produced Secret (via the template)
     */
    public static String buildYaml(RSAPublicKey key, String namespace, String name, Scope scope,
                                   String type, Map<String, byte[]> data,
                                   Map<String, String> labels, Map<String, String> annotations) {
        byte[] label = labelFor(namespace, name, scope);

        Map<String, String> encrypted = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> e : data.entrySet()) {
            encrypted.put(e.getKey(), sealToBase64(key, e.getValue(), label));
        }

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("name", name);
        meta.put("namespace", namespace);
        // Scope lives in the SealedSecret's own annotations — that is where the controller reads it.
        Map<String, String> scopeAnnotations = new LinkedHashMap<>();
        if (scope == Scope.NAMESPACE_WIDE) {
            scopeAnnotations.put("sealedsecrets.bitnami.com/namespace-wide", "true");
        } else if (scope == Scope.CLUSTER_WIDE) {
            scopeAnnotations.put("sealedsecrets.bitnami.com/cluster-wide", "true");
        }
        if (!scopeAnnotations.isEmpty()) {
            meta.put("annotations", scopeAnnotations);
        }

        Map<String, Object> templateMeta = new LinkedHashMap<>();
        templateMeta.put("name", name);
        templateMeta.put("namespace", namespace);
        if (labels != null && !labels.isEmpty()) {
            templateMeta.put("labels", new LinkedHashMap<>(labels));
        }
        if (annotations != null && !annotations.isEmpty()) {
            templateMeta.put("annotations", new LinkedHashMap<>(annotations));
        }

        Map<String, Object> template = new LinkedHashMap<>();
        template.put("metadata", templateMeta);
        if (type != null && !type.isBlank()) {
            template.put("type", type);
        }

        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("encryptedData", encrypted);
        spec.put("template", template);

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("apiVersion", "bitnami.com/v1alpha1");
        root.put("kind", "SealedSecret");
        root.put("metadata", meta);
        root.put("spec", spec);

        return Serialization.asYaml(root);
    }
}
