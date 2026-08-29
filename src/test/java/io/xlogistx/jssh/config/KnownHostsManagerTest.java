package io.xlogistx.jssh.config;

import io.xlogistx.jssh.config.KnownHostsManager.HostEntry;
import io.xlogistx.jssh.config.KnownHostsManager.VerifyResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Per-(host, key type) known_hosts behaviour, backed by a temp file.
 */
public class KnownHostsManagerTest {

    private static final String HOST = "Example.COM";
    private static final int PORT = JSSHConst.DEFAULT_SSH_PORT;
    private static final String RSA_FP = "SHA256:rsaAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    private static final String RSA_FP2 = "SHA256:rsaBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB";
    private static final String EC_FP = "SHA256:ecdsaCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC";

    private static PublicKey rsaKey;
    private static PublicKey rsaKey2;  // a different RSA key: a genuine "key changed"
    private static PublicKey ecKey;

    @TempDir
    Path tmp;

    @BeforeAll
    public static void generateKeys() throws Exception {
        KeyPairGenerator rsa = KeyPairGenerator.getInstance("RSA");
        rsa.initialize(2048);
        rsaKey = rsa.generateKeyPair().getPublic();
        rsaKey2 = rsa.generateKeyPair().getPublic();

        KeyPairGenerator ec = KeyPairGenerator.getInstance("EC");
        ec.initialize(256);
        ecKey = ec.generateKeyPair().getPublic();
    }

    private Path file() {
        return tmp.resolve("known_hosts");
    }

    private KnownHostsManager manager() {
        return new KnownHostsManager(file());
    }

    private String fileText() throws IOException {
        return Files.readString(file(), StandardCharsets.UTF_8);
    }

    private static String b64(PublicKey key) {
        return Base64.getEncoder().encodeToString(key.getEncoded());
    }

    @Test
    public void keyTypeOfMapsJdkAlgorithmNames() throws Exception {
        assertEquals("RSA", KnownHostsManager.keyTypeOf(rsaKey));
        assertEquals("ECDSA", KnownHostsManager.keyTypeOf(ecKey));
        PublicKey ed = KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPublic();
        assertEquals("ED25519", KnownHostsManager.keyTypeOf(ed));
        assertEquals(KnownHostsManager.ANY_KEY_TYPE, KnownHostsManager.keyTypeOf(null));
    }

    @Test
    public void unknownHostIsUnknown() {
        KnownHostsManager m = manager();
        assertEquals(VerifyResult.UNKNOWN, m.verify(HOST, PORT, RSA_FP, rsaKey));
        assertNull(m.getStoredFingerprint(HOST, PORT));
        assertNull(m.getStoredFingerprint(HOST, PORT, "RSA"));
    }

    @Test
    public void unchangedKeyIsKnownOk() {
        KnownHostsManager m = manager();
        m.addHost(HOST, PORT, "RSA", RSA_FP, rsaKey);
        assertEquals(VerifyResult.KNOWN_OK, m.verify(HOST, PORT, RSA_FP, rsaKey));
        // Host matching is case-insensitive
        assertEquals(VerifyResult.KNOWN_OK, m.verify("example.com", PORT, "RSA", RSA_FP, rsaKey));
        assertEquals(RSA_FP, m.getStoredFingerprint(HOST, PORT));
    }

