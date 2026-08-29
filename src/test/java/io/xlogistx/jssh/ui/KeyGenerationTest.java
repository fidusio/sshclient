package io.xlogistx.jssh.ui;

import org.apache.sshd.common.NamedResource;
import org.apache.sshd.common.config.keys.FilePasswordProvider;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.keyprovider.KeyPairProvider;
import org.apache.sshd.common.util.security.SecurityUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.PublicKey;
import java.util.Arrays;
import java.util.Iterator;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Headless tests for {@link KeyManagerDialog#generateKeyPairFiles}: the key
 * pair is generated in-process and written in OpenSSH format, and MINA must be
 * able to load both halves back (with the passphrase for the private key).
 */
public class KeyGenerationTest {

    private static KeyPair loadPrivate(Path file, String passphrase) throws Exception {
        try (InputStream in = Files.newInputStream(file)) {
            Iterable<KeyPair> ids = SecurityUtils.loadKeyPairIdentities(null,
                    NamedResource.ofName(file.toString()), in,
                    passphrase != null ? FilePasswordProvider.of(passphrase) : null);
            assertNotNull(ids, "no key pairs loaded from " + file);
            Iterator<KeyPair> it = ids.iterator();
            assertTrue(it.hasNext());
            KeyPair kp = it.next();
            assertFalse(it.hasNext(), "expected exactly one key in " + file);
            return kp;
        }
    }

    private static void assertRoundTrip(Path dir, String keyType, int bits, String name, String comment,
                                        String passphrase) throws Exception {
        Path priv = dir.resolve(name);
        Path pub = dir.resolve(name + ".pub");
        char[] pass = passphrase != null ? passphrase.toCharArray() : null;

        KeyManagerDialog.generateKeyPairFiles(keyType, bits, priv, comment, pass);

        // The caller-owned array must not have been altered by generation
        if (pass != null) {
            assertArrayEquals(passphrase.toCharArray(), pass);
            Arrays.fill(pass, '\0');
        }

        assertTrue(Files.exists(priv));
        assertTrue(Files.exists(pub));

        String privText = Files.readString(priv, StandardCharsets.UTF_8);
        assertTrue(privText.startsWith("-----BEGIN OPENSSH PRIVATE KEY-----\n"), privText);
        assertTrue(privText.endsWith("-----END OPENSSH PRIVATE KEY-----\n"), privText);
        assertFalse(privText.contains("\r"), "OpenSSH format requires bare LF line endings");

        String pubText = Files.readString(pub, StandardCharsets.UTF_8);
        String[] parts = pubText.trim().split(" ", 3);
        assertEquals(keyType, parts[0], pubText);
        assertEquals(comment, parts.length > 2 ? parts[2] : "", pubText);
        assertTrue(pubText.endsWith("\n"));

        KeyPair loaded = loadPrivate(priv, passphrase);
        assertEquals(keyType, KeyUtils.getKeyType(loaded));

        PublicKey fromPub = KeyUtils.loadPublicKey(pub);
        assertNotNull(fromPub);
        assertTrue(KeyUtils.compareKeys(loaded.getPublic(), fromPub),
                ".pub does not match the public half of the private key");
    }

    @Test
    public void ed25519WithPassphraseRoundTrips(@TempDir Path dir) throws Exception {
        assertRoundTrip(dir, KeyPairProvider.SSH_ED25519, KeyManagerDialog.ED25519_KEY_BITS,
                "id_ed25519", "alice@test", "correct horse battery staple");
    }

    @Test
    public void rsaWithPassphraseRoundTrips(@TempDir Path dir) throws Exception {
        // 2048 keeps the test fast; the dialog itself uses JSSHConst.RSA_KEY_BITS (4096)
        assertRoundTrip(dir, KeyPairProvider.SSH_RSA, 2048, "id_rsa", "bob@test", "s3cret pa55");
        KeyPair kp = loadPrivate(dir.resolve("id_rsa"), "s3cret pa55");
        assertEquals(2048, KeyUtils.getKeySize(kp.getPublic()));
    }

    @Test
    public void ecdsaWithPassphraseRoundTrips(@TempDir Path dir) throws Exception {
        assertRoundTrip(dir, KeyPairProvider.ECDSA_SHA2_NISTP256, KeyManagerDialog.ECDSA_NISTP256_KEY_BITS,
                "id_ecdsa", "carol@test", "pass");
    }

    @Test
    public void emptyPassphraseWritesUnencryptedKey(@TempDir Path dir) throws Exception {
        assertRoundTrip(dir, KeyPairProvider.SSH_ED25519, KeyManagerDialog.ED25519_KEY_BITS,
                "id_plain", "", null);
        // Loads without any password provider at all
        KeyPair kp = loadPrivate(dir.resolve("id_plain"), null);
        assertEquals(KeyPairProvider.SSH_ED25519, KeyUtils.getKeyType(kp));

        Path priv2 = dir.resolve("id_plain2");
        KeyManagerDialog.generateKeyPairFiles(KeyPairProvider.SSH_ED25519, KeyManagerDialog.ED25519_KEY_BITS,
                priv2, "c", new char[0]);
        assertNotNull(loadPrivate(priv2, null));
    }

    @Test
    public void wrongPassphraseIsRejected(@TempDir Path dir) throws Exception {
        Path priv = dir.resolve("id_ed25519");
        KeyManagerDialog.generateKeyPairFiles(KeyPairProvider.SSH_ED25519, KeyManagerDialog.ED25519_KEY_BITS,
                priv, "x", "right".toCharArray());
        assertThrows(Exception.class, () -> loadPrivate(priv, "wrong"));
    }

    @Test
    public void existingFilesAreReplaced(@TempDir Path dir) throws Exception {
        Path priv = dir.resolve("id_ed25519");
        Path pub = dir.resolve("id_ed25519.pub");
        Files.writeString(priv, "old private");
        Files.writeString(pub, "old public");

        KeyManagerDialog.generateKeyPairFiles(KeyPairProvider.SSH_ED25519, KeyManagerDialog.ED25519_KEY_BITS,
                priv, "new", "p".toCharArray());

        assertNotEquals("old private", Files.readString(priv));
        assertTrue(Files.readString(pub).startsWith(KeyPairProvider.SSH_ED25519 + " "));
        assertNotNull(loadPrivate(priv, "p"));
    }

    @Test
    public void privateKeyIsOwnerOnlyOnPosix(@TempDir Path dir) throws Exception {
        Path priv = dir.resolve("id_ed25519");
        KeyManagerDialog.generateKeyPairFiles(KeyPairProvider.SSH_ED25519, KeyManagerDialog.ED25519_KEY_BITS,
                priv, "c", null);
        if (priv.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(
                    Files.getPosixFilePermissions(priv)));
        }
        // Non-POSIX (Windows): the file must at least exist and be readable by the owner
        assertTrue(Files.isReadable(priv));
    }
}
