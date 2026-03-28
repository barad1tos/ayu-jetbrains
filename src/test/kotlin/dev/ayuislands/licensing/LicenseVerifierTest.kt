package dev.ayuislands.licensing

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.KeyPair
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

class LicenseVerifierTest {
    private lateinit var verifier: LicenseVerifier

    @BeforeTest
    fun setUp() {
        verifier = LicenseVerifier()
    }

    // Group 1: isKeyValid - format rejection (no crypto needed)

    @Test
    fun `isKeyValid returns false for empty string`() {
        assertFalse(verifier.isKeyValid(""))
    }

    @Test
    fun `isKeyValid returns false for single part`() {
        assertFalse(verifier.isKeyValid("onlyone"))
    }

    @Test
    fun `isKeyValid returns false for two dash-separated parts`() {
        assertFalse(verifier.isKeyValid("a-b"))
    }

    @Test
    fun `isKeyValid returns false for three dash-separated parts`() {
        assertFalse(verifier.isKeyValid("a-b-c"))
    }

    @Test
    fun `isKeyValid returns false for five dash-separated parts`() {
        assertFalse(verifier.isKeyValid("a-b-c-d-e"))
    }

    @Test
    fun `isKeyValid returns false for invalid Base64 in signature field`() {
        val licenseBase64 = Base64.getEncoder().encodeToString("""{"licenseId":"LID1"}""".toByteArray())
        assertFalse(verifier.isKeyValid("LID1-$licenseBase64-!!!NOT_BASE64!!!-certpart"))
    }

    @Test
    fun `isKeyValid returns false for invalid Base64 in cert field`() {
        val licenseBase64 = Base64.getEncoder().encodeToString("""{"licenseId":"LID1"}""".toByteArray())
        val signatureBase64 = Base64.getEncoder().encodeToString(ByteArray(16))
        assertFalse(verifier.isKeyValid("LID1-$licenseBase64-$signatureBase64-!!!NOT_BASE64!!!"))
    }

    // Group 2: isKeyValid - crypto validation

    @Test
    fun `isKeyValid returns false when cert not signed by root CA`() {
        val keyPair = loadTestCaKeyPair()
        val cert = loadTestCaCert()
        val licenseId = "TEST-LICENSE-001"
        val licenseJson = """{"licenseId":"$licenseId"}"""
        val licenseBytes = licenseJson.toByteArray(StandardCharsets.UTF_8)
        val licenseBase64 = Base64.getEncoder().encodeToString(licenseBytes)

        val signature = Signature.getInstance("SHA1withRSA")
        signature.initSign(keyPair.private)
        signature.update(licenseBytes)
        val signatureBase64 = Base64.getEncoder().encodeToString(signature.sign())

        val certBase64 = Base64.getEncoder().encodeToString(cert.encoded)

        // Self-signed test CA cert is not in PRODUCTION_ROOT_CAS
        assertFalse(verifier.isKeyValid("$licenseId-$licenseBase64-$signatureBase64-$certBase64"))
    }

    @Test
    fun `isKeyValid returns false when licenseId mismatch`() {
        val keyPair = loadTestCaKeyPair()
        val cert = loadTestCaCert()
        val licenseJson = """{"licenseId":"REAL-ID"}"""
        val licenseBytes = licenseJson.toByteArray(StandardCharsets.UTF_8)
        val licenseBase64 = Base64.getEncoder().encodeToString(licenseBytes)

        val signature = Signature.getInstance("SHA1withRSA")
        signature.initSign(keyPair.private)
        signature.update(licenseBytes)
        val signatureBase64 = Base64.getEncoder().encodeToString(signature.sign())

        val certBase64 = Base64.getEncoder().encodeToString(cert.encoded)

        // licenseId in path ("WRONG-ID") does not match JSON ("REAL-ID")
        assertFalse(verifier.isKeyValid("WRONG-ID-$licenseBase64-$signatureBase64-$certBase64"))
    }

    // Group 3: isStampValid - format rejection

    @Test
    fun `isStampValid returns false for empty string`() {
        assertFalse(verifier.isStampValid(""))
    }

    @Test
    fun `isStampValid returns false for too few colon-separated parts`() {
        assertFalse(verifier.isStampValid("a:b:c"))
    }

