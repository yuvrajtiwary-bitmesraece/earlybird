/*
 * Copies Aiven's Kafka client-certificate files into .env as KAFKA_SSL_CA_PEM, KAFKA_SSL_CERT_PEM and
 * KAFKA_SSL_KEY_PEM (spec section 5), and sets KAFKA_SECURITY_PROTOCOL=SSL. Run it from the project folder:
 *
 *   java scripts/KafkaPemsToEnv.java <folder with the 3 downloaded files> [env file, default .env]
 *
 * The files may have any names; they are recognized by content: the private key, the CA certificate (marked
 * as a CA) and the client certificate. Each one is checked (it parses, isn't expired, the key belongs
 * to the certificate), then written on one line with newlines as \n, which is how a properties-format .env
 * holds multi-line text. Existing lines for these settings are replaced; nothing is written unless every
 * check passes.
 *
 * Prints OK/FAIL only, never a value, so the output is safe to paste anywhere.
 */

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

static final Pattern PEM_BLOCK = Pattern.compile("-----BEGIN ([A-Z0-9 ]+)-----\\s*([A-Za-z0-9+/=\\s]+?)\\s*-----END \\1-----");
static final Pattern BOOTSTRAP = Pattern.compile("[A-Za-z0-9.-]+:\\d{1,5}(,[A-Za-z0-9.-]+:\\d{1,5})*");

boolean failed = false;

void main(String[] args) throws Exception {
    if (args.length < 1 || args.length > 2) {
        IO.println("Usage: java scripts/KafkaPemsToEnv.java <folder with the Aiven files> [env file, default .env]");
        System.exit(2);
    }
    Path folder = Path.of(args[0]);
    Path envFile = Path.of(args.length == 2 ? args[1] : ".env");
    if (!Files.isDirectory(folder)) {
        fail("not a folder: " + folder);
        System.exit(1);
    }
    if (!Files.isRegularFile(envFile)) {
        fail(envFile + " not found: run this from the project folder (copy .env.example to .env first)");
        System.exit(1);
    }

    List<X509Certificate> caCerts = null;
    List<X509Certificate> clientCerts = null;
    PrivateKey key = null;
    int keys = 0;
    try (var files = Files.list(folder)) {
        for (Path file : files.filter(Files::isRegularFile).toList()) {
            if (Files.size(file) > 64 * 1024) {
                continue; // not a PEM file
            }
            String text = Files.readString(file, StandardCharsets.ISO_8859_1);
            if (text.contains("PRIVATE KEY-----")) {
                keys++;
                key = readKey(text);
            } else if (text.contains("-----BEGIN CERTIFICATE-----")) {
                List<X509Certificate> certs = readCertificates(text);
                if (!certs.isEmpty() && isCa(certs.getFirst())) {
                    caCerts = certs;
                } else {
                    clientCerts = certs;
                }
            }
        }
    }

    if (caCerts == null) {
        fail("no CA certificate found in " + folder + " (Aiven: Overview > Connection information > CA certificate)");
    }
    if (clientCerts == null || clientCerts.isEmpty()) {
        fail("no client certificate found in " + folder + " (Aiven: Access certificate)");
    }
    if (keys != 1) {
        fail(keys == 0 ? "no private key found in " + folder + " (Aiven: Access key)"
                : keys + " private keys found in " + folder + "; keep only the one from this service");
    }
    String bootstrap = currentValue(envFile, "KAFKA_BOOTSTRAP_SERVERS");
    if (bootstrap == null || bootstrap.isBlank()) {
        fail("KAFKA_BOOTSTRAP_SERVERS is missing in " + envFile + ": add the Service URI (host:port) from Aiven");
    } else if (!BOOTSTRAP.matcher(bootstrap.strip()).matches()) {
        fail("KAFKA_BOOTSTRAP_SERVERS in " + envFile + " isn't host:port (no quotes, no kafka:// prefix)");
    } else {
        ok("KAFKA_BOOTSTRAP_SERVERS is set");
    }
    if (failed || key == null) {
        System.exit(1);
    }

    X509Certificate ca = caCerts.getFirst();
    X509Certificate client = clientCerts.getFirst();
    check(stillValid(ca), "CA certificate (valid until " + until(ca) + ")", "CA certificate expired on " + until(ca));
    check(stillValid(client), "client certificate (valid until " + until(client) + ")",
            "client certificate expired on " + until(client));
    check(signedBy(client, ca), "the client certificate is signed by that CA",
            "the client certificate isn't signed by that CA: are all three files from the same service?");
    check(belongTogether(key, client), "the private key (" + key.getAlgorithm() + ") belongs to the client certificate",
            "the private key doesn't belong to the client certificate");
    if (failed) {
        System.exit(1);
    }

    Map<String, String> settings = new LinkedHashMap<>();
    settings.put("KAFKA_SECURITY_PROTOCOL", "SSL");
    settings.put("KAFKA_SSL_CA_PEM", oneLine(certificatesPem(caCerts)));
    settings.put("KAFKA_SSL_CERT_PEM", oneLine(certificatesPem(clientCerts)));
    settings.put("KAFKA_SSL_KEY_PEM", oneLine(pem("PRIVATE KEY", key.getEncoded())));
    write(envFile, settings);
    ok("wrote " + String.join(", ", settings.keySet()) + " to " + envFile);
    IO.println("\nNext: delete the downloaded files. " + envFile + " (gitignored) now holds them.");
}

