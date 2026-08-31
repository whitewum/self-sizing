#pragma once

#include "core/iblt/hash_family.h"
#include <vector>
#include <set>
#include <memory>
#include <cstdint>

namespace sciblt {

/**
 * A single cell in the IBLT
 */
struct IBLTCell {
    int count = 0;
    int key_sum = 0;
    uint64_t hash_sum1 = 0;
    uint64_t hash_sum2 = 0;

    /**
     * Check if cell is empty (all fields zero)
     */
    bool is_empty() const {
        return count == 0 && key_sum == 0 && hash_sum1 == 0 && hash_sum2 == 0;
    }

    /**
     * Check if cell is peelable (|count| == 1 and checksums match)
     */
    bool is_peelable() const {
        if (count != 1 && count != -1) {
            return false;
        }
        
        // If count is ±1, key should be recoverable
        // We'll verify checksum during actual peeling
        return true;
    }
};

/**
 * Result of IBLT decode operation
 */
struct DecodeResult {
    bool success;                      // Whether decoding succeeded
    std::set<int> plus_keys;          // Keys with count > 0 (A-only)
    std::set<int> minus_keys;         // Keys with count < 0 (B-only)
    int recovered_count;               // Total keys recovered
    std::vector<IBLTCell> core_cells; // Residual core (only if return_core=true)
    
    DecodeResult()
        : success(false), recovered_count(0) {}
};

/**
 * Result of a single decode step (for iterative peeling)
 */
struct DecodeStepResult {
    std::set<int> new_keys;  // Newly decoded keys in this step
    bool progress;           // Whether any progress was made
    
    DecodeStepResult() : progress(false) {}
};

/**
 * Standard IBLT (Invertible Bloom Lookup Table)
 * 
 * This implementation MUST maintain numerical consistency with
 * history/code/standard_iblt.py:127-245
 * 
 * Key features:
 * - Multiple random retry for decode (default 30 attempts)
 * - Returns "best failure residual" when all attempts fail
 * - Supports incremental decode_step for joint decoding
 */
class StandardIBLT {
public:
    /**
     * Constructor
     * @param num_cells Number of cells (M)
     * @param k Number of hash functions (typically 3)
     * @param seed Random seed for hash family (-1 for default)
     * @param use_fast_hash Use splitmix64-based fast hash (default true).
     *                      Set false for SHA256 (cross-language consistency).
     */
    StandardIBLT(int num_cells, int k = 3, int seed = -1,
                 bool use_fast_hash = true);

    /**
     * Constructor with custom HashFamily
     * @param num_cells Number of cells (M)
     * @param hash_family Ownership of hash family to use
     */
    StandardIBLT(int num_cells, std::unique_ptr<HashFamily> hash_family);

    /**
     * Insert a key into the IBLT
     * @param key Key to insert
     */
    void insert(int key);

    /**
     * Delete a key from the IBLT (insert with negative count)
     * Note: 'delete' is a C++ keyword, so we use 'delete_'
     * @param key Key to delete
     */
    void delete_(int key);

    /**
     * Decode the IBLT to recover keys
     * 
     * Critical: This uses multiple random retries to maximize success rate
     * and return the best failure residual.
     * 
     * @param enable_shuffle_retry Enable random order retries (default true)
     * @param max_retries Maximum retry attempts (default 30)
     * @param return_core Return residual core in result (default false)
     * @param rng_seed Seed for retry randomization (0 for time-based)
     * @return DecodeResult with recovered keys or best failure residual
     */
    DecodeResult decode(bool enable_shuffle_retry = true,
                       int max_retries = 30,
                       bool return_core = false,
                       unsigned int rng_seed = 0) const;

    /**
     * Remove known keys from the IBLT (destructive).
     *
     * For each plus_key, peels it as a +1 element (same as delete_).
     * For each minus_key, peels it as a -1 element (same as insert).
     * This is used in joint decoding: keys recovered from one IBLT are
     * removed from the other's residual to enable further peeling.
     *
     * @param plus_keys Keys that were decoded with count > 0
     * @param minus_keys Keys that were decoded with count < 0
     */
    void remove_keys(const std::set<int>& plus_keys,
                     const std::set<int>& minus_keys);

