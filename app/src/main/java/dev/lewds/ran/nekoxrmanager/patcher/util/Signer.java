package dev.lewds.ran.nekoxrmanager.patcher.util;

import com.android.apksig.ApkSigner;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Collections;
import java.util.Date;
import java.util.Locale;

import dev.lewds.ran.nekoxrmanager.BuildConfig;

/**
 * Generates a self-signed RSA-2048 keystore on first use and signs APKs with V2+V3.
 * V1 signing is disabled (zipalign breaks with it); min SDK is 28 anyway.
 */
public final class Signer {

    private static final char[] PASSWORD = "password".toCharArray();
    private static final String KEY_ALIAS = "alias";
    private static final long ONE_DAY_MS = 1000L * 60L * 60L * 24L;

    private final File keystoreFile;
    private volatile ApkSigner.SignerConfig signerConfig;

    public Signer(File keystoreFile) {
        this.keystoreFile = keystoreFile;
    }

    public File getKeystoreFile() {
        return keystoreFile;
    }

    /** Signs in place — input APK is overwritten with the signed APK. */
    public void signApk(File apkFile) throws Exception {
        File tmp = new File(apkFile.getParentFile(), apkFile.getName() + ".tmp");

        // Pinning minSdk skips apksig's manifest-derived lookup, which is fragile
        // on third-party APKs. The patcher itself targets minSdk 28 and the only
        // supported install target is API 34+, so V2+V3 covers the floor.
        new ApkSigner.Builder(Collections.singletonList(loadOrCreateSignerConfig()))
                .setV1SigningEnabled(false)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(true)
                .setMinSdkVersion(28)
                .setInputApk(apkFile)
                .setOutputApk(tmp)
                .build()
                .sign();

        if (!apkFile.delete() || !tmp.renameTo(apkFile)) {
            throw new IOException("Could not replace " + apkFile + " with signed copy");
        }
    }

    private ApkSigner.SignerConfig loadOrCreateSignerConfig() throws Exception {
        if (signerConfig != null) return signerConfig;
        synchronized (this) {
            if (signerConfig != null) return signerConfig;

            if (!keystoreFile.exists()) {
                keystoreFile.getParentFile().mkdirs();
                createNewKeystore(keystoreFile);
            }

            KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
            try (FileInputStream in = new FileInputStream(keystoreFile)) {
                keyStore.load(in, null);
            }
            String alias = keyStore.aliases().nextElement();
            X509Certificate cert = (X509Certificate) keyStore.getCertificate(alias);
            PrivateKey key = (PrivateKey) keyStore.getKey(alias, PASSWORD);

            signerConfig = new ApkSigner.SignerConfig.Builder(
                    BuildConfig.APPLICATION_NAME + " signer",
                    key,
                    Collections.singletonList(cert)
            ).build();
            return signerConfig;
        }
    }

    private static void createNewKeystore(File out) throws Exception {
        KeyPair pair = generateKeyPair();
        X509Certificate cert = generateSelfSignedCert(pair);

        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        ks.load(null, PASSWORD);
        ks.setKeyEntry(KEY_ALIAS, pair.getPrivate(), PASSWORD,
                new Certificate[] { cert });
        try (FileOutputStream fos = new FileOutputStream(out)) {
            ks.store(fos, PASSWORD);
        }
    }

    private static KeyPair generateKeyPair() throws NoSuchAlgorithmException {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        return gen.generateKeyPair();
    }

    private static X509Certificate generateSelfSignedCert(KeyPair pair) throws OperatorCreationException, java.security.cert.CertificateException {
        BigInteger serial;
        do {
            serial = BigInteger.valueOf(new SecureRandom().nextInt());
        } while (serial.signum() < 0);

        X500Name name = new X500Name("CN=" + BuildConfig.APPLICATION_NAME);
        long now = System.currentTimeMillis();
        X509v3CertificateBuilder builder = new X509v3CertificateBuilder(
                /* issuer       */ name,
                /* serial       */ serial,
                /* notBefore    */ new Date(now - 30L * ONE_DAY_MS),
                /* notAfter     */ new Date(now + 30L * 366L * ONE_DAY_MS),
                /* dateLocale   */ Locale.ENGLISH,
                /* subject      */ name,
                /* publicKey    */ SubjectPublicKeyInfo.getInstance(pair.getPublic().getEncoded())
        );
        return new JcaX509CertificateConverter().getCertificate(
                builder.build(new JcaContentSignerBuilder("SHA256withRSA").build(pair.getPrivate()))
        );
    }
}