    @Test
    fun `isStampValid returns false for four colon-separated parts`() {
        assertFalse(verifier.isStampValid("a:b:c:d"))
    }

    @Test
    fun `isStampValid returns false for non-numeric timestamp`() {
        val cert = loadTestCaCert()
        val certBase64 = Base64.getEncoder().encodeToString(cert.encoded)
        val sigBase64 = Base64.getEncoder().encodeToString(ByteArray(16))
        assertFalse(verifier.isStampValid("notanumber:mid:0:$sigBase64:$certBase64"))
    }

    // Group 4: isStampValid - timestamp window (frozen timeSource)

    @Test
    fun `isStampValid returns false for timestamp older than one hour`() {
        val now = 1_700_000_000_000L
        val twoHoursAgo = now - 2 * LicenseVerifier.TIMESTAMP_VALIDITY_PERIOD_MS
        val frozenVerifier = LicenseVerifier(timeSource = { now })

        val stamp = buildFakeStamp(twoHoursAgo, loadTestCaPrivateKey(), loadTestCaCert())
        assertFalse(frozenVerifier.isStampValid(stamp))
    }

    @Test
    fun `isStampValid returns false for timestamp more than one hour in future`() {
        val now = 1_700_000_000_000L
        val twoHoursFromNow = now + 2 * LicenseVerifier.TIMESTAMP_VALIDITY_PERIOD_MS
        val frozenVerifier = LicenseVerifier(timeSource = { now })

        val stamp = buildFakeStamp(twoHoursFromNow, loadTestCaPrivateKey(), loadTestCaCert())
        assertFalse(frozenVerifier.isStampValid(stamp))
    }

    // Group 5: isStampValid - crypto validation

    @Test
    fun `isStampValid returns false when cert not signed by root CA`() {
        val now = System.currentTimeMillis()
        val stamp = buildFakeStamp(now, loadTestCaPrivateKey(), loadTestCaCert())
        assertFalse(verifier.isStampValid(stamp))
    }

    @Test
    fun `isStampValid returns false for invalid signature bytes`() {
        val cert = loadTestCaCert()
        val certBase64 = Base64.getEncoder().encodeToString(cert.encoded)
        val garbageSig = Base64.getEncoder().encodeToString(ByteArray(256) { 0xFF.toByte() })
        val stamp = "${System.currentTimeMillis()}:machine123:0:$garbageSig:$certBase64"
        assertFalse(verifier.isStampValid(stamp))
    }

    // Group 6: createAndValidateCertificate - cert chain

    @Test
    fun `createAndValidateCertificate throws for self-signed cert not in trust anchors`() {
        val cert = loadTestCaCert()
        assertFailsWith<CertPathValidatorException> {
            verifier.createAndValidateCertificate(cert.encoded, emptyList())
        }
    }

    // Group 7: Constructor injection

    @Test
    fun `custom timeSource is used for stamp validation`() {
        val fixedTime = 1_700_000_000_000L
        val frozenVerifier = LicenseVerifier(timeSource = { fixedTime })

        val privateKey = loadTestCaPrivateKey()
        val cert = loadTestCaCert()
        val withinWindowStamp = buildFakeStamp(fixedTime, privateKey, cert)
        val outsideWindowStamp = buildFakeStamp(fixedTime - 5_000_000L, privateKey, cert)

        // Both fail (cert not trusted), confirming the frozen verifier uses injected time
        assertFalse(frozenVerifier.isStampValid(withinWindowStamp))
        assertFalse(frozenVerifier.isStampValid(outsideWindowStamp))
    }

    @Test
    fun `empty rootCertificates causes cert validation failure`() {
        val emptyRootsVerifier = LicenseVerifier(rootCertificates = emptyList())
        val cert = loadTestCaCert()

        assertFailsWith<Exception> {
            emptyRootsVerifier.createAndValidateCertificate(cert.encoded, emptyList())
        }
    }

    @Test
    fun `custom rootCertificates with matching CA validates cert chain`() {
        val caPem = TEST_CA_PEM
        val customVerifier = LicenseVerifier(rootCertificates = listOf(caPem))

        val leafCert = loadTestLeafCert()
        val result = customVerifier.createAndValidateCertificate(leafCert.encoded, emptyList())
        assertNotNull(result)
    }

