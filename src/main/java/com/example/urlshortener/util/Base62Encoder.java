package com.example.urlshortener.util;

/**
 * ==============================================================================
 * Base62Encoder — converts a numeric database ID into a short, URL-safe
 * alphanumeric string, and back again.
 * ==============================================================================
 * WHY BASE62 (AND NOT, SAY, JUST USING THE RAW NUMERIC ID AS THE SHORT
 * CODE)?
 *   "Base62" means we use a 62-character alphabet: the 10 digits (0-9) plus
 *   26 lowercase letters (a-z) plus 26 uppercase letters (A-Z) = 62 symbols
 *   total. Compare this to plain decimal (Base10), which only has 10
 *   symbols.
 *
 *   The more symbols in our alphabet, the FEWER characters we need to
 *   represent the same number — because each additional character
 *   multiplies the number of representable values by the base instead of
 *   by 10. Concretely:
 *       - Base10: id 1,000,000        -> "1000000"    (7 characters)
 *       - Base62: id 1,000,000        -> "4c92"        (4 characters)
 *   For a URL shortener, shorter is the entire point — "bit.ly/4c92" is
 *   much more shareable than "bit.ly/1000000".
 *
 * WHY THIS IS COLLISION-SAFE WITHOUT ANY EXTRA COORDINATION:
 *   Base62 encoding is a pure, DETERMINISTIC, ONE-TO-ONE (bijective)
 *   mapping between non-negative integers and strings in this alphabet —
 *   exactly like how decimal-to-hexadecimal is a 1:1 mapping. Two
 *   DIFFERENT input numbers can NEVER produce the SAME Base62 string, and
 *   the encoding can always be reversed (decode) back to the exact original
 *   number. Since our database's AUTO_INCREMENT column already guarantees
 *   every `id` is unique (MySQL serializes concurrent inserts internally),
 *   Base62-encoding that id automatically guarantees every shortCode is
 *   unique too — with ZERO extra locking, hashing, or "check if taken,
 *   retry" logic required anywhere in our application.
 *   (This is fundamentally different from, e.g., taking an MD5 hash of the
 *   URL and truncating it — THAT approach can genuinely collide and needs
 *   a "check-and-retry" loop. Our approach mathematically cannot.)
 * ==============================================================================
 */
public final class Base62Encoder {

    // The 62-character alphabet. Index 0 = '0', index 61 = 'Z'.
    // ORDER MATTERS: encode() and decode() must agree on exactly this
    // ordering, or round-tripping would silently produce wrong results.
    private static final String ALPHABET =
            "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";

    private static final int BASE = ALPHABET.length(); // 62

    // Private constructor: this class is a stateless utility (all static
    // methods) and should never be instantiated — enforced at compile time
    // by making the class `final` and hiding its only constructor.
    private Base62Encoder() {
    }

    /**
     * encode(id) — converts a non-negative long (the database's
     * auto-increment primary key) into its Base62 string representation.
     *
     * ALGORITHM (identical in spirit to converting decimal to any other
     * base by hand):
     *   Repeatedly divide the number by 62. At each step, the REMAINDER
     *   (0-61) tells us which character from our alphabet to use, and the
     *   QUOTIENT becomes the new number we continue dividing. We stop once
     *   the number reaches 0. Because we generate digits least-significant
     *   first, we build the string back-to-front (prepending each
     *   character) or reverse the StringBuilder at the end — we do the
     *   latter here for efficiency (StringBuilder.append is O(1) amortized,
     *   while repeated prepending would be O(n) per call).
     *
     * EXAMPLE WALKTHROUGH for id = 125 :
     *   125 / 62 = 2 remainder 1   -> alphabet[1] = '1'
     *     2 / 62 = 0 remainder 2   -> alphabet[2] = '2'
     *   (quotient is now 0, stop)
     *   digits collected in order generated: ['1', '2']
     *   reversed -> "21"
     *   So id 125 becomes the short code "21".
     */
    public static String encode(long id) {
        if (id < 0) {
            // We never expect a negative ID from an AUTO_INCREMENT column,
            // but we guard explicitly because a negative number breaks the
            // division/remainder logic below (and silently producing a
            // wrong/ambiguous code for invalid input would be worse than
            // failing loudly).
            throw new IllegalArgumentException("id must be non-negative, got: " + id);
        }
        if (id == 0) {
            // Special case: the loop below never executes for 0 (0 / 62 = 0,
            // loop condition `id > 0` is immediately false), so without this
            // explicit check we'd return an empty string instead of "0".
            return String.valueOf(ALPHABET.charAt(0));
        }

        StringBuilder sb = new StringBuilder();
        long remaining = id;
        while (remaining > 0) {
            int remainder = (int) (remaining % BASE);
            sb.append(ALPHABET.charAt(remainder));
            remaining = remaining / BASE;
        }
        // We built the string least-significant-digit-first, so reverse it
        // to get the conventional most-significant-digit-first representation.
        return sb.reverse().toString();
    }

    /**
     * decode(shortCode) — the inverse of encode(): given a Base62 string,
     * recover the original numeric database id. Not currently used by the
     * redirect flow (which looks short codes up directly via
     * findByShortCode in the database — see UrlServiceImpl design notes),
     * but included because:
     *   (a) it's the natural counterpart that proves encode() is truly
     *       reversible/collision-free, and
     *   (b) it is genuinely useful — e.g. for debugging ("which database
     *       row does short code 'b7F3a' correspond to?") or for an
     *       alternative redirect implementation that decodes-then-looks-up-
     *       by-primary-key instead of looking up by the short_code column
     *       (a valid alternative design, trading the short_code index for a
     *       primary key lookup — discussed in the README interview Q&A).
     *
     * ALGORITHM: process the string left to right; at each character, shift
     * the accumulated result left by one "digit" in base 62 (multiply by
     * 62) and add in the new character's alphabet position. This is the
     * standard "Horner's method" for parsing a number in any base.
     *
     * EXAMPLE for "21":
     *   result = 0
     *   char '2' -> index 2 -> result = 0*62 + 2 = 2
     *   char '1' -> index 1 -> result = 2*62 + 1 = 125
     *   -> returns 125, matching the encode() example above.
     */
    public static long decode(String shortCode) {
        if (shortCode == null || shortCode.isEmpty()) {
            throw new IllegalArgumentException("shortCode must not be null or empty");
        }
        long result = 0;
        for (int i = 0; i < shortCode.length(); i++) {
            char c = shortCode.charAt(i);
            int digitValue = ALPHABET.indexOf(c);
            if (digitValue < 0) {
                // The character isn't in our alphabet at all — this short
                // code could never have been produced by encode(), so it's
                // either corrupted input or a malicious/guessed URL.
                throw new IllegalArgumentException("Invalid character in shortCode: " + c);
            }
            result = result * BASE + digitValue;
        }
        return result;
    }
}
