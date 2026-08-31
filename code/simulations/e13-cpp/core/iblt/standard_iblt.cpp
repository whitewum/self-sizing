#include "core/iblt/standard_iblt.h"
#include "core/iblt/fast_hash_family.h"
#include <algorithm>
#include <random>
#include <stdexcept>
#include <limits>

namespace sciblt {

static std::unique_ptr<HashFamily> make_hash_family(int k, int seed, bool fast) {
    if (fast) return std::make_unique<FastHashFamily>(k, seed);
    return std::make_unique<HashFamily>(k, seed);
}

StandardIBLT::StandardIBLT(int num_cells, int k, int seed, bool use_fast_hash)
    : num_cells_(num_cells),
      use_fast_hash_(use_fast_hash),
      hash_family_(make_hash_family(k, seed, use_fast_hash)),
      cells_(num_cells) {

    if (num_cells <= 0) {
        throw std::invalid_argument("num_cells must be positive");
    }
}

StandardIBLT::StandardIBLT(int num_cells, std::unique_ptr<HashFamily> hash_family)
    : num_cells_(num_cells),
      hash_family_(std::move(hash_family)),
      cells_(num_cells) {
    if (num_cells <= 0) {
        throw std::invalid_argument("num_cells must be positive");
    }
}    

void StandardIBLT::insert(int key) {
    auto positions = hash_family_->hash_positions(key, num_cells_);
    uint64_t checksum1 = hash_family_->hash_checksum_1(key);
    uint64_t checksum2 = hash_family_->hash_checksum_2(key);

    for (int pos : positions) {
        cells_[pos].count += 1;
        cells_[pos].key_sum ^= key;  // Use XOR, not addition!
        cells_[pos].hash_sum1 ^= checksum1;
        cells_[pos].hash_sum2 ^= checksum2;
    }
}

void StandardIBLT::delete_(int key) {
    auto positions = hash_family_->hash_positions(key, num_cells_);
    uint64_t checksum1 = hash_family_->hash_checksum_1(key);
    uint64_t checksum2 = hash_family_->hash_checksum_2(key);

    for (int pos : positions) {
        cells_[pos].count -= 1;
        cells_[pos].key_sum ^= key;  // Use XOR, not subtraction!
        cells_[pos].hash_sum1 ^= checksum1;
        cells_[pos].hash_sum2 ^= checksum2;
    }
}

bool StandardIBLT::verify_cell(const IBLTCell& cell, int key) const {
    uint64_t expected_checksum1 = hash_family_->hash_checksum_1(key);
    uint64_t expected_checksum2 = hash_family_->hash_checksum_2(key);
    
    return cell.hash_sum1 == expected_checksum1 &&
           cell.hash_sum2 == expected_checksum2;
}

DecodeResult StandardIBLT::attempt_decode(const std::vector<int>& scan_order,
                                          bool return_core) const {
    DecodeResult result;
    
    // Create a working copy of cells
    std::vector<IBLTCell> working_cells = cells_;
    
    bool progress = true;
    
    while (progress) {
        progress = false;
        
        for (int idx : scan_order) {
            IBLTCell cell = working_cells[idx];  // Make a copy, not a reference
            
            // Skip empty cells
            if (cell.is_empty()) {
                continue;
            }
            
            // Check if peelable
            if (cell.count == 1 || cell.count == -1) {
                int recovered_key = cell.key_sum;  // key_sum is XOR, so just use it directly
                
                // Verify checksum
                if (!verify_cell(cell, recovered_key)) {
                    continue;  // Checksum mismatch, not truly peelable
                }
                
                // Valid peelable cell found
                progress = true;
                
                // Add to appropriate set (check if not already added)
                if (cell.count == 1) {
                    if (result.plus_keys.insert(recovered_key).second) {
                        // Newly added key, peel it from all positions
                        auto positions = hash_family_->hash_positions(recovered_key, num_cells_);
                        uint64_t checksum1 = hash_family_->hash_checksum_1(recovered_key);
                        uint64_t checksum2 = hash_family_->hash_checksum_2(recovered_key);
                        
                        for (int pos : positions) {
                            working_cells[pos].count -= 1;
                            working_cells[pos].key_sum ^= recovered_key;
                            working_cells[pos].hash_sum1 ^= checksum1;
                            working_cells[pos].hash_sum2 ^= checksum2;
                        }
                    }
                } else {  // count == -1
                    if (result.minus_keys.insert(recovered_key).second) {
                        // Newly added key, peel it from all positions
                        auto positions = hash_family_->hash_positions(recovered_key, num_cells_);
                        uint64_t checksum1 = hash_family_->hash_checksum_1(recovered_key);
                        uint64_t checksum2 = hash_family_->hash_checksum_2(recovered_key);
                        
                        for (int pos : positions) {
                            working_cells[pos].count += 1;
                            working_cells[pos].key_sum ^= recovered_key;
                            working_cells[pos].hash_sum1 ^= checksum1;
                            working_cells[pos].hash_sum2 ^= checksum2;
                        }
                    }
                }
            }
        }
    }
    
    // Check if fully decoded
    bool all_empty = true;
    for (const auto& cell : working_cells) {
        if (!cell.is_empty()) {
            all_empty = false;
            break;
        }
    }
    
    result.success = all_empty;
    result.recovered_count = result.plus_keys.size() + result.minus_keys.size();
    
    if (return_core && !result.success) {
        result.core_cells = working_cells;
    }
    
    return result;
}

std::pair<int, int> StandardIBLT::compute_burden(const DecodeResult& result) {
    // Burden = (negative recovered count, remaining cells)
    // We want to maximize recovered_count, so negate it
    int remaining_cells = 0;
    for (const auto& cell : result.core_cells) {
        if (!cell.is_empty()) {
            remaining_cells++;
        }
    }
    
    return {-result.recovered_count, remaining_cells};
}

DecodeResult StandardIBLT::decode(bool enable_shuffle_retry,
                                  int max_retries,
                                  bool return_core,
                                  unsigned int rng_seed) const {
    
    // Step 1: Try deterministic order first
    std::vector<int> sequential_order(num_cells_);
    for (int i = 0; i < num_cells_; ++i) {
        sequential_order[i] = i;
    }
    
    DecodeResult best_result = attempt_decode(sequential_order, return_core);
    
    if (best_result.success) {
        return best_result;  // Success on first try
    }
    
    // Step 2: Initialize best burden
    auto best_burden = compute_burden(best_result);
    
    // Step 3: Random retries if enabled
    if (enable_shuffle_retry && max_retries > 0) {
        // Initialize RNG
        std::mt19937 rng;
        if (rng_seed == 0) {
            std::random_device rd;
            rng.seed(rd());
        } else {
            rng.seed(rng_seed);
        }
        
        for (int attempt = 1; attempt <= max_retries; ++attempt) {
            // Create shuffled order
            std::vector<int> shuffled_order = sequential_order;
            std::shuffle(shuffled_order.begin(), shuffled_order.end(), rng);
            
            // Attempt decode
            DecodeResult result = attempt_decode(shuffled_order, return_core);
            
            if (result.success) {
                return result;  // Success!
            }
            
            // Update best result if this one is better
            auto burden = compute_burden(result);
            if (burden < best_burden) {
                best_result = result;
                best_burden = burden;
            }
        }
    }
    
    // Step 4: All attempts failed, return best failure result
    return best_result;
}

void StandardIBLT::remove_keys(const std::set<int>& plus_keys,
                               const std::set<int>& minus_keys) {
    for (int key : plus_keys) {
        delete_(key);  // plus key has count +1, remove by subtracting
    }
    for (int key : minus_keys) {
        insert(key);   // minus key has count -1, remove by adding
    }
}

DecodeStepResult StandardIBLT::decode_step() {
    DecodeStepResult result;
    
    // Scan all cells for peelable ones
    for (int idx = 0; idx < num_cells_; ++idx) {
        IBLTCell& cell = cells_[idx];
        
        if (cell.is_empty()) {
            continue;
        }
        
        if (cell.count == 1 || cell.count == -1) {
            int recovered_key = cell.key_sum;  // key_sum is XOR
            
            // Verify checksum
            if (!verify_cell(cell, recovered_key)) {
                continue;
            }
            
            // Valid peelable cell
            result.new_keys.insert(recovered_key);
            result.progress = true;
            
            // Peel immediately
            auto positions = hash_family_->hash_positions(recovered_key, num_cells_);
            uint64_t checksum1 = hash_family_->hash_checksum_1(recovered_key);
            uint64_t checksum2 = hash_family_->hash_checksum_2(recovered_key);
            
            int delta = (cell.count == 1) ? 1 : -1;

            for (int pos : positions) {
                cells_[pos].count -= delta;
                cells_[pos].key_sum ^= recovered_key;
                cells_[pos].hash_sum1 ^= checksum1;
                cells_[pos].hash_sum2 ^= checksum2;
            }
        }
    }
    
    return result;
}

StandardIBLT StandardIBLT::subtract(const StandardIBLT& other) const {
    if (num_cells_ != other.num_cells_) {
        throw std::invalid_argument("Cannot subtract IBLTs with different sizes");
    }

    // Create result IBLT (preserve hash type)
    StandardIBLT result(num_cells_, get_k(), get_seed(), use_fast_hash_);

    // Subtract cell by cell
    for (int i = 0; i < num_cells_; ++i) {
        result.cells_[i].count = cells_[i].count - other.cells_[i].count;
        result.cells_[i].key_sum = cells_[i].key_sum ^ other.cells_[i].key_sum;
        result.cells_[i].hash_sum1 = cells_[i].hash_sum1 ^ other.cells_[i].hash_sum1;
        result.cells_[i].hash_sum2 = cells_[i].hash_sum2 ^ other.cells_[i].hash_sum2;
    }
    
    return result;
}

bool StandardIBLT::is_empty() const {
    for (const auto& cell : cells_) {
        if (!cell.is_empty()) {
            return false;
        }
    }
    return true;
}

StandardIBLT StandardIBLT::copy() const {
    StandardIBLT result(num_cells_, get_k(), get_seed(), use_fast_hash_);
    result.cells_ = cells_;
    return result;
}

void StandardIBLT::set_cells(const std::vector<IBLTCell>& cells) {
    if (static_cast<int>(cells.size()) != num_cells_) {
        throw std::invalid_argument("cells size must match num_cells_");
    }
    cells_ = cells;
}

JointDecodeResult joint_decode(StandardIBLT& iblt1,
                               StandardIBLT& iblt2,
                               int max_rounds) {
    JointDecodeResult result;

    for (int round = 0; round < max_rounds; ++round) {
        result.rounds = round + 1;
        bool made_progress = false;

        // Phase A: peel iblt1
        {
            auto copy1 = iblt1.copy();
            auto dr = copy1.decode(true, 5, false, round + 1);
            if (dr.recovered_count > 0) {
                // Check for genuinely new keys
                std::set<int> new_plus, new_minus;
                for (int k : dr.plus_keys) {
                    if (result.plus_keys.insert(k).second) new_plus.insert(k);
                }
                for (int k : dr.minus_keys) {
                    if (result.minus_keys.insert(k).second) new_minus.insert(k);
                }
                if (!new_plus.empty() || !new_minus.empty()) {
                    made_progress = true;
                    iblt1.remove_keys(new_plus, new_minus);
                    iblt2.remove_keys(new_plus, new_minus);
                }
            }
        }

        // Phase B: peel iblt2
        {
            auto copy2 = iblt2.copy();
            auto dr = copy2.decode(true, 5, false, round + 100);
            if (dr.recovered_count > 0) {
                std::set<int> new_plus, new_minus;
                for (int k : dr.plus_keys) {
                    if (result.plus_keys.insert(k).second) new_plus.insert(k);
                }
                for (int k : dr.minus_keys) {
                    if (result.minus_keys.insert(k).second) new_minus.insert(k);
                }
                if (!new_plus.empty() || !new_minus.empty()) {
                    made_progress = true;
                    iblt1.remove_keys(new_plus, new_minus);
                    iblt2.remove_keys(new_plus, new_minus);
                }
            }
        }

        if (!made_progress) break;
    }

    result.recovered_count = result.plus_keys.size() + result.minus_keys.size();
    result.success = iblt1.is_empty() && iblt2.is_empty();
    return result;
}

}  // namespace sciblt
