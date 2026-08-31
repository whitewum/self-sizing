#include "core/iblt/hash_family.h"
#include <openssl/sha.h>
#include <sstream>
#include <random>
#include <algorithm>
#include <unordered_set>
#include <stdexcept>

namespace sciblt {

HashFamily::HashFamily(int k, int seed)
    : k_(k), seed_(seed) {
    
    if (k <= 0) {
        throw std::invalid_argument("k must be positive");
    }

    // Initialize salts
    if (seed < 0) {
        // Default deterministic salts (MATCHING PYTHON three_message_iblt.py)
        // Python: [f"pos_{i}".encode() for i in range(k)]
        position_salts_.reserve(k);
        for (int i = 0; i < k; ++i) {
            position_salts_.push_back("pos_" + std::to_string(i));
        }
        // Python: [b"check_1", b"check_2"]
        checksum_salts_ = {"check_1", "check_2"};
    } else {
        // Generate random salts using seed
        // Note: This will NOT match Python's random.Random(seed) exactly across languages.
        // It provides C++-side determinism but not cross-language determinism for seeded runs.
        std::mt19937 rng(static_cast<unsigned int>(seed));
        std::uniform_int_distribution<int> dist(0, 999999);
        
        position_salts_.reserve(k);
        for (int i = 0; i < k; ++i) {
            position_salts_.push_back("pos_" + std::to_string(dist(rng)));
        }
        
        checksum_salts_ = {
            "check1_" + std::to_string(dist(rng)),
            "check2_" + std::to_string(dist(rng))
        };
    }
}

uint64_t HashFamily::sha256_hash(const std::string& salt, int key) const {
    // Create input string: salt + str(key)
    std::string input = salt + std::to_string(key);
    
    // Compute SHA256
    unsigned char hash[SHA256_DIGEST_LENGTH];
    SHA256(reinterpret_cast<const unsigned char*>(input.c_str()),
           input.length(),
           hash);
    
    // Convert first 8 bytes to uint64_t (big-endian, matching Python)
    uint64_t result = 0;
    for (int i = 0; i < 8; ++i) {
        result = (result << 8) | hash[i];
    }
    
    return result;
}

std::vector<int> HashFamily::compute_initial_positions(int key, int bucket_count) const {
    std::vector<int> positions;
    positions.reserve(k_);
    
    for (int i = 0; i < k_; ++i) {
        uint64_t hash_val = sha256_hash(position_salts_[i], key);
        positions.push_back(static_cast<int>(hash_val % bucket_count));
    }
    
    return positions;
}

std::vector<int> HashFamily::compute_retry_positions(int key, int bucket_count, int attempt) const {
    std::vector<int> positions;
    positions.reserve(k_);
    
    for (int i = 0; i < k_; ++i) {
        std::string retry_salt = position_salts_[i] + "_retry_" + std::to_string(attempt);
        uint64_t hash_val = sha256_hash(retry_salt, key);
        positions.push_back(static_cast<int>(hash_val % bucket_count));
    }
    
    return positions;
}

bool HashFamily::all_unique(const std::vector<int>& positions) {
    std::unordered_set<int> seen;
    for (int pos : positions) {
        if (!seen.insert(pos).second) {
            return false;  // Duplicate found
        }
    }
    return true;
}

std::vector<int> HashFamily::fallback_dedup(const std::vector<int>& positions,
                                             int bucket_count) const {
    std::vector<int> result;
    std::unordered_set<int> used;
    
    // First, add all unique positions from input
    for (int pos : positions) {
        if (used.insert(pos).second) {
            result.push_back(pos);
        }
    }
    
    // If we don't have enough positions, use linear probing
    if (result.size() < static_cast<size_t>(k_)) {
        int probe = 0;
        
        while (result.size() < static_cast<size_t>(k_)) {
            if (used.insert(probe).second) {
                result.push_back(probe);
            }
            probe = (probe + 1) % bucket_count;
            
            // Safety check: prevent infinite loop
            if (used.size() >= static_cast<size_t>(bucket_count)) {
                throw std::runtime_error(
                    "Cannot generate " + std::to_string(k_) +
                    " distinct positions with bucket_count=" + std::to_string(bucket_count));
            }
        }
    }
    
    return result;
}

std::vector<int> HashFamily::hash_positions(int key, int bucket_count) const {
    if (bucket_count < k_) {
        throw std::invalid_argument(
            "bucket_count (" + std::to_string(bucket_count) +
            ") must be at least k (" + std::to_string(k_) + ")");
    }

    // Step 1: Try initial positions
    std::vector<int> positions = compute_initial_positions(key, bucket_count);
    
    if (all_unique(positions)) {
        return positions;  // Fast path: no collision
    }

    // Step 2: Rejection sampling with retry
    for (int attempt = 1; attempt <= MAX_RETRIES; ++attempt) {
        positions = compute_retry_positions(key, bucket_count, attempt);
        
        if (all_unique(positions)) {
            return positions;  // Success after retry
        }
    }

    // Step 3: Fallback - deterministic deduplication
    return fallback_dedup(positions, bucket_count);
}

uint64_t HashFamily::hash_checksum_1(int key) const {
    return sha256_hash(checksum_salts_[0], key);
}

uint64_t HashFamily::hash_checksum_2(int key) const {
    return sha256_hash(checksum_salts_[1], key);
}

}  // namespace sciblt
