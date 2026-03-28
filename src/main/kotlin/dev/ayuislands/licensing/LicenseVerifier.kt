package dev.ayuislands.licensing

// Cryptographic verification adapted from marketplace-makemecoffee-plugin (Apache 2.0).
// Manual RSA verification is required per JetBrains docs — LicensingFacade does not verify stamps.

import com.google.gson.JsonParser
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.security.Signature
import java.security.cert.CertPathValidator
import java.security.cert.CertificateFactory
import java.security.cert.PKIXParameters
import java.security.cert.TrustAnchor
import java.security.cert.X509Certificate
import java.util.Base64

class LicenseVerifier(
    private val timeSource: () -> Long = System::currentTimeMillis,
    private val rootCertificates: List<String> = PRODUCTION_ROOT_CAS,
) {
    @Suppress("TooGenericExceptionCaught") // Corrupted data can throw any exception; must not crash IDE
    fun isKeyValid(keyString: String): Boolean {
        try {
            val parts = keyString.split("-")
            if (parts.size != KEY_PARTS_COUNT) return false

            val licenseId = parts[0]
            val licensePartBase64 = parts[1]
            val signatureBase64 = parts[2]
            val certBase64 = parts[KEY_CERT_INDEX]

            val signatureBytes = Base64.getDecoder().decode(signatureBase64)
            val certBytes = Base64.getDecoder().decode(certBase64)
            val licenseBytes = Base64.getDecoder().decode(licensePartBase64)

            val cert = createAndValidateCertificate(certBytes, emptyList())

            val signature = Signature.getInstance("SHA1withRSA")
            signature.initVerify(cert)
            signature.update(licenseBytes)

            if (!signature.verify(signatureBytes)) return false

            val licenseData = String(licenseBytes, StandardCharsets.UTF_8)
            val json = JsonParser.parseString(licenseData).asJsonObject
            return json["licenseId"]?.asString == licenseId
        } catch (_: Exception) {
            return false
        }
    }

    @Suppress("TooGenericExceptionCaught") // Corrupted data can throw any exception; must not crash IDE
    fun isStampValid(serverStamp: String): Boolean {
        try {
            val parts = serverStamp.split(":")
            // Expected format: timestamp:machineId:signatureType:signatureBase64:certBase64[:intermediateCertBase64]*
            if (parts.size < STAMP_MIN_PARTS) return false

            val timestamp = parts[0].toLongOrNull() ?: return false
            val machineId = parts[1]
            val signatureType = parts[2]
            val signatureBase64 = parts[STAMP_SIGNATURE_INDEX]
            val certBase64 = parts[STAMP_CERT_INDEX]
            val intermediateCertsBase64 =
                if (parts.size > STAMP_MIN_PARTS) {
                    parts.subList(STAMP_INTERMEDIATE_START_INDEX, parts.size)
                } else {
                    emptyList()
                }

            val signatureBytes = Base64.getDecoder().decode(signatureBase64)
            val certBytes = Base64.getDecoder().decode(certBase64)
            val intermediateCertsBytes = intermediateCertsBase64.map { Base64.getDecoder().decode(it) }

            val cert = createAndValidateCertificate(certBytes, intermediateCertsBytes)

            val algorithmName = if (signatureType == "0") "SHA1withRSA" else "SHA256withRSA"
            val signature = Signature.getInstance(algorithmName)
            signature.initVerify(cert)

            // Data to verify: timestamp + machineId + PRODUCT_CODE
            val dataToVerify = "$timestamp:$machineId"
            signature.update(dataToVerify.toByteArray(StandardCharsets.UTF_8))

            if (!signature.verify(signatureBytes)) return false

            // Timestamp must be within the validity period (allow negative clock drift)
            val timeDiff = timeSource() - timestamp
            return timeDiff in -TIMESTAMP_VALIDITY_PERIOD_MS..TIMESTAMP_VALIDITY_PERIOD_MS
        } catch (_: Exception) {
            return false
        }
    }

    fun createAndValidateCertificate(
        certBytes: ByteArray,
        intermediateCertsBytes: Collection<ByteArray>,
    ): X509Certificate {
        val factory = CertificateFactory.getInstance("X.509")

        val cert =
            factory.generateCertificate(
                ByteArrayInputStream(certBytes),
            ) as X509Certificate

        val intermediateCerts =
            intermediateCertsBytes.map { bytes ->
                factory.generateCertificate(ByteArrayInputStream(bytes)) as X509Certificate
            }

        // Build trust anchors from root certificates
        val trustAnchors =
            rootCertificates
                .map { rootCertPem ->
                    val rootCert =
                        factory.generateCertificate(
                            ByteArrayInputStream(rootCertPem.toByteArray(StandardCharsets.UTF_8)),
                        ) as X509Certificate
                    TrustAnchor(rootCert, null)
                }.toSet()

        // Build cert path: leaf + intermediates
        val allCerts = listOf(cert) + intermediateCerts
        val certPath = factory.generateCertPath(allCerts)

        // Validate using PKIX
        val params = PKIXParameters(trustAnchors)
        params.isRevocationEnabled = false

        val validator = CertPathValidator.getInstance("PKIX")
        validator.validate(certPath, params)

        cert.checkValidity()
        return cert
    }

    companion object {
        // JetBrains public root CAs for license verification.
        // Source: marketplace-makemecoffee-plugin (Apache 2.0), the official JetBrains licensing example.
        val PRODUCTION_ROOT_CAS: List<String> =
            listOf(
                // Certificate 1: JetProfile CA
                "-----BEGIN CERTIFICATE-----\n" +
                    "MIIFOzCCAyOgAwIBAgIJANJssYOyg3nhMA0GCSqGSIb3DQEBCwUAMBgxFjAUBgNV\n" +
                    "BAMMDUpldFByb2ZpbGUgQ0EwHhcNMTUxMDAyMTEwMDU2WhcNNDUxMDI0MTEwMDU2\n" +
                    "WjAYMRYwFAYDVQQDDA1KZXRQcm9maWxlIENBMIICIjANBgkqhkiG9w0BAQEFAAOC\n" +
                    "Ag8AMIICCgKCAgEA0tQuEA8784NabB1+T2XBhpB+2P1qjewHiSajAV8dfIeWJOYG\n" +
                    "y+ShXiuedj8rL8VCdU+yH7Ux/6IvTcT3nwM/E/3rjJIgLnbZNerFm15Eez+XpWBl\n" +
                    "m5fDBJhEGhPc89Y31GpTzW0vCLmhJ44XwvYPntWxYISUrqeR3zoUQrCEp1C6mXNX\n" +
                    "EpqIGIVbJ6JVa/YI+pwbfuP51o0ZtF2rzvgfPzKtkpYQ7m7KgA8g8ktRXyNrz8bo\n" +
                    "iwg7RRPeqs4uL/RK8d2KLpgLqcAB9WDpcEQzPWegbDrFO1F3z4UVNH6hrMfOLGVA\n" +
                    "xoiQhNFhZj6RumBXlPS0rmCOCkUkWrDr3l6Z3spUVgoeea+QdX682j6t7JnakaOw\n" +
                    "jzwY777SrZoi9mFFpLVhfb4haq4IWyKSHR3/0BlWXgcgI6w6LXm+V+ZgLVDON52F\n" +
                    "LcxnfftaBJz2yclEwBohq38rYEpb+28+JBvHJYqcZRaldHYLjjmb8XXvf2MyFeXr\n" +
                    "SopYkdzCvzmiEJAewrEbPUaTllogUQmnv7Rv9sZ9jfdJ/cEn8e7GSGjHIbnjV2ZM\n" +
                    "Q9vTpWjvsT/cqatbxzdBo/iEg5i9yohOC9aBfpIHPXFw+fEj7VLvktxZY6qThYXR\n" +
                    "Rus1WErPgxDzVpNp+4gXovAYOxsZak5oTV74ynv1aQ93HSndGkKUE/qA/JECAwEA\n" +
                    "AaOBhzCBhDAdBgNVHQ4EFgQUo562SGdCEjZBvW3gubSgUouX8bMwSAYDVR0jBEEw\n" +
                    "P4AUo562SGdCEjZBvW3gubSgUouX8bOhHKQaMBgxFjAUBgNVBAMMDUpldFByb2Zp\n" +
                    "bGUgQ0GCCQDSbLGDsoN54TAMBgNVHRMEBTADAQH/MAsGA1UdDwQEAwIBBjANBgkq\n" +
                    "hkiG9w0BAQsFAAOCAgEAjrPAZ4xC7sNiSSqh69s3KJD3Ti4etaxcrSnD7r9rJYpK\n" +
                    "BMviCKZRKFbLv+iaF5JK5QWuWdlgA37ol7mLeoF7aIA9b60Ag2OpgRICRG79QY7o\n" +
                    "uLviF/yRMqm6yno7NYkGLd61e5Huu+BfT459MWG9RVkG/DY0sGfkyTHJS5xrjBV6\n" +
                    "hjLG0lf3orwqOlqSNRmhvn9sMzwAP3ILLM5VJC5jNF1zAk0jrqKz64vuA8PLJZlL\n" +
                    "S9TZJIYwdesCGfnN2AETvzf3qxLcGTF038zKOHUMnjZuFW1ba/12fDK5GJ4i5y+n\n" +
                    "fDWVZVUDYOPUixEZ1cwzmf9Tx3hR8tRjMWQmHixcNC8XEkVfztID5XeHtDeQ+uPk\n" +
                    "X+jTDXbRb+77BP6n41briXhm57AwUI3TqqJFvoiFyx5JvVWG3ZqlVaeU/U9e0gxn\n" +
                    "8qyR+ZA3BGbtUSDDs8LDnE67URzK+L+q0F2BC758lSPNB2qsJeQ63bYyzf0du3wB\n" +
                    "/gb2+xJijAvscU3KgNpkxfGklvJD/oDUIqZQAnNcHe7QEf8iG2WqaMJIyXZlW3me\n" +
                    "0rn+cgvxHPt6N4EBh5GgNZR4l0eaFEV+fxVsydOQYo1RIyFMXtafFBqQl6DDxujl\n" +
                    "FeU3FZ+Bcp12t7dlM4E0/sS1XdL47CfGVj4Bp+/VbF862HmkAbd7shs7sDQkHbU=\n" +
                    "-----END CERTIFICATE-----\n",
                // Certificate 2: License Servers CA
                "-----BEGIN CERTIFICATE-----\n" +
                    "MIIFTDCCAzSgAwIBAgIJAMCrW9HV+hjZMA0GCSqGSIb3DQEBCwUAMB0xGzAZBgNV\n" +
                    "BAMMEkxpY2Vuc2UgU2VydmVycyBDQTAgFw0xNjEwMTIxNDMwNTRaGA8yMTE2MTIy\n" +
                    "NzE0MzA1NFowHTEbMBkGA1UEAwwSTGljZW5zZSBTZXJ2ZXJzIENBMIICIjANBgkq\n" +
                    "hkiG9w0BAQEFAAOCAg8AMIICCgKCAgEAoT7LvHj3JKK2pgc5f02z+xEiJDcvlBi6\n" +
                    "fIwrg/504UaMx3xWXAE5CEPelFty+QPRJnTNnSxqKQQmg2s/5tMJpL9lzGwXaV7a\n" +
                    "rrcsEDbzV4el5mIXUnk77Bm/QVv48s63iQqUjVmvjQt9SWG2J7+h6X3ICRvF1sQB\n" +
                    "yeat/cO7tkpz1aXXbvbAws7/3dXLTgAZTAmBXWNEZHVUTcwSg2IziYxL8HRFOH0+\n" +
                    "GMBhHqa0ySmF1UTnTV4atIXrvjpABsoUvGxw+qOO2qnwe6ENEFWFz1a7pryVOHXg\n" +
                    "P+4JyPkI1hdAhAqT2kOKbTHvlXDMUaxAPlriOVw+vaIjIVlNHpBGhqTj1aqfJpLj\n" +
                    "qfDFcuqQSI4O1W5tVPRNFrjr74nDwLDZnOF+oSy4E1/WhL85FfP3IeQAIHdswNMJ\n" +
                    "y+RdkPZCfXzSUhBKRtiM+yjpIn5RBY+8z+9yeGocoxPf7l0or3YF4GUpud202zgy\n" +
                    "Y3sJqEsZksB750M0hx+vMMC9GD5nkzm9BykJS25hZOSsRNhX9InPWYYIi6mFm8QA\n" +
                    "2Dnv8wxAwt2tDNgqa0v/N8OxHglPcK/VO9kXrUBtwCIfZigO//N3hqzfRNbTv/ZO\n" +
                    "k9lArqGtcu1hSa78U4fuu7lIHi+u5rgXbB6HMVT3g5GQ1L9xxT1xad76k2EGEi3F\n" +
                    "9B+tSrvru70CAwEAAaOBjDCBiTAdBgNVHQ4EFgQUpsRiEz+uvh6TsQqurtwXMd4J\n" +
                    "8VEwTQYDVR0jBEYwRIAUpsRiEz+uvh6TsQqurtwXMd4J8VGhIaQfMB0xGzAZBgNV\n" +
                    "BAMMEkxpY2Vuc2UgU2VydmVycyBDQYIJAMCrW9HV+hjZMAwGA1UdEwQFMAMBAf8w\n" +
                    "CwYDVR0PBAQDAgEGMA0GCSqGSIb3DQEBCwUAA4ICAQCJ9+GQWvBS3zsgPB+1PCVc\n" +
                    "oG6FY87N6nb3ZgNTHrUMNYdo7FDeol2DSB4wh/6rsP9Z4FqVlpGkckB+QHCvqU+d\n" +
                    "rYPe6QWHIb1kE8ftTnwapj/ZaBtF80NWUfYBER/9c6To5moW63O7q6cmKgaGk6zv\n" +
                    "St2IhwNdTX0Q5cib9ytE4XROeVwPUn6RdU/+AVqSOspSMc1WQxkPVGRF7HPCoGhd\n" +
                    "vqebbYhpahiMWfClEuv1I37gJaRtsoNpx3f/jleoC/vDvXjAznfO497YTf/GgSM2\n" +
                    "LCnVtpPQQ2vQbOfTjaBYO2MpibQlYpbkbjkd5ZcO5U5PGrQpPFrWcylz7eUC3c05\n" +
                    "UVeygGIthsA/0hMCioYz4UjWTgi9NQLbhVkfmVQ5lCVxTotyBzoubh3FBz+wq2Qt\n" +
                    "iElsBrCMR7UwmIu79UYzmLGt3/gBdHxaImrT9SQ8uqzP5eit54LlGbvGekVdAL5l\n" +
                    "DFwPcSB1IKauXZvi1DwFGPeemcSAndy+Uoqw5XGRqE6jBxS7XVI7/4BSMDDRBz1u\n" +
                    "a+JMGZXS8yyYT+7HdsybfsZLvkVmc9zVSDI7/MjVPdk6h0sLn+vuPC1bIi5edoNy\n" +
                    "PdiG2uPH5eDO6INcisyPpLS4yFKliaO4Jjap7yzLU9pbItoWgCAYa2NpxuxHJ0tB\n" +
                    "7tlDFnvaRnQukqSG+VqNWg==\n" +
                    "-----END CERTIFICATE-----",
            )

        const val KEY_PARTS_COUNT = 4
        const val KEY_CERT_INDEX = 3
        const val STAMP_MIN_PARTS = 5
        const val STAMP_SIGNATURE_INDEX = 3
        const val STAMP_CERT_INDEX = 4
        const val STAMP_INTERMEDIATE_START_INDEX = 5
        const val TIMESTAMP_VALIDITY_PERIOD_MS = 3_600_000L
    }
}
