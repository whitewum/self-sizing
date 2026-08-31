#pragma once

#include "core/iblt/hash_family.h"
#include <stdexcept>
#include <string>

namespace sciblt {

class FastHashFamily : public HashFamily {
public:
    explicit FastHashFamily(int k = 3, int seed = -1)
        : HashFamily(k, seed)
    {
        uint64_t s = (seed < 0) ? 0x12345678ABCDEF01ULL
                                : splitmix(static_cast<uint64_t>(seed) + 1);
        position_seeds_.reserve(k);
        for (int i = 0; i < k; ++i) {
            s = splitmix(s);
            position_seeds_.push_back(s);
        }
        s = splitmix(s);
        checksum_seed1_ = s;
        s = splitmix(s);
        checksum_seed2_ = s;
    }

    std::vector<int> hash_positions(int key, int bucket_count) const override {
        const int k = get_k();
        if (bucket_count < k) {
            throw std::invalid_argument(
                "bucket_count (" + std::to_string(bucket_count) +
                ") must be at least k (" + std::to_string(k) + ")");
        }

        const uint64_t kbits = key_to_u64(key);
        std::vector<int> pos;
        pos.reserve(k);

        for (int i = 0; i < k; ++i) {
            pos.push_back(static_cast<int>(
                splitmix(position_seeds_[i] + kbits) %
                static_cast<uint64_t>(bucket_count)));
        }
        if (all_unique(pos)) return pos;

        for (int attempt = 1; attempt <= 100; ++attempt) {
            pos.clear();
            const uint64_t amix = splitmix(
                static_cast<uint64_t>(attempt) * 0x9E3779B97F4A7C15ULL);
            for (int i = 0; i < k; ++i) {
                pos.push_back(static_cast<int>(
                    splitmix(position_seeds_[i] + kbits + amix) %
                    static_cast<uint64_t>(bucket_count)));
            }
            if (all_unique(pos)) return pos;
        }

        return fallback_dedup(pos, bucket_count);
    }

    uint64_t hash_checksum_1(int key) const override {
        return splitmix(checksum_seed1_ + key_to_u64(key));
    }

    uint64_t hash_checksum_2(int key) const override {
        return splitmix(checksum_seed2_ + key_to_u64(key));
    }

private:
    std::vector<uint64_t> position_seeds_;
    uint64_t checksum_seed1_;
    uint64_t checksum_seed2_;

    static uint64_t splitmix(uint64_t x) {
        x ^= x >> 30;
        x *= 0xbf58476d1ce4e5b9ULL;
        x ^= x >> 27;
        x *= 0x94d049bb133111ebULL;
        x ^= x >> 31;
        return x;
    }

    static uint64_t key_to_u64(int key) {
        return static_cast<uint64_t>(static_cast<uint32_t>(key))
               * 0x9E3779B97F4A7C15ULL;
    }
};

}  // namespace sciblt
