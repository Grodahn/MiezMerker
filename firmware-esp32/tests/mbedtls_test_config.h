// Minimal host test build. ESP-IDF uses its own Mbed TLS configuration.
#define MBEDTLS_BIGNUM_C
#define MBEDTLS_ECP_C
#define MBEDTLS_ECP_DP_SECP256R1_ENABLED
#define MBEDTLS_ECDSA_C
#define MBEDTLS_ASN1_PARSE_C
#define MBEDTLS_ASN1_WRITE_C
#define MBEDTLS_SHA256_C
#define MBEDTLS_BASE64_C
#define MBEDTLS_PLATFORM_C