    @Test
    fun `isStampValid with custom rootCertificates and valid stamp within time window returns true`() {
        val now = 1_700_000_000_000L
        val caPem = TEST_CA_PEM
        val customVerifier = LicenseVerifier(
            timeSource = { now },
            rootCertificates = listOf(caPem),
        )

        val leafPrivateKey = loadTestLeafPrivateKey()
        val leafCert = loadTestLeafCert()
        val stamp = buildFakeStamp(now, leafPrivateKey, leafCert)

        // Valid stamp: cert is trusted by custom CA, timestamp within window, signature matches
        assert(customVerifier.isStampValid(stamp))
    }

    @Test
    fun `isStampValid with custom rootCertificates but expired timestamp returns false`() {
        val now = 1_700_000_000_000L
        val oldTimestamp = now - 2 * LicenseVerifier.TIMESTAMP_VALIDITY_PERIOD_MS
        val caPem = TEST_CA_PEM
        val customVerifier = LicenseVerifier(
            timeSource = { now },
            rootCertificates = listOf(caPem),
        )

        val leafPrivateKey = loadTestLeafPrivateKey()
        val leafCert = loadTestLeafCert()
        val stamp = buildFakeStamp(oldTimestamp, leafPrivateKey, leafCert)

        // Cert is trusted, signature valid, but timestamp is outside window
        assertFalse(customVerifier.isStampValid(stamp))
    }

    // Test helpers

    private fun buildFakeStamp(
        timestamp: Long,
        privateKey: PrivateKey,
        cert: X509Certificate,
    ): String {
        val machineId = "test-machine-id"
        val signatureType = "0"
        val dataToSign = "$timestamp:$machineId"

        val signature = Signature.getInstance("SHA1withRSA")
        signature.initSign(privateKey)
        signature.update(dataToSign.toByteArray(StandardCharsets.UTF_8))
        val signatureBase64 = Base64.getEncoder().encodeToString(signature.sign())

        val certBase64 = Base64.getEncoder().encodeToString(cert.encoded)

        return "$timestamp:$machineId:$signatureType:$signatureBase64:$certBase64"
    }

    private fun loadTestCaCert(): X509Certificate = parsePemCert(TEST_CA_PEM)

    private fun loadTestLeafCert(): X509Certificate = parsePemCert(TEST_LEAF_PEM)

    private fun loadTestCaPrivateKey(): PrivateKey = parsePkcs8Key(TEST_CA_PRIVATE_KEY_PKCS8)

    private fun loadTestLeafPrivateKey(): PrivateKey = parsePkcs8Key(TEST_LEAF_PRIVATE_KEY_PKCS8)

    private fun loadTestCaKeyPair(): KeyPair {
        val cert = loadTestCaCert()
        val privateKey = loadTestCaPrivateKey()
        return KeyPair(cert.publicKey, privateKey)
    }

    private fun parsePemCert(pem: String): X509Certificate {
        val factory = CertificateFactory.getInstance("X.509")
        val bytes = pem.toByteArray(StandardCharsets.UTF_8)
        return factory.generateCertificate(ByteArrayInputStream(bytes)) as X509Certificate
    }

    private fun parsePkcs8Key(pkcs8Pem: String): PrivateKey {
        val base64 = pkcs8Pem
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replace("\\s".toRegex(), "")
        val keyBytes = Base64.getDecoder().decode(base64)
        val keySpec = PKCS8EncodedKeySpec(keyBytes)
        return KeyFactory.getInstance("RSA").generatePrivate(keySpec)
    }

    companion object {
        private const val RESOURCE_PATH = "dev/ayuislands/licensing"

        private val TEST_CA_PEM = loadResource("$RESOURCE_PATH/test-ca.pem")
        private val TEST_LEAF_PEM = loadResource("$RESOURCE_PATH/test-leaf.pem")
        private val TEST_CA_PRIVATE_KEY_PKCS8 = loadResource("$RESOURCE_PATH/test-ca-key.pk8")
        private val TEST_LEAF_PRIVATE_KEY_PKCS8 = loadResource("$RESOURCE_PATH/test-leaf-key.pk8")

        private fun loadResource(path: String): String =
            LicenseVerifierTest::class.java.classLoader
                .getResourceAsStream(path)!!
                .bufferedReader(StandardCharsets.UTF_8)
                .readText()
                .trim()
    }
}
