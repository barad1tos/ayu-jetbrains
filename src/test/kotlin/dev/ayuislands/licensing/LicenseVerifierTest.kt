package dev.ayuislands.licensing

import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.Date
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LicenseVerifierTest {
    private lateinit var verifier: LicenseVerifier

    @BeforeTest
    fun setUp() {
        verifier = LicenseVerifier()
    }

    // Group 1: isKeyValid — format rejection (no crypto needed)

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
        val json = """{"licenseId":"LID1"}"""
        val licenseBase64 = Base64.getEncoder().encodeToString(json.toByteArray(StandardCharsets.UTF_8))
        assertFalse(verifier.isKeyValid("LID1-$licenseBase64-!!!NOT_BASE64!!!-certpart"))
    }

    @Test
    fun `isKeyValid returns false for invalid Base64 in cert field`() {
        val json = """{"licenseId":"LID1"}"""
        val licenseBase64 = Base64.getEncoder().encodeToString(json.toByteArray(StandardCharsets.UTF_8))
        val signatureBase64 = Base64.getEncoder().encodeToString(ByteArray(16))
        assertFalse(
            verifier.isKeyValid(
                "LID1-$licenseBase64-$signatureBase64-!!!NOT_BASE64!!!",
            ),
        )
    }

    // Group 2: isKeyValid — crypto validation

    @Test
    fun `isKeyValid returns false when cert not signed by root CA`() {
        val licenseId = "TEST-LICENSE-001"
        val licenseJson = """{"licenseId":"$licenseId"}"""
        val licenseBytes = licenseJson.toByteArray(StandardCharsets.UTF_8)
        val licenseBase64 = Base64.getEncoder().encodeToString(licenseBytes)

        val signature = Signature.getInstance("SHA1withRSA")
        signature.initSign(caKeyPair.private)
        signature.update(licenseBytes)
        val signatureBase64 = Base64.getEncoder().encodeToString(signature.sign())

        val certBase64 = Base64.getEncoder().encodeToString(caCert.encoded)

        // Self-signed test CA cert is not in PRODUCTION_ROOT_CAS
        assertFalse(
            verifier.isKeyValid(
                "$licenseId-$licenseBase64-$signatureBase64-$certBase64",
            ),
        )
    }

    @Test
    fun `isKeyValid returns false when licenseId mismatch`() {
        val licenseJson = """{"licenseId":"REAL-ID"}"""
        val licenseBytes = licenseJson.toByteArray(StandardCharsets.UTF_8)
        val licenseBase64 = Base64.getEncoder().encodeToString(licenseBytes)

        val signature = Signature.getInstance("SHA1withRSA")
        signature.initSign(caKeyPair.private)
        signature.update(licenseBytes)
        val signatureBase64 = Base64.getEncoder().encodeToString(signature.sign())

        val certBase64 = Base64.getEncoder().encodeToString(caCert.encoded)

        assertFalse(
            verifier.isKeyValid(
                "WRONG-ID-$licenseBase64-$signatureBase64-$certBase64",
            ),
        )
    }

    // Group 3: isStampValid — format rejection

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
        val certBase64 = Base64.getEncoder().encodeToString(caCert.encoded)
        val sigBase64 = Base64.getEncoder().encodeToString(ByteArray(16))
        assertFalse(
            verifier.isStampValid("not-a-number:mid:0:$sigBase64:$certBase64"),
        )
    }

    // Group 4: isStampValid — timestamp window (frozen timeSource)

    @Test
    fun `isStampValid returns false for timestamp older than one hour`() {
        val now = 1_700_000_000_000L
        val twoHoursAgo = now - 2 * LicenseVerifier.TIMESTAMP_VALIDITY_PERIOD_MS
        val frozenVerifier = LicenseVerifier(timeSource = { now })

        val stamp = buildFakeStamp(twoHoursAgo, caKeyPair.private, caCert)
        assertFalse(frozenVerifier.isStampValid(stamp))
    }

    @Test
    fun `isStampValid returns false for timestamp more than one hour in future`() {
        val now = 1_700_000_000_000L
        val twoHoursFromNow = now + 2 * LicenseVerifier.TIMESTAMP_VALIDITY_PERIOD_MS
        val frozenVerifier = LicenseVerifier(timeSource = { now })

        val stamp =
            buildFakeStamp(
                twoHoursFromNow,
                caKeyPair.private,
                caCert,
            )
        assertFalse(frozenVerifier.isStampValid(stamp))
    }

    // Group 5: isStampValid — crypto validation

    @Test
    fun `isStampValid returns false when cert not signed by root CA`() {
        val now = System.currentTimeMillis()
        val stamp = buildFakeStamp(now, caKeyPair.private, caCert)
        assertFalse(verifier.isStampValid(stamp))
    }

    @Test
    fun `isStampValid returns false for invalid signature bytes`() {
        val certBase64 = Base64.getEncoder().encodeToString(caCert.encoded)
        val garbageSig =
            Base64
                .getEncoder()
                .encodeToString(ByteArray(256) { 0xFF.toByte() })
        val stamp =
            "${System.currentTimeMillis()}:machine123:0:$garbageSig:$certBase64"
        assertFalse(verifier.isStampValid(stamp))
    }

    // Group 6: createAndValidateCertificate — cert chain

    @Test
    fun `createAndValidateCertificate throws for self-signed cert not in trust anchors`() {
        assertFailsWith<Exception> {
            verifier.createAndValidateCertificate(caCert.encoded, emptyList())
        }
    }

    // Group 7: Constructor injection

    @Test
    fun `custom timeSource is used for stamp validation`() {
        val fixedTime = 1_700_000_000_000L
        val frozenVerifier = LicenseVerifier(timeSource = { fixedTime })

        val withinStamp = buildFakeStamp(fixedTime, caKeyPair.private, caCert)
        val outsideStamp =
            buildFakeStamp(
                fixedTime - 5_000_000L,
                caKeyPair.private,
                caCert,
            )

        assertFalse(frozenVerifier.isStampValid(withinStamp))
        assertFalse(frozenVerifier.isStampValid(outsideStamp))
    }

    @Test
    fun `empty rootCertificates causes cert validation failure`() {
        val emptyRootsVerifier = LicenseVerifier(rootCertificates = emptyList())

        assertFailsWith<Exception> {
            emptyRootsVerifier.createAndValidateCertificate(
                caCert.encoded,
                emptyList(),
            )
        }
    }

    @Test
    fun `custom rootCertificates with matching CA validates cert chain`() {
        val caPem = caCertPem()
        val customVerifier = LicenseVerifier(rootCertificates = listOf(caPem))

        val result =
            customVerifier.createAndValidateCertificate(
                leafCert.encoded,
                emptyList(),
            )
        assertNotNull(result)
    }

    @Test
    fun `isStampValid with custom rootCertificates and valid stamp returns true`() {
        val now = 1_700_000_000_000L
        val caPem = caCertPem()
        val customVerifier =
            LicenseVerifier(
                timeSource = { now },
                rootCertificates = listOf(caPem),
            )

        val stamp = buildFakeStamp(now, leafKeyPair.private, leafCert)
        assertTrue(customVerifier.isStampValid(stamp))
    }

    @Test
    fun `isStampValid with custom rootCertificates but expired timestamp returns false`() {
        val now = 1_700_000_000_000L
        val old = now - 2 * LicenseVerifier.TIMESTAMP_VALIDITY_PERIOD_MS
        val caPem = caCertPem()
        val customVerifier =
            LicenseVerifier(
                timeSource = { now },
                rootCertificates = listOf(caPem),
            )

        val stamp = buildFakeStamp(old, leafKeyPair.private, leafCert)
        assertFalse(customVerifier.isStampValid(stamp))
    }

    // Helpers

    private fun buildFakeStamp(
        timestamp: Long,
        privateKey: PrivateKey,
        cert: X509Certificate,
    ): String {
        val machineId = "test-machine-id"
        val dataToSign = "$timestamp:$machineId"

        val signature = Signature.getInstance("SHA1withRSA")
        signature.initSign(privateKey)
        signature.update(dataToSign.toByteArray(StandardCharsets.UTF_8))
        val signatureBase64 = Base64.getEncoder().encodeToString(signature.sign())
        val certBase64 = Base64.getEncoder().encodeToString(cert.encoded)

        return "$timestamp:$machineId:0:$signatureBase64:$certBase64"
    }

    companion object {
        private val caKeyPair: KeyPair = generateKeyPair()
        private val leafKeyPair: KeyPair = generateKeyPair()

        private val caCert: X509Certificate =
            buildSelfSignedCert(caKeyPair, "CN=Test CA", isCA = true)
        private val leafCert: X509Certificate =
            buildSignedCert(leafKeyPair, "CN=Test Leaf", caKeyPair, caCert)

        private fun generateKeyPair(): KeyPair =
            KeyPairGenerator
                .getInstance("RSA")
                .apply { initialize(2048) }
                .generateKeyPair()

        /**
         * Build a self-signed X.509 v3 certificate using raw ASN.1 DER encoding.
         * Avoids sun.security.x509 (not exported in JPMS) and BouncyCastle.
         */
        @Suppress("SameParameterValue")
        private fun buildSelfSignedCert(
            keyPair: KeyPair,
            dn: String,
            isCA: Boolean = false,
        ): X509Certificate =
            buildCertDer(
                subjectDn = dn,
                issuerDn = dn,
                subjectPublicKey = keyPair.public.encoded,
                signerKey = keyPair.private,
                serial = BigInteger.valueOf(System.nanoTime()),
                isCA = isCA,
            )

        @Suppress("SameParameterValue")
        private fun buildSignedCert(
            subjectKeyPair: KeyPair,
            subjectDn: String,
            issuerKeyPair: KeyPair,
            issuerCert: X509Certificate,
        ): X509Certificate =
            buildCertDer(
                subjectDn = subjectDn,
                issuerDn = issuerCert.subjectX500Principal.name,
                subjectPublicKey = subjectKeyPair.public.encoded,
                signerKey = issuerKeyPair.private,
                serial = BigInteger.valueOf(System.nanoTime() + 1),
                isCA = false,
            )

        @Suppress("LongParameterList") // DER cert builder — params are all required fields
        private fun buildCertDer(
            subjectDn: String,
            issuerDn: String,
            subjectPublicKey: ByteArray,
            signerKey: PrivateKey,
            serial: BigInteger,
            isCA: Boolean,
        ): X509Certificate {
            val now = System.currentTimeMillis()
            val notBefore = Date(now - 60_000)
            val notAfter = Date(now + 365L * 24 * 60 * 60 * 1000)

            // SHA256withRSA OID: 1.2.840.113549.1.1.11
            val sha256WithRsa =
                byteArrayOf(
                    0x30,
                    0x0D,
                    0x06,
                    0x09,
                    0x2A.toByte(),
                    0x86.toByte(),
                    0x48,
                    0x86.toByte(),
                    0xF7.toByte(),
                    0x0D,
                    0x01,
                    0x01,
                    0x0B,
                    0x05,
                    0x00,
                )

            val tbsCert =
                buildTbsCertificate(
                    serial = serial,
                    signatureAlgorithm = sha256WithRsa,
                    issuerDn = issuerDn,
                    notBefore = notBefore,
                    notAfter = notAfter,
                    subjectDn = subjectDn,
                    subjectPublicKey = subjectPublicKey,
                    isCA = isCA,
                )

            val sig = Signature.getInstance("SHA256withRSA")
            sig.initSign(signerKey)
            sig.update(tbsCert)
            val signatureBytes = sig.sign()

            val signatureBitString = derBitString(signatureBytes)
            val certDer = derSequence(tbsCert + sha256WithRsa + signatureBitString)

            val factory = CertificateFactory.getInstance("X.509")
            return factory.generateCertificate(
                ByteArrayInputStream(certDer),
            ) as X509Certificate
        }

        @Suppress("LongParameterList") // ASN.1 TBS structure — all fields required by X.509 spec
        private fun buildTbsCertificate(
            serial: BigInteger,
            signatureAlgorithm: ByteArray,
            issuerDn: String,
            notBefore: Date,
            notAfter: Date,
            subjectDn: String,
            subjectPublicKey: ByteArray,
            isCA: Boolean,
        ): ByteArray {
            val version = derExplicit(0, derInteger(BigInteger.valueOf(2)))
            val serialNum = derInteger(serial)
            val issuer = encodeDn(issuerDn)
            val validity = derSequence(derUtcTime(notBefore) + derUtcTime(notAfter))
            val subject = encodeDn(subjectDn)

            var tbs =
                version + serialNum + signatureAlgorithm + issuer +
                    validity + subject + subjectPublicKey

            if (isCA) {
                val basicConstraints =
                    byteArrayOf(
                        0x30,
                        0x0F,
                        0x06,
                        0x03,
                        0x55,
                        0x1D,
                        0x13,
                        0x01,
                        0x01,
                        0xFF.toByte(),
                        0x04,
                        0x05,
                        0x30,
                        0x03,
                        0x01,
                        0x01,
                        0xFF.toByte(),
                    )
                val extensions = derExplicit(3, derSequence(basicConstraints))
                tbs += extensions
            }

            return derSequence(tbs)
        }

        private fun encodeDn(dn: String): ByteArray {
            val cn = dn.removePrefix("CN=")
            val cnValue = derUtf8String(cn)
            val oid = byteArrayOf(0x06, 0x03, 0x55, 0x04, 0x03)
            val atv = derSequence(oid + cnValue)
            val rdn = derSet(atv)
            return derSequence(rdn)
        }

        private fun derSequence(content: ByteArray): ByteArray = byteArrayOf(0x30) + derLength(content.size) + content

        private fun derSet(content: ByteArray): ByteArray = byteArrayOf(0x31) + derLength(content.size) + content

        private fun derInteger(value: BigInteger): ByteArray {
            val bytes = value.toByteArray()
            return byteArrayOf(0x02) + derLength(bytes.size) + bytes
        }

        private fun derBitString(content: ByteArray): ByteArray {
            val payload = byteArrayOf(0x00) + content
            return byteArrayOf(0x03) + derLength(payload.size) + payload
        }

        private fun derUtf8String(value: String): ByteArray {
            val bytes = value.toByteArray(StandardCharsets.UTF_8)
            return byteArrayOf(0x0C) + derLength(bytes.size) + bytes
        }

        private fun derExplicit(
            tag: Int,
            content: ByteArray,
        ): ByteArray = byteArrayOf((0xA0 + tag).toByte()) + derLength(content.size) + content

        @Suppress("MagicNumber")
        private fun derUtcTime(date: Date): ByteArray {
            val fmt = java.text.SimpleDateFormat("yyMMddHHmmss'Z'")
            fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
            val bytes = fmt.format(date).toByteArray(StandardCharsets.US_ASCII)
            return byteArrayOf(0x17) + derLength(bytes.size) + bytes
        }

        @Suppress("MagicNumber")
        private fun derLength(length: Int): ByteArray =
            when {
                length < 128 -> byteArrayOf(length.toByte())
                length < 256 -> byteArrayOf(0x81.toByte(), length.toByte())
                else ->
                    byteArrayOf(
                        0x82.toByte(),
                        (length shr 8).toByte(),
                        (length and 0xFF).toByte(),
                    )
            }

        private fun caCertPem(): String {
            val base64 =
                Base64
                    .getMimeEncoder(64, "\n".toByteArray())
                    .encodeToString(caCert.encoded)
            return "-----BEGIN CERTIFICATE-----\n$base64\n-----END CERTIFICATE-----"
        }
    }
}
