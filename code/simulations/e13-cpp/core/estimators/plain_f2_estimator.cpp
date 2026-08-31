#include "core/estimators/plain_f2_estimator.h"

#include <algorithm>

namespace sciblt {

double plain_f2_c_theory(int M, int k) {
    if (M <= 0) {
        return 0.0;
    }
    return std::max(1e-9, 1.0 - static_cast<double>(k) / static_cast<double>(M));
}

PlainF2Stats estimate_plain_f2_from_counts(const StandardIBLT& diff) {
    PlainF2Stats stats;
    stats.M = diff.get_num_cells();
    stats.k = diff.get_k();

    const auto& cells = diff.get_cells();
    for (const auto& cell : cells) {
        const int64_t c = static_cast<int64_t>(cell.count);
        stats.s_net += c;
        stats.s_sq += c * c;
    }

    const double M = static_cast<double>(std::max(1, stats.M));
    stats.e_global = static_cast<double>(stats.s_sq) -
                     (static_cast<double>(stats.s_net) * static_cast<double>(stats.s_net)) / M;
    if (stats.e_global < 0.0) {
        stats.e_global = 0.0;
    }

    stats.c_theory = plain_f2_c_theory(stats.M, stats.k);
    if (stats.k > 0) {
        stats.d_hat = stats.e_global / (static_cast<double>(stats.k) * stats.c_theory);
    }

    return stats;
}

}  // namespace sciblt
