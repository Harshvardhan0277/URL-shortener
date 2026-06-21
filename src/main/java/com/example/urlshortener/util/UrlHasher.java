package com.example.urlshortener.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * ==============================================================================
 * UrlHasher — computes a fixed-length SHA-256 hash of an arbitrary URL
 * string, used to make "does this URL already have a short code?" lookups
 * fast and indexable.
 * ==============================================================================
 * WHY WE NEED THIS AT ALL (instead of just indexing the original_url column
 * directly):
 *   original_url is declared VARCHAR(2048) (see UrlMapping / schema.sql) to
 *   comfortably fit real-world URLs. MySQL's InnoDB storage engine has a
 *   maximum index key length (3072 bytes by default with the modern
 *   innodb_large_prefix behavior, but as little as 767 bytes on older
 *   configurations). With utf8mb4 (4 bytes per character), a VARCHAR(2048)
 *   column could require up to 8192 bytes per index entry — too large to
 *   index directly. Indexing only a PREFIX of the column (e.g. the first
 *   255 characters) is one common workaround, but it produces false
 *   "duplicate" matches for any two URLs that merely SHARE a long common
 *   prefix (e.g. the same domain with different query strings beyond
 *   character 255).
 *
 *   The standard, reliable fix: hash the full string into a SHORT,
 *   FIXED-LENGTH value (SHA-256 produces exactly 64 hex characters,
 *   regardless of input length) and index THAT instead. Two different
 *   URLs will, for all practical purposes, never produce the same SHA-256
 *   hash (a "collision" is astronomically unlikely — on the order of 1 in
 *   2^128 for a birthday-bound attack), so a hash match is an extremely
 *   strong signal of equality. We still defensively compare the actual
 *   original_url strings too when checking for a duplicate (see
 *   UrlMappingRepository.findFirstByOriginalUrlHashAndOriginalUrl), so
 *   correctness does not depend on hash collisions being literally
 *   impossible — only fast lookups do.
 * ==============================================================================
 */
public final class UrlHasher {

    private UrlHasher() {
        // Stateless utility class — never instantiated.
    }

    /**
     * sha256Hex
     * ------------------------------------------------------------------------
     * Returns the SHA-256 hash of the input string, encoded as a 64-character
     * lowercase hexadecimal string (2 hex characters per byte, 32 bytes of
     * raw hash output = 64 hex characters total — matching the
     * `original_url_hash CHAR(64)` column declared in schema.sql).
     */
    public static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));

            StringBuilder hex = new StringBuilder(hashBytes.length * 2);
            for (byte b : hashBytes) {
                // Format each byte as exactly 2 lowercase hex digits,
                // zero-padded (e.g. byte value 5 -> "05", not "5").
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is a mandatory algorithm every standard Java
            // implementation must provide (per the JDK specification), so
            // this branch is unreachable in practice. We wrap it as an
            // unchecked exception rather than forcing every caller to
            // handle a checked exception for a failure mode that cannot
            // actually occur on a compliant JVM.
            throw new IllegalStateException("SHA-256 algorithm not available on this JVM", e);
        }
    }
}