    /**
     * Perform a single step of peeling decode
     * 
     * WARNING: This operation is DESTRUCTIVE. It modifies the IBLT in-place
     * by peeling keys. This is intended for use in joint decoding where
     * we want to incrementally peel and synchronize state.
     * 
     * Unlike full decode(), this doesn't retry or shuffle.
     * 
     * @return DecodeStepResult with newly decoded keys
     */
    DecodeStepResult decode_step();

    /**
     * Subtract another IBLT from this one
     * Returns a new IBLT representing the difference
     * 
     * @param other IBLT to subtract
     * @return New IBLT = this - other
     */
    StandardIBLT subtract(const StandardIBLT& other) const;

    /**
     * Check if IBLT is empty (all cells are empty)
     */
    bool is_empty() const;

    /**
     * Create a deep copy of this IBLT
     */
    StandardIBLT copy() const;

    /**
     * Get number of cells
     */
    int get_num_cells() const { return num_cells_; }

    /**
     * Get k (number of hash functions)
     */
    int get_k() const { return hash_family_->get_k(); }
    
    /**
     * Get seed (for debugging)
     */
    int get_seed() const { return hash_family_->get_seed(); }

    /**
     * Get cells (for testing/inspection)
     */
    const std::vector<IBLTCell>& get_cells() const { return cells_; }

    /**
     * Set cells directly (for deserialization/reconstruction in Phase 7)
     * The cells vector must have exactly num_cells_ elements.
     */
    void set_cells(const std::vector<IBLTCell>& cells);

private:
    int num_cells_;
    bool use_fast_hash_;
    std::unique_ptr<HashFamily> hash_family_;
    std::vector<IBLTCell> cells_;

    /**
     * Attempt to decode with a specific scan order
     * 
     * @param scan_order Order to scan cells (e.g., [0,1,2,...] or shuffled)
     * @param return_core Whether to save residual core
     * @return DecodeResult for this attempt
     */
    DecodeResult attempt_decode(const std::vector<int>& scan_order,
                               bool return_core) const;

    /**
     * Compute "burden" of a decode result for comparison
     * Returns (negative_recovered_count, remaining_cells)
     * 
     * We want to maximize recovered_count, so we negate it for std::min comparison
     */
    static std::pair<int, int> compute_burden(const DecodeResult& result);

    /**
     * Verify if a cell's checksums match the expected key
     */
    bool verify_cell(const IBLTCell& cell, int key) const;
};

/**
 * Result of joint decoding two IBLTs.
 */
struct JointDecodeResult {
    bool success;             // All keys recovered (both IBLTs empty)
    std::set<int> plus_keys;  // Union of all recovered plus keys
    std::set<int> minus_keys; // Union of all recovered minus keys
    int recovered_count;
    int rounds;               // Number of alternating peel rounds used

    JointDecodeResult()
        : success(false), recovered_count(0), rounds(0) {}
};

/**
 * Joint peeling decode of two diff-IBLTs sharing the same key universe.
 *
 * Algorithm:
 *   1. Peel iblt1 until stuck.
 *   2. Remove iblt1's newly recovered keys from iblt2.
 *   3. Peel iblt2 until stuck.
 *   4. Remove iblt2's newly recovered keys from iblt1.
 *   5. Repeat until neither makes progress.
 *
 * Both IBLTs are modified in place (destructive).
 *
 * @param iblt1 First diff-IBLT (modified in place)
 * @param iblt2 Second diff-IBLT (modified in place)
 * @param max_rounds Safety limit on alternation rounds (default 100)
 * @return JointDecodeResult with union of recovered keys
 */
JointDecodeResult joint_decode(StandardIBLT& iblt1,
                               StandardIBLT& iblt2,
                               int max_rounds = 100);

}  // namespace sciblt