/** PKCS#8 as Kafka expects it. A PKCS#1 RSA key ("BEGIN RSA PRIVATE KEY") is converted. */
PrivateKey readKey(String text) throws Exception {
    Matcher m = PEM_BLOCK.matcher(text);
    if (!m.find()) {
        throw new IllegalArgumentException("unreadable private key file");
    }
    byte[] der = Base64.getMimeDecoder().decode(m.group(2));
    switch (m.group(1)) {
        case "PRIVATE KEY" -> { }
        case "RSA PRIVATE KEY" -> der = pkcs1ToPkcs8(der);
        case "ENCRYPTED PRIVATE KEY" -> throw new IllegalArgumentException(
                "the private key is password-protected; download the unencrypted Access key from Aiven");
        default -> throw new IllegalArgumentException("unsupported key type: " + m.group(1));
    }
    var spec = new PKCS8EncodedKeySpec(der);
    for (String algorithm : List.of("RSA", "EC", "Ed25519")) {
        try {
            return KeyFactory.getInstance(algorithm).generatePrivate(spec);
        } catch (Exception notThisAlgorithm) {
            // try the next one
        }
    }
    throw new IllegalArgumentException("unreadable private key");
}

/** Wraps a PKCS#1 RSAPrivateKey in a PKCS#8 PrivateKeyInfo: version 0, rsaEncryption, then the key. */
byte[] pkcs1ToPkcs8(byte[] pkcs1) {
    byte[] version = {0x02, 0x01, 0x00};
    byte[] rsaEncryption = {0x30, 0x0D, 0x06, 0x09, 0x2A, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xF7, 0x0D, 0x01,
            0x01, 0x01, 0x05, 0x00};
    byte[] octets = der(0x04, pkcs1);
    var body = new ByteArrayOutputStream();
    body.writeBytes(version);
    body.writeBytes(rsaEncryption);
    body.writeBytes(octets);
    return der(0x30, body.toByteArray());
}

byte[] der(int tag, byte[] content) {
    var out = new ByteArrayOutputStream();
    out.write(tag);
    int length = content.length;
    if (length < 0x80) {
        out.write(length);
    } else if (length <= 0xFF) {
        out.write(0x81);
        out.write(length);
    } else {
        out.write(0x82);
        out.write(length >> 8);
        out.write(length & 0xFF);
    }
    out.writeBytes(content);
    return out.toByteArray();
}

List<X509Certificate> readCertificates(String text) throws Exception {
    var factory = CertificateFactory.getInstance("X.509");
    List<X509Certificate> certs = new ArrayList<>();
    Matcher m = PEM_BLOCK.matcher(text);
    while (m.find()) {
        if (m.group(1).equals("CERTIFICATE")) {
            byte[] der = Base64.getMimeDecoder().decode(m.group(2));
            certs.add((X509Certificate) factory.generateCertificate(new ByteArrayInputStream(der)));
        }
    }
    return certs;
}

