#pragma once

#include "core/iblt/standard_iblt.h"

#include <cstdint>

namespace sciblt {

struct PlainF2Stats {
    int M = 0;
    int k = 0;
    int64_t s_net = 0;
    int64_t s_sq = 0;
    double e_global = 0.0;
    double c_theory = 0.0;
    double d_hat = 0.0;
};

double plain_f2_c_theory(int M, int k);

PlainF2Stats estimate_plain_f2_from_counts(const StandardIBLT& diff);

}  // namespace sciblt