    @Test
    public void sameHostDifferentKeyTypeIsUnknownNotChanged() throws IOException {
        KnownHostsManager m = manager();
        m.addHost(HOST, PORT, "RSA", RSA_FP, rsaKey);

        // Server now presents an ECDSA key: not a changed key, simply not known yet
        assertEquals(VerifyResult.UNKNOWN, m.verify(HOST, PORT, EC_FP, ecKey));

        // Accepting it must not overwrite the RSA entry
        m.addHost(HOST, PORT, "ECDSA", EC_FP, ecKey);
        assertEquals(VerifyResult.KNOWN_OK, m.verify(HOST, PORT, RSA_FP, rsaKey));
        assertEquals(VerifyResult.KNOWN_OK, m.verify(HOST, PORT, EC_FP, ecKey));
        assertEquals(RSA_FP, m.getStoredFingerprint(HOST, PORT, "RSA"));
        assertEquals(EC_FP, m.getStoredFingerprint(HOST, PORT, "ECDSA"));
        assertEquals(2, m.getAllHosts().size());

        // Both survive a reload from disk
        KnownHostsManager reloaded = manager();
        assertEquals(VerifyResult.KNOWN_OK, reloaded.verify(HOST, PORT, RSA_FP, rsaKey));
        assertEquals(VerifyResult.KNOWN_OK, reloaded.verify(HOST, PORT, EC_FP, ecKey));
        assertEquals(2, reloaded.getAllHosts().size());

        String text = fileText();
        assertTrue(text.contains("example.com RSA " + RSA_FP + " " + b64(rsaKey)), text);
        assertTrue(text.contains("example.com ECDSA " + EC_FP + " " + b64(ecKey)), text);
    }

    @Test
    public void changedKeyOfSameTypeIsReported() {
        KnownHostsManager m = manager();
        m.addHost(HOST, PORT, "RSA", RSA_FP, rsaKey);
        m.addHost(HOST, PORT, "ECDSA", EC_FP, ecKey);

        // A different RSA key than stored: CHANGED, and the old fingerprint of that type is reported
        assertEquals(VerifyResult.KNOWN_CHANGED, m.verify(HOST, PORT, "RSA", RSA_FP2, rsaKey2));
        assertEquals(RSA_FP, m.getStoredFingerprint(HOST, PORT));
        assertEquals(RSA_FP, m.getStoredFingerprint(HOST, PORT, "RSA"));

        // Accepting the new key replaces only the RSA entry
        m.addHost(HOST, PORT, "RSA", RSA_FP2, rsaKey2);
        assertEquals(VerifyResult.KNOWN_OK, m.verify(HOST, PORT, "RSA", RSA_FP2, rsaKey2));
        assertEquals(VerifyResult.KNOWN_CHANGED, m.verify(HOST, PORT, "RSA", RSA_FP, rsaKey));
        assertEquals(VerifyResult.KNOWN_OK, m.verify(HOST, PORT, "ECDSA", EC_FP, ecKey));
        assertEquals(2, m.getAllHosts().size());
    }

    @Test
    public void currentFormatFileLoads() throws IOException {
        Files.writeString(file(),
                "# JSSH Known Hosts File\n\n"
                        + "example.com RSA " + RSA_FP + " " + b64(rsaKey) + "\n"
                        + "[example.com]:2222 ECDSA " + EC_FP + "\n");
        KnownHostsManager m = manager();
        assertEquals(VerifyResult.KNOWN_OK, m.verify(HOST, PORT, "RSA", RSA_FP, rsaKey));
        assertEquals(VerifyResult.UNKNOWN, m.verify(HOST, PORT, "ECDSA", EC_FP, ecKey));
        assertEquals(VerifyResult.KNOWN_OK, m.verify(HOST, 2222, "ECDSA", EC_FP, ecKey));
        assertEquals(VerifyResult.UNKNOWN, m.verify(HOST, 2222, "RSA", RSA_FP, rsaKey));
    }

    @Test
    public void legacyTypelessEntryMatchesAnyTypeAndIsRewritten() throws IOException {
        Files.writeString(file(), "example.com " + RSA_FP + " " + b64(rsaKey) + "\n");

        KnownHostsManager m = manager();
        List<HostEntry> loaded = m.getAllHosts();
        assertEquals(1, loaded.size());
        assertEquals(KnownHostsManager.ANY_KEY_TYPE, loaded.get(0).getKeyType());
        assertEquals(RSA_FP, m.getStoredFingerprint(HOST, PORT, "ED25519"));

        // Same fingerprint under any type matches ...
        assertEquals(VerifyResult.KNOWN_OK, new KnownHostsManager(file()).verify(HOST, PORT, "ECDSA", RSA_FP, ecKey));

        // ... and matching rewrites the entry with the concrete type
        Files.writeString(file(), "example.com " + RSA_FP + " " + b64(rsaKey) + "\n");
        m = manager();
        assertEquals(VerifyResult.KNOWN_OK, m.verify(HOST, PORT, "RSA", RSA_FP, rsaKey));
        List<HostEntry> after = m.getAllHosts();
        assertEquals(1, after.size());
        assertEquals("RSA", after.get(0).getKeyType());
        assertEquals(b64(rsaKey), after.get(0).getEncodedKey());
        assertTrue(fileText().contains("example.com RSA " + RSA_FP), fileText());

        // Once typed, another type is simply unknown
        assertEquals(VerifyResult.UNKNOWN, m.verify(HOST, PORT, "ECDSA", EC_FP, ecKey));
    }