/** A CA certificate says so in its basic constraints; a client certificate doesn't. */
boolean isCa(X509Certificate cert) {
    return cert.getBasicConstraints() >= 0;
}

boolean stillValid(X509Certificate cert) {
    try {
        cert.checkValidity();
        return true;
    } catch (Exception e) {
        return false;
    }
}

String until(X509Certificate cert) {
    return LocalDate.ofInstant(cert.getNotAfter().toInstant(), ZoneOffset.UTC).toString();
}

boolean signedBy(X509Certificate cert, X509Certificate ca) {
    try {
        cert.verify(ca.getPublicKey());
        return true;
    } catch (Exception e) {
        return false;
    }
}

/** Signs random bytes with the key and verifies them with the certificate. */
boolean belongTogether(PrivateKey key, X509Certificate cert) {
    try {
        String algorithm = switch (key.getAlgorithm()) {
            case "RSA" -> "SHA256withRSA";
            case "EC" -> "SHA256withECDSA";
            default -> key.getAlgorithm();
        };
        byte[] data = new byte[32];
        new SecureRandom().nextBytes(data);
        Signature signer = Signature.getInstance(algorithm);
        signer.initSign(key);
        signer.update(data);
        byte[] signature = signer.sign();
        Signature verifier = Signature.getInstance(algorithm);
        verifier.initVerify(cert.getPublicKey());
        verifier.update(data);
        return verifier.verify(signature);
    } catch (Exception e) {
        return false;
    }
}

String certificatesPem(List<X509Certificate> certs) throws Exception {
    var out = new StringBuilder();
    for (X509Certificate cert : certs) {
        out.append(pem("CERTIFICATE", cert.getEncoded()));
    }
    return out.toString();
}

String pem(String type, byte[] der) {
    String body = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der);
    return "-----BEGIN " + type + "-----\n" + body + "\n-----END " + type + "-----\n";
}

/** Properties files read "\n" as a newline, so a PEM fits on one line. PEM text has no backslashes. */
String oneLine(String pem) {
    return pem.replace("\n", "\\n");
}

String currentValue(Path envFile, String name) throws Exception {
    Pattern line = Pattern.compile("^\\s*" + Pattern.quote(name) + "\\s*[=:]\\s*(.*)$");
    for (String l : Files.readAllLines(envFile, StandardCharsets.UTF_8)) {
        Matcher m = line.matcher(l);
        if (m.matches()) {
            return m.group(1);
        }
    }
    return null;
}

/** Replaces each setting's line, or appends it; writes a temp file first so .env is never half-written. */
void write(Path envFile, Map<String, String> settings) throws Exception {
    List<String> lines = new ArrayList<>(Files.readAllLines(envFile, StandardCharsets.UTF_8));
    for (var setting : settings.entrySet()) {
        Pattern line = Pattern.compile("^\\s*" + Pattern.quote(setting.getKey()) + "\\s*[=:].*$");
        String replacement = setting.getKey() + "=" + setting.getValue();
        boolean replaced = false;
        for (int i = 0; i < lines.size(); i++) {
            if (line.matcher(lines.get(i)).matches()) {
                lines.set(i, replacement);
                replaced = true;
            }
        }
        if (!replaced) {
            lines.add(replacement);
        }
    }
    // Keep the file's own line endings.
    String newline = Files.readString(envFile, StandardCharsets.UTF_8).contains("\r\n") ? "\r\n" : "\n";
    Path temp = Files.createTempFile(envFile.toAbsolutePath().getParent(), ".env", ".tmp");
    Files.writeString(temp, String.join(newline, lines) + newline, StandardCharsets.UTF_8);
    Files.move(temp, envFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
}

void check(boolean good, String okMessage, String failMessage) {
    if (good) {
        ok(okMessage);
    } else {
        fail(failMessage);
    }
}

void ok(String message) {
    IO.println("OK    " + message);
}

void fail(String message) {
    failed = true;
    IO.println("FAIL  " + message);
}
