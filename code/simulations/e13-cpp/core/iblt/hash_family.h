#pragma once

#include <vector>
#include <string>
#include <cstdint>
#include <memory>

namespace sciblt {

/**
 * HashFamily provides k-uniform hash functions for IBLT.
 * 
 * Key features:
 * - Guarantees k hash positions are always distinct (k-uniform property)
 * - Uses SHA256-based hashing for consistency with Python implementation
 * - Supports seeded and unseeded modes
 * - Implements rejection sampling with fallback for collision resolution
 * 
 * Critical implementation note:
 * This class MUST maintain numerical consistency with the Python version in
 * history/code/.backup/three_message_iblt.py:70-123
 */
class HashFamily {
public:
    /**
     * Constructor
     * @param k Number of hash functions (typically 3)
     * @param seed Random seed. If -1, uses default deterministic salts.
     *             If >= 0, generates random salts with the given seed.
     */
    explicit HashFamily(int k = 3, int seed = -1);
    
    // Disable copy to avoid accidental duplication
    HashFamily(const HashFamily&) = delete;
    HashFamily& operator=(const HashFamily&) = delete;
    
    // Enable move
    HashFamily(HashFamily&&) noexcept = default;
    HashFamily& operator=(HashFamily&&) noexcept = default;
    
    virtual ~HashFamily() = default;

    /**
     * Compute k distinct hash positions for a key.
     * 
     * This method guarantees that all k positions are distinct (k-uniform).
     * Algorithm:
     * 1. Compute initial k positions using SHA256(salt_i + key)
     * 2. If collision detected, retry with modified salt up to MAX_RETRIES times
     * 3. If still failing, use deterministic deduplication + linear probing
     * 
     * @param key The key to hash
     * @param bucket_count Number of buckets (M)
     * @return Vector of k distinct positions in [0, bucket_count)
     */
    virtual std::vector<int> hash_positions(int key, int bucket_count) const;

    /**
     * Compute first checksum for a key
     * @param key The key to hash
     * @return 64-bit checksum
     */
    virtual uint64_t hash_checksum_1(int key) const;

    /**
     * Compute second checksum for a key
     * @param key The key to hash
     * @return 64-bit checksum
     */
    virtual uint64_t hash_checksum_2(int key) const;

    /**
     * Get the number of hash functions
     */
    int get_k() const { return k_; }
    
    /**
     * Get the seed used (for debugging)
     */
    int get_seed() const { return seed_; }

private:
    static constexpr int MAX_RETRIES = 100;
    
    int k_;  // Number of hash functions
    int seed_;  // Random seed (-1 for default)
    std::vector<std::string> position_salts_;  // Salts for position hashing
    std::vector<std::string> checksum_salts_;  // Salts for checksum hashing

protected:
    /**
     * Core SHA256 hashing function
     * @param salt Salt prefix
     * @param key Key to hash
     * @return First 8 bytes of SHA256 as uint64_t (big-endian)
     */
    uint64_t sha256_hash(const std::string& salt, int key) const;

    /**
     * Compute initial positions (may have collisions)
     */
    std::vector<int> compute_initial_positions(int key, int bucket_count) const;

    /**
     * Compute positions with retry salt
     */
    std::vector<int> compute_retry_positions(int key, int bucket_count, int attempt) const;

    /**
     * Check if all positions are unique
     */
    static bool all_unique(const std::vector<int>& positions);

    /**
     * Fallback: deterministic deduplication + linear probing
     * Ensures exactly k distinct positions
     */
    std::vector<int> fallback_dedup(const std::vector<int>& positions,
                                    int bucket_count) const;
};

}  // namespace sciblt