    @Test
    public void legacyTypelessEntryWithoutEncodedKeyLoads() throws IOException {
        Files.writeString(file(), "example.com " + RSA_FP + "\n");
        KnownHostsManager m = manager();
        assertEquals(VerifyResult.KNOWN_OK, m.verify(HOST, PORT, "RSA", RSA_FP, rsaKey));
        assertEquals(VerifyResult.KNOWN_OK, new KnownHostsManager(file()).verify(HOST, PORT, "RSA", RSA_FP, rsaKey));
    }

    @Test
    public void legacyTypelessEntryWithDifferentFingerprintIsChanged() throws IOException {
        Files.writeString(file(), "example.com " + RSA_FP + "\n");
        KnownHostsManager m = manager();
        assertEquals(VerifyResult.KNOWN_CHANGED, m.verify(HOST, PORT, "RSA", RSA_FP2, rsaKey2));
        assertEquals(RSA_FP, m.getStoredFingerprint(HOST, PORT));

        // Accepting the new key supersedes the legacy entry
        m.addHost(HOST, PORT, "RSA", RSA_FP2, rsaKey2);
        assertEquals(1, m.getAllHosts().size());
        assertEquals("RSA", m.getAllHosts().get(0).getKeyType());
        assertEquals(VerifyResult.KNOWN_OK, m.verify(HOST, PORT, "RSA", RSA_FP2, rsaKey2));
    }

    @Test
    public void oldFingerprintSchemeMigratesWhenEncodedKeyMatches() {
        KnownHostsManager m = manager();
        m.addHost(HOST, PORT, "RSA", "SHA256:oldSchemeFingerprint", rsaKey);
        assertEquals(VerifyResult.KNOWN_OK, m.verify(HOST, PORT, "RSA", RSA_FP, rsaKey));
        assertEquals(RSA_FP, m.getStoredFingerprint(HOST, PORT, "RSA"));
        assertEquals(1, m.getAllHosts().size());
    }

    @Test
    public void removeHostRemovesAllTypes() {
        KnownHostsManager m = manager();
        m.addHost(HOST, PORT, "RSA", RSA_FP, rsaKey);
        m.addHost(HOST, PORT, "ECDSA", EC_FP, ecKey);
        m.addHost(HOST, 2222, "RSA", RSA_FP, rsaKey);

        m.removeHost(HOST, PORT, "RSA");
        assertEquals(VerifyResult.UNKNOWN, m.verify(HOST, PORT, "RSA", RSA_FP, rsaKey));
        assertEquals(VerifyResult.KNOWN_OK, m.verify(HOST, PORT, "ECDSA", EC_FP, ecKey));

        m.removeHost(HOST, PORT);
        assertEquals(VerifyResult.UNKNOWN, m.verify(HOST, PORT, "ECDSA", EC_FP, ecKey));
        assertEquals(VerifyResult.KNOWN_OK, m.verify(HOST, 2222, "RSA", RSA_FP, rsaKey));
        assertEquals(1, new KnownHostsManager(file()).getAllHosts().size());
    }

    @Test
    public void storedFingerprintWithoutVerifyListsAllTypes() {
        KnownHostsManager m = manager();
        m.addHost(HOST, PORT, "RSA", RSA_FP, rsaKey);
        m.addHost(HOST, PORT, "ECDSA", EC_FP, ecKey);
        String all = new KnownHostsManager(file()).getStoredFingerprint(HOST, PORT);
        assertTrue(all.contains("RSA: " + RSA_FP), all);
        assertTrue(all.contains("ECDSA: " + EC_FP), all);
    }
}
